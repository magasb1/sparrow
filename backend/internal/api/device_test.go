package api

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"connectrpc.com/connect"
	"google.golang.org/protobuf/types/known/timestamppb"

	sparrowv1 "github.com/magasb1/sparrow/backend/gen/sparrow/v1"
	"github.com/magasb1/sparrow/backend/gen/sparrow/v1/sparrowv1connect"
	"github.com/magasb1/sparrow/backend/internal/hub"
	"github.com/magasb1/sparrow/backend/internal/store"
	"github.com/magasb1/sparrow/backend/internal/testdb"
)

type env struct {
	st     *store.Store
	hub    *hub.Hub
	client sparrowv1connect.DeviceServiceClient
}

func setup(t *testing.T) *env {
	t.Helper()
	st := testdb.New(t)
	h := hub.New()
	svc := New(st, h, slog.New(slog.NewTextHandler(io.Discard, nil)))
	svc.SetHeartbeat(50 * time.Millisecond)
	mux := http.NewServeMux()
	mux.Handle(svc.Handler())
	// TLS with HTTP/1.1 only: phones may sit behind proxies that do not speak HTTP/2.
	srv := httptest.NewUnstartedServer(mux)
	srv.StartTLS()
	t.Cleanup(srv.Close)
	return &env{st: st, hub: h, client: sparrowv1connect.NewDeviceServiceClient(srv.Client(), srv.URL)}
}

func (e *env) enroll(t *testing.T) string {
	t.Helper()
	tok := NewToken()
	if err := e.st.CreateEnrollment(context.Background(), HashToken(tok), time.Now().Add(time.Hour)); err != nil {
		t.Fatal(err)
	}
	return tok
}

func authed[T any](msg *T, token string) *connect.Request[T] {
	r := connect.NewRequest(msg)
	r.Header().Set("Authorization", "Bearer "+token)
	return r
}

func code(err error) connect.Code {
	var ce *connect.Error
	if errors.As(err, &ce) {
		return ce.Code()
	}
	return connect.CodeUnknown
}

func TestRegisterReportUnregister(t *testing.T) {
	e := setup(t)
	ctx := context.Background()
	enrollment := e.enroll(t)

	reg, err := e.client.Register(ctx, connect.NewRequest(&sparrowv1.RegisterRequest{
		EnrollmentToken: enrollment,
		Device:          &sparrowv1.DeviceInfo{Manufacturer: "Google", Model: "Pixel 8", AndroidSdk: 36, AppVersion: "0.2.0", ZoomMin: 0.5, ZoomMax: 10},
	}))
	if err != nil {
		t.Fatal(err)
	}
	if reg.Msg.GetAlias() != "cam-01" || reg.Msg.GetDeviceToken() == "" || reg.Msg.GetConfig().GetRevision() != 0 {
		t.Fatalf("unexpected registration: %v", reg.Msg)
	}
	token := reg.Msg.GetDeviceToken()

	// The enrollment token works once.
	if _, err := e.client.Register(ctx, connect.NewRequest(&sparrowv1.RegisterRequest{EnrollmentToken: enrollment})); code(err) != connect.CodePermissionDenied {
		t.Fatalf("reused enrollment: %v", err)
	}

	battery := uint32(77)
	rtt := 42.0
	_, err = e.client.Report(ctx, authed(&sparrowv1.ReportRequest{
		Time:           timestamppb.New(time.Date(2026, 10, 19, 12, 0, 0, 0, time.UTC)),
		State:          sparrowv1.StreamState_STREAM_STATE_LIVE,
		ProfileName:    "Default",
		SendKbps:       1180,
		TargetKbps:     1200,
		RttMs:          &rtt,
		Position:       &sparrowv1.Position{Lat: 59.91, Lon: 10.75},
		Zoom:           2,
		Resolution:     "1280×720 H264",
		BatteryPercent: &battery,
	}, token))
	if err != nil {
		t.Fatal(err)
	}
	rows, err := e.st.ListDevices(ctx)
	if err != nil || len(rows) != 1 {
		t.Fatalf("list: %v %v", rows, err)
	}
	r := rows[0]
	if r.Name != "Google Pixel 8" || r.Latest == nil || r.Latest.State != "live" || *r.Latest.Lat != 59.91 ||
		*r.Latest.Battery != 77 || *r.Latest.RttMs != 42 || r.LastSeenAt == nil {
		t.Fatalf("stored row: %+v latest %+v", r, r.Latest)
	}

	cfg, err := e.client.GetConfig(ctx, authed(&sparrowv1.GetConfigRequest{}, token))
	if err != nil || cfg.Msg.GetConfig().GetRevision() != 0 || cfg.Msg.GetConfig().GetProfile() != nil {
		t.Fatalf("config: %v %v", cfg, err)
	}

	if _, err := e.client.Unregister(ctx, authed(&sparrowv1.UnregisterRequest{}, token)); err != nil {
		t.Fatal(err)
	}
	if _, err := e.client.Report(ctx, authed(&sparrowv1.ReportRequest{}, token)); code(err) != connect.CodeUnauthenticated {
		t.Fatalf("report after unregister: %v", err)
	}
}

