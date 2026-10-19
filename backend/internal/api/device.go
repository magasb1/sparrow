// Package api implements sparrow.v1.DeviceService, the API phones call.
package api

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"log/slog"
	"math"
	"net/http"
	"strings"
	"time"

	"connectrpc.com/connect"
	"google.golang.org/protobuf/proto"

	sparrowv1 "github.com/magasb1/sparrow/backend/gen/sparrow/v1"
	"github.com/magasb1/sparrow/backend/gen/sparrow/v1/sparrowv1connect"
	"github.com/magasb1/sparrow/backend/internal/hub"
	"github.com/magasb1/sparrow/backend/internal/store"
)

// HeartbeatInterval is how often an open Subscribe stream carries a heartbeat.
const HeartbeatInterval = 15 * time.Second

type Service struct {
	store     *store.Store
	hub       *hub.Hub
	log       *slog.Logger
	heartbeat time.Duration
}

var _ sparrowv1connect.DeviceServiceHandler = (*Service)(nil)

func New(st *store.Store, h *hub.Hub, log *slog.Logger) *Service {
	return &Service{store: st, hub: h, log: log, heartbeat: HeartbeatInterval}
}

// SetHeartbeat changes the heartbeat interval (tests).
func (s *Service) SetHeartbeat(d time.Duration) { s.heartbeat = d }

// Handler returns the path prefix and handler to mount.
func (s *Service) Handler() (string, http.Handler) {
	return sparrowv1connect.NewDeviceServiceHandler(s, connect.WithReadMaxBytes(1<<20))
}

// NewToken returns 32 random bytes, base64url encoded.
func NewToken() string {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return base64.RawURLEncoding.EncodeToString(b)
}

// HashToken is how tokens are stored: SHA-256 of the token text.
func HashToken(token string) []byte {
	sum := sha256.Sum256([]byte(token))
	return sum[:]
}

func newPublishKey() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b)
}

var errUnauthenticated = connect.NewError(connect.CodeUnauthenticated, errors.New("unknown or revoked device token"))

func (s *Service) authenticate(ctx context.Context, h http.Header) (store.Device, error) {
	token, ok := strings.CutPrefix(h.Get("Authorization"), "Bearer ")
	if !ok || token == "" {
		return store.Device{}, errUnauthenticated
	}
	d, err := s.store.DeviceByToken(ctx, HashToken(token))
	if errors.Is(err, store.ErrNotFound) {
		return d, errUnauthenticated
	}
	if err != nil {
		return d, s.internal("look up device", err)
	}
	return d, nil
}

func (s *Service) internal(what string, err error) error {
	s.log.Error(what, "err", err)
	return connect.NewError(connect.CodeInternal, errors.New(what+" failed"))
}

func (s *Service) Register(ctx context.Context, req *connect.Request[sparrowv1.RegisterRequest]) (*connect.Response[sparrowv1.RegisterResponse], error) {
	m := req.Msg
	if m.GetEnrollmentToken() == "" {
		return nil, connect.NewError(connect.CodeInvalidArgument, errors.New("enrollment_token is required"))
	}
	info := m.GetDevice()
	token := NewToken()
	d, err := s.store.Register(ctx, HashToken(m.GetEnrollmentToken()), HashToken(token), newPublishKey(), store.DeviceInfo{
		Manufacturer: clip(info.GetManufacturer(), 100),
		Model:        clip(info.GetModel(), 100),
		AppVersion:   clip(info.GetAppVersion(), 50),
		AndroidSDK:   int(info.GetAndroidSdk()),
		ZoomMin:      info.GetZoomMin(),
		ZoomMax:      info.GetZoomMax(),
	})
	if errors.Is(err, store.ErrEnrollmentInvalid) {
		return nil, connect.NewError(connect.CodePermissionDenied, err)
	}
	if err != nil {
		return nil, s.internal("register", err)
	}
	s.log.Info("device registered", "alias", d.Alias, "id", d.ID, "model", d.Name)
	return connect.NewResponse(&sparrowv1.RegisterResponse{
		DeviceId:    d.ID,
		DeviceToken: token,
		Alias:       d.Alias,
		Config:      &sparrowv1.DeviceConfig{},
	}), nil
}

