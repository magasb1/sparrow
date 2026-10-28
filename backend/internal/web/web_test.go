package web

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/url"
	"regexp"
	"strings"
	"testing"
	"time"

	"connectrpc.com/connect"

	sparrowv1 "github.com/magasb1/sparrow/backend/gen/sparrow/v1"
	"github.com/magasb1/sparrow/backend/gen/sparrow/v1/sparrowv1connect"
	"github.com/magasb1/sparrow/backend/internal/api"
	"github.com/magasb1/sparrow/backend/internal/hub"
	"github.com/magasb1/sparrow/backend/internal/testdb"
)

type site struct {
	srv    *httptest.Server
	client *http.Client
	device sparrowv1connect.DeviceServiceClient
}

func newSite(t *testing.T) *site {
	t.Helper()
	st := testdb.New(t)
	h := hub.New()
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	mux := http.NewServeMux()
	mux.Handle(api.New(st, h, log).Handler())
	New(Config{AdminUser: "admin", AdminPassword: "correct horse", Pin: "PIN", SecureCookies: true,
		EnrollmentTTL: time.Hour}, st, h, log).Routes(mux)
	srv := httptest.NewTLSServer(mux)
	t.Cleanup(srv.Close)
	jar, _ := cookiejar.New(nil)
	c := srv.Client()
	c.Jar = jar
	c.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	return &site{srv: srv, client: c, device: sparrowv1connect.NewDeviceServiceClient(srv.Client(), srv.URL)}
}

func (s *site) post(t *testing.T, path string, form url.Values, origin string) *http.Response {
	t.Helper()
	req, _ := http.NewRequest(http.MethodPost, s.srv.URL+path, strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	if origin != "" {
		req.Header.Set("Origin", origin)
	}
	resp, err := s.client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	return resp
}

func (s *site) get(t *testing.T, path string) (int, string) {
	t.Helper()
	resp, err := s.client.Get(s.srv.URL + path)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, string(b)
}

func (s *site) login(t *testing.T) {
	t.Helper()
	resp := s.post(t, "/login", url.Values{"user": {"admin"}, "password": {"correct horse"}}, s.srv.URL)
	resp.Body.Close()
	if resp.StatusCode != http.StatusSeeOther {
		t.Fatalf("login: %d", resp.StatusCode)
	}
}

func TestLoginRequired(t *testing.T) {
	s := newSite(t)
	if code, _ := s.get(t, "/"); code != http.StatusSeeOther {
		t.Fatalf("anonymous devices page: %d", code)
	}
	resp := s.post(t, "/login", url.Values{"user": {"admin"}, "password": {"wrong"}}, s.srv.URL)
	resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("wrong password: %d", resp.StatusCode)
	}
	s.login(t)
	if code, body := s.get(t, "/"); code != http.StatusOK || !strings.Contains(body, "No phones yet") {
		t.Fatalf("devices page: %d", code)
	}
	resp = s.post(t, "/logout", nil, s.srv.URL)
	resp.Body.Close()
	if code, _ := s.get(t, "/"); code != http.StatusSeeOther {
		t.Fatalf("after logout: %d", code)
	}
}

func TestCrossOriginPostRefused(t *testing.T) {
	s := newSite(t)
	s.login(t)
	for _, origin := range []string{"https://evil.example", ""} {
		resp := s.post(t, "/enroll", nil, origin)
		resp.Body.Close()
		if resp.StatusCode != http.StatusForbidden {
			t.Fatalf("origin %q: %d", origin, resp.StatusCode)
		}
	}
}

var linkRe = regexp.MustCompile(`sparrow://enroll\?[^<"\s]+`)

func TestEnrollRegisterAndRemove(t *testing.T) {
	s := newSite(t)
	s.login(t)
	resp := s.post(t, "/enroll", nil, s.srv.URL)
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	m := linkRe.FindString(string(body))
	if m == "" || !strings.Contains(string(body), `<svg class="qr"`) {
		t.Fatalf("no link or QR in page:\n%s", body)
	}
	link, err := url.Parse(strings.ReplaceAll(m, "&amp;", "&"))
	if err != nil {
		t.Fatal(err)
	}
	q := link.Query()
	if q.Get("u") != s.srv.URL || q.Get("fp") != "PIN" || q.Get("t") == "" {
		t.Fatalf("link fields: %v", q)
	}

	ctx := context.Background()
	reg, err := s.device.Register(ctx, connect.NewRequest(&sparrowv1.RegisterRequest{
		EnrollmentToken: q.Get("t"), Device: &sparrowv1.DeviceInfo{Manufacturer: "Google", Model: "Pixel 8"},
	}))
	if err != nil {
		t.Fatal(err)
	}
	req := connect.NewRequest(&sparrowv1.ReportRequest{State: sparrowv1.StreamState_STREAM_STATE_STANDBY})
	req.Header().Set("Authorization", "Bearer "+reg.Msg.GetDeviceToken())
	if _, err := s.device.Report(ctx, req); err != nil {
		t.Fatal(err)
	}
	_, table := s.get(t, "/devices/table")
	if !strings.Contains(table, "cam-01") || !strings.Contains(table, "Google Pixel 8") || !strings.Contains(table, ">standby<") {
		t.Fatalf("table:\n%s", table)
	}

	resp = s.post(t, "/devices/"+reg.Msg.GetDeviceId()+"/remove", nil, s.srv.URL)
	resp.Body.Close()
	if _, table := s.get(t, "/devices/table"); strings.Contains(table, "cam-01") {
		t.Fatal("removed device still listed")
	}
	if _, err := s.device.Report(ctx, req); connect.CodeOf(err) != connect.CodeUnauthenticated {
		t.Fatalf("removed device can still report: %v", err)
	}
}

func TestRemoveMalformedID(t *testing.T) {
	s := newSite(t)
	s.login(t)
	resp := s.post(t, "/devices/not-a-uuid/remove", nil, s.srv.URL)
	resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("got %d", resp.StatusCode)
	}
}

func TestEnrollLink(t *testing.T) {
	got := EnrollLink("https://10.0.0.5:8443", "tok", "")
	if got != "sparrow://enroll?t=tok&u=https%3A%2F%2F10.0.0.5%3A8443" {
		t.Fatal(got)
	}
}