func TestBadTokens(t *testing.T) {
	e := setup(t)
	ctx := context.Background()
	if _, err := e.client.Report(ctx, connect.NewRequest(&sparrowv1.ReportRequest{})); code(err) != connect.CodeUnauthenticated {
		t.Fatalf("no token: %v", err)
	}
	if _, err := e.client.Report(ctx, authed(&sparrowv1.ReportRequest{}, "nope")); code(err) != connect.CodeUnauthenticated {
		t.Fatalf("bad token: %v", err)
	}
	if _, err := e.client.Register(ctx, connect.NewRequest(&sparrowv1.RegisterRequest{EnrollmentToken: "nope"})); code(err) != connect.CodePermissionDenied {
		t.Fatalf("bad enrollment: %v", err)
	}
	expired := NewToken()
	_ = e.st.CreateEnrollment(ctx, HashToken(expired), time.Now().Add(-time.Minute))
	if _, err := e.client.Register(ctx, connect.NewRequest(&sparrowv1.RegisterRequest{EnrollmentToken: expired})); code(err) != connect.CodePermissionDenied {
		t.Fatalf("expired enrollment: %v", err)
	}
}

func TestSubscribeDeliversEventsAndEndsOnKick(t *testing.T) {
	e := setup(t)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	reg, err := e.client.Register(ctx, connect.NewRequest(&sparrowv1.RegisterRequest{EnrollmentToken: e.enroll(t)}))
	if err != nil {
		t.Fatal(err)
	}
	id, token := reg.Msg.GetDeviceId(), reg.Msg.GetDeviceToken()

	stream, err := e.client.Subscribe(ctx, authed(&sparrowv1.SubscribeRequest{}, token))
	if err != nil {
		t.Fatal(err)
	}
	defer stream.Close()
	// First event: an immediate heartbeat.
	if !stream.Receive() || stream.Msg().GetHeartbeat() == nil {
		t.Fatalf("first event: %v %v", stream.Msg(), stream.Err())
	}
	for !e.hub.Online(id) {
		time.Sleep(5 * time.Millisecond)
	}
	if err := e.hub.Notify(id, &sparrowv1.SubscribeResponse{Event: &sparrowv1.SubscribeResponse_ConfigChanged{ConfigChanged: 3}}); err != nil {
		t.Fatal(err)
	}
	gotConfig, gotHeartbeat := false, false
	for !(gotConfig && gotHeartbeat) && stream.Receive() {
		gotConfig = gotConfig || stream.Msg().GetConfigChanged() == 3
		gotHeartbeat = gotHeartbeat || stream.Msg().GetHeartbeat() != nil
	}
	if !gotConfig || !gotHeartbeat {
		t.Fatalf("events missing: config %v heartbeat %v err %v", gotConfig, gotHeartbeat, stream.Err())
	}

	// Kicking (as on revoke) ends the stream.
	e.hub.Kick(id)
	for stream.Receive() {
	}
	if e.hub.Online(id) {
		t.Fatal("still online after kick")
	}
}

func TestCommandRoundTrip(t *testing.T) {
	e := setup(t)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	reg, err := e.client.Register(ctx, connect.NewRequest(&sparrowv1.RegisterRequest{EnrollmentToken: e.enroll(t)}))
	if err != nil {
		t.Fatal(err)
	}
	id, token := reg.Msg.GetDeviceId(), reg.Msg.GetDeviceToken()
	stream, err := e.client.Subscribe(ctx, authed(&sparrowv1.SubscribeRequest{}, token))
	if err != nil {
		t.Fatal(err)
	}
	defer stream.Close()
	go func() {
		for stream.Receive() {
			if c := stream.Msg().GetCommand(); c != nil {
				_, _ = e.client.CommandDone(ctx, authed(&sparrowv1.CommandDoneRequest{Id: c.GetId(), Ok: true, Message: "zoom 2.0"}, token))
			}
		}
	}()
	for !e.hub.Online(id) {
		time.Sleep(5 * time.Millisecond)
	}
	if err := e.st.InsertCommand(ctx, "c1", id, "zoom", nil); err != nil {
		t.Fatal(err)
	}
	res, err := e.hub.Send(ctx, id, &sparrowv1.Command{Id: "c1", Kind: &sparrowv1.Command_Zoom{Zoom: &sparrowv1.ZoomCommand{Ratio: 2}}})
	if err != nil || !res.OK || res.Message != "zoom 2.0" {
		t.Fatalf("result %+v, %v", res, err)
	}
	var ok bool
	if err := e.st.DB().QueryRow(ctx, `SELECT ok FROM commands WHERE id = 'c1'`).Scan(&ok); err != nil || !ok {
		t.Fatalf("stored result %v %v", ok, err)
	}
}

func TestOutOfRangeReportValuesAreClamped(t *testing.T) {
	e := setup(t)
	ctx := context.Background()
	reg, err := e.client.Register(ctx, connect.NewRequest(&sparrowv1.RegisterRequest{EnrollmentToken: e.enroll(t)}))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := e.client.Report(ctx, authed(&sparrowv1.ReportRequest{
		TargetKbps: 1 << 31, ThermalStatus: 70000, ConfigRevision: 1 << 63,
	}, reg.Msg.GetDeviceToken())); err != nil {
		t.Fatalf("report with huge values: %v", err)
	}
}

func TestClip(t *testing.T) {
	if got := clip("ææææ", 3); got != "æ" {
		t.Fatalf("clip split a rune: %q", got)
	}
	if got := clip("abc", 5); got != "abc" {
		t.Fatal(got)
	}
}