func (s *Service) Report(ctx context.Context, req *connect.Request[sparrowv1.ReportRequest]) (*connect.Response[sparrowv1.ReportResponse], error) {
	d, err := s.authenticate(ctx, req.Header())
	if err != nil {
		return nil, err
	}
	m := req.Msg
	r := store.Report{
		Time:          time.Now(),
		State:         stateName(m.GetState()),
		Reason:        clip(m.GetReason(), 500),
		Profile:       clip(m.GetProfileName(), 200),
		SendKbps:      m.GetSendKbps(),
		TargetKbps:    int(min(m.GetTargetKbps(), math.MaxInt32)),
		RttMs:         m.RttMs,
		LossPercent:   m.LossPercent,
		Zoom:          m.GetZoom(),
		Resolution:    clip(m.GetResolution(), 50),
		Charging:      m.GetCharging(),
		ThermalStatus: int(min(m.GetThermalStatus(), math.MaxInt16)),
		// Clamped so a phone cannot write a negative or absurd revision.
		ConfigRevision: int64(min(m.GetConfigRevision(), 1<<62)),
	}
	if t := m.GetTime(); t.IsValid() {
		r.Time = t.AsTime()
	}
	if m.BatteryPercent != nil {
		b := int(min(m.GetBatteryPercent(), 100))
		r.Battery = &b
	}
	if p := m.GetPosition(); p != nil && validLatLon(p.GetLat(), p.GetLon()) {
		r.Lat, r.Lon = &p.Lat, &p.Lon
		r.AltMsl, r.HeadingDeg, r.ElevationDeg = p.AltMsl, p.HeadingDeg, p.ElevationDeg
		if p.AccuracyM != nil {
			a := float64(p.GetAccuracyM())
			r.AccuracyM = &a
		}
	}
	if err := s.store.InsertReport(ctx, d.ID, r); err != nil {
		return nil, s.internal("store report", err)
	}
	rev, _, err := s.store.Config(ctx, d.ID)
	if err != nil {
		return nil, s.internal("read config", err)
	}
	return connect.NewResponse(&sparrowv1.ReportResponse{LatestConfigRevision: uint64(rev)}), nil
}

func (s *Service) Subscribe(ctx context.Context, req *connect.Request[sparrowv1.SubscribeRequest], stream *connect.ServerStream[sparrowv1.SubscribeResponse]) error {
	d, err := s.authenticate(ctx, req.Header())
	if err != nil {
		return err
	}
	sess, done := s.hub.Attach(d.ID)
	defer done()
	// Checked again after attaching: a Remove between authenticate and Attach would otherwise
	// leave a revoked device with an open stream.
	if err := s.touch(ctx, d.ID); err != nil {
		return err
	}
	s.log.Info("device connected", "alias", d.Alias)
	defer s.log.Info("device disconnected", "alias", d.Alias)

	// A first event right away tells the phone the stream is up.
	if err := stream.Send(heartbeat()); err != nil {
		return nil
	}
	if rev, _, err := s.store.Config(ctx, d.ID); err == nil && rev > 0 {
		if err := stream.Send(&sparrowv1.SubscribeResponse{
			Event: &sparrowv1.SubscribeResponse_ConfigChanged{ConfigChanged: uint64(rev)},
		}); err != nil {
			return nil
		}
	}
	tick := time.NewTicker(s.heartbeat)
	defer tick.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-sess.Closed():
			return nil
		case ev := <-sess.Events:
			if err := stream.Send(ev); err != nil {
				return nil
			}
		case <-tick.C:
			if err := stream.Send(heartbeat()); err != nil {
				return nil
			}
			if err := s.touch(ctx, d.ID); err != nil {
				return err
			}
		}
	}
}

