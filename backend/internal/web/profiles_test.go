package web

import (
	"context"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"testing"

	"connectrpc.com/connect"

	sparrowv1 "github.com/magasb1/sparrow/backend/gen/sparrow/v1"
)

// profileForm is a valid profile form, as the browser posts it.
func profileForm(name, host, bitrate string) url.Values {
	return url.Values{
		"name": {name}, "mode": {"caller"}, "host": {host}, "port": {"9000"}, "latency_ms": {"500"},
		"stream_id": {""}, "key_length": {"0"}, "passphrase": {"correct horse battery"},
		"codec": {"h265"}, "resolution": {"1280x720"}, "fps": {"15"}, "bitrate_kbps": {bitrate},
		"gop_seconds": {"2"}, "rate_control": {"cbr"}, "zoom_ratio": {"1"}, "focus": {"continuous"},
		"orientation": {"auto"}, "stabilization": {"on"}, "audio_kbps": {"96"},
		"meta_rate_hz": {"10"}, "platform": {"Sparrow"}, "sensor": {"EO"}, "ground_elevation_m": {"0"},
		"heading_offset_deg": {"0"}, "carriage": {"sync"},
		"detection_rate_hz": {"4"}, "min_confidence": {"0.5"}, "send_vmti": {"on"},
		"abr": {"on"}, "min_kbps": {"400"}, "max_kbps": {"1000"}, "pts_delay_ms": {"250"},
	}
}

var profileIDRe = regexp.MustCompile(`/profiles/([0-9a-f-]{36})"`)

func (s *site) registerPhone(t *testing.T) (id, token string) {
	t.Helper()
	resp := s.post(t, "/enroll", nil, s.srv.URL)
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	link, _ := url.Parse(strings.ReplaceAll(linkRe.FindString(string(body)), "&amp;", "&"))
	reg, err := s.device.Register(context.Background(), connect.NewRequest(&sparrowv1.RegisterRequest{
		EnrollmentToken: link.Query().Get("t"), Device: &sparrowv1.DeviceInfo{Model: "Pixel"},
	}))
	if err != nil {
		t.Fatal(err)
	}
	return reg.Msg.GetDeviceId(), reg.Msg.GetDeviceToken()
}

func authed[T any](msg *T, token string) *connect.Request[T] {
	req := connect.NewRequest(msg)
	req.Header().Set("Authorization", "Bearer "+token)
	return req
}

func (s *site) config(t *testing.T, token string) *sparrowv1.DeviceConfig {
	t.Helper()
	resp, err := s.device.GetConfig(context.Background(), authed(&sparrowv1.GetConfigRequest{}, token))
	if err != nil {
		t.Fatal(err)
	}
	return resp.Msg.GetConfig()
}

func (s *site) postBody(t *testing.T, path string, form url.Values) (int, string) {
	t.Helper()
	resp := s.post(t, path, form, s.srv.URL)
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, string(b)
}

func TestProfileLibraryPushesToAssignedPhones(t *testing.T) {
	s := newSite(t)
	s.login(t)
	deviceID, token := s.registerPhone(t)

	if code, body := s.postBody(t, "/profiles/new", profileForm("Field", "", "1000")); code != http.StatusUnprocessableEntity ||
		!strings.Contains(body, "Host is required") {
		t.Fatalf("invalid profile accepted: %d", code)
	}
	if code, _ := s.postBody(t, "/profiles/new", profileForm("Field", "srt.example.com", "1000")); code != http.StatusSeeOther {
		t.Fatalf("create: %d", code)
	}
	if code, body := s.postBody(t, "/profiles/new", profileForm("Field", "srt.example.com", "1000")); code != http.StatusUnprocessableEntity ||
		!strings.Contains(body, "already exists") {
		t.Fatalf("duplicate name: %d", code)
	}
	_, list := s.get(t, "/profiles")
	m := profileIDRe.FindStringSubmatch(list)
	if m == nil {
		t.Fatalf("no profile in list:\n%s", list)
	}
	profileID := m[1]

	if c := s.config(t, token); c.GetRevision() != 0 || c.GetProfile() != nil {
		t.Fatalf("config before assigning: %v", c)
	}
	if code, _ := s.postBody(t, "/devices/"+deviceID+"/profile", url.Values{"profile_id": {profileID}}); code != http.StatusSeeOther {
		t.Fatalf("assign: %d", code)
	}
	c := s.config(t, token)
	if c.GetRevision() != 1 || c.GetProfile().GetName() != "Field" || c.GetProfile().GetDestination().GetHost() != "srt.example.com" ||
		c.GetProfile().GetDestination().GetPassphrase() != "correct horse battery" {
		t.Fatalf("assigned config: %v", c)
	}

	// Editing pushes a new revision; a blank passphrase keeps the stored one.
	form := profileForm("Field", "srt.example.com", "2500")
	form.Set("passphrase", "")
	if code, _ := s.postBody(t, "/profiles/"+profileID, form); code != http.StatusSeeOther {
		t.Fatalf("update: %d", code)
	}
	c = s.config(t, token)
	if c.GetRevision() != 2 || c.GetProfile().GetVideo().GetBitrateKbps() != 2500 ||
		c.GetProfile().GetDestination().GetPassphrase() != "correct horse battery" {
		t.Fatalf("updated config: %v", c)
	}
	if _, page := s.get(t, "/devices/"+deviceID); !strings.Contains(page, "waiting for phone") {
		t.Fatal("device page does not show the pending config")
	}

	if code, _ := s.postBody(t, "/profiles/"+profileID+"/delete", nil); code != http.StatusSeeOther {
		t.Fatalf("delete: %d", code)
	}
	if c := s.config(t, token); c.GetRevision() != 3 || c.GetProfile() != nil {
		t.Fatalf("config after delete: %v", c)
	}
}

func TestCommandRoundTrip(t *testing.T) {
	s := newSite(t)
	s.login(t)
	deviceID, token := s.registerPhone(t)
	start := url.Values{"kind": {"start"}}

	if _, body := s.postBody(t, "/devices/"+deviceID+"/command", start); !strings.Contains(body, "not connected") {
		t.Fatalf("offline phone: %s", body)
	}

	// A fake phone: answers every command it is sent.
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	stream, err := s.device.Subscribe(ctx, authed(&sparrowv1.SubscribeRequest{}, token))
	if err != nil {
		t.Fatal(err)
	}
	stream.Receive() // first heartbeat: the hub has the stream
	go func() {
		for stream.Receive() {
			if cmd := stream.Msg().GetCommand(); cmd != nil {
				_, _ = s.device.CommandDone(ctx, authed(&sparrowv1.CommandDoneRequest{
					Id: cmd.GetId(), Ok: cmd.GetStart() != nil, Message: "now live",
				}, token))
			}
		}
	}()

	if _, body := s.postBody(t, "/devices/"+deviceID+"/command", start); !strings.Contains(body, `class="ok"`) ||
		!strings.Contains(body, "Start: now live") {
		t.Fatalf("start: %s", body)
	}
	if _, body := s.postBody(t, "/devices/"+deviceID+"/command", url.Values{"kind": {"stop"}}); !strings.Contains(body, `class="error"`) {
		t.Fatalf("refused stop shown as ok: %s", body)
	}
	if _, body := s.postBody(t, "/devices/"+deviceID+"/command", url.Values{"kind": {"zoom"}, "zoom": {"abc"}}); !strings.Contains(body, "Zoom must be") {
		t.Fatalf("bad zoom: %s", body)
	}
}