// touch updates last-seen time and fails with Unauthenticated once the device is revoked.
func (s *Service) touch(ctx context.Context, id string) error {
	err := s.store.Touch(ctx, id)
	if errors.Is(err, store.ErrNotFound) {
		return errUnauthenticated
	}
	if err != nil && ctx.Err() == nil {
		s.log.Warn("touch device", "err", err)
	}
	return nil
}

func heartbeat() *sparrowv1.SubscribeResponse {
	return &sparrowv1.SubscribeResponse{Event: &sparrowv1.SubscribeResponse_Heartbeat{Heartbeat: &sparrowv1.Heartbeat{}}}
}

func (s *Service) CommandDone(ctx context.Context, req *connect.Request[sparrowv1.CommandDoneRequest]) (*connect.Response[sparrowv1.CommandDoneResponse], error) {
	d, err := s.authenticate(ctx, req.Header())
	if err != nil {
		return nil, err
	}
	m := req.Msg
	msg := clip(m.GetMessage(), 500)
	// Stored before the waiting sender is woken, so it can read the result back.
	if err := s.store.CommandDone(ctx, d.ID, m.GetId(), m.GetOk(), msg); err != nil {
		return nil, s.internal("store command result", err)
	}
	s.hub.Done(d.ID, m.GetId(), hub.Result{OK: m.GetOk(), Message: msg})
	return connect.NewResponse(&sparrowv1.CommandDoneResponse{}), nil
}

func (s *Service) GetConfig(ctx context.Context, req *connect.Request[sparrowv1.GetConfigRequest]) (*connect.Response[sparrowv1.GetConfigResponse], error) {
	d, err := s.authenticate(ctx, req.Header())
	if err != nil {
		return nil, err
	}
	rev, raw, err := s.store.Config(ctx, d.ID)
	if err != nil {
		return nil, s.internal("read config", err)
	}
	cfg := &sparrowv1.DeviceConfig{Revision: uint64(rev)}
	if raw != nil {
		cfg.Profile = &sparrowv1.Profile{}
		if err := proto.Unmarshal(raw, cfg.Profile); err != nil {
			return nil, s.internal("decode stored profile", err)
		}
	}
	return connect.NewResponse(&sparrowv1.GetConfigResponse{Config: cfg}), nil
}

func (s *Service) Unregister(ctx context.Context, req *connect.Request[sparrowv1.UnregisterRequest]) (*connect.Response[sparrowv1.UnregisterResponse], error) {
	d, err := s.authenticate(ctx, req.Header())
	if err != nil {
		return nil, err
	}
	if err := s.store.Revoke(ctx, d.ID); err != nil && !errors.Is(err, store.ErrNotFound) {
		return nil, s.internal("unregister", err)
	}
	s.hub.Kick(d.ID)
	s.log.Info("device unregistered itself", "alias", d.Alias)
	return connect.NewResponse(&sparrowv1.UnregisterResponse{}), nil
}

// stateName turns STREAM_STATE_LIVE into "live".
func stateName(s sparrowv1.StreamState) string {
	if s == sparrowv1.StreamState_STREAM_STATE_UNSPECIFIED {
		return "unknown"
	}
	name, ok := sparrowv1.StreamState_name[int32(s)]
	if !ok {
		return "unknown"
	}
	return strings.ToLower(strings.TrimPrefix(name, "STREAM_STATE_"))
}

func validLatLon(lat, lon float64) bool {
	return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180 && !(lat == 0 && lon == 0)
}

// clip shortens s to at most n bytes without splitting a UTF-8 sequence.
func clip(s string, n int) string {
	if len(s) <= n {
		return s
	}
	for n > 0 && n < len(s) && s[n]&0xC0 == 0x80 {
		n--
	}
	return s[:n]
}
