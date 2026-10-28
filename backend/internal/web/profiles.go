package web

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"

	"google.golang.org/protobuf/proto"

	sparrowv1 "github.com/magasb1/sparrow/backend/gen/sparrow/v1"
	"github.com/magasb1/sparrow/backend/internal/api"
	"github.com/magasb1/sparrow/backend/internal/hub"
	"github.com/magasb1/sparrow/backend/internal/store"
)

// commandTimeout is how long a page waits for the phone's CommandDone.
const commandTimeout = 10 * time.Second

// Resolutions offered on the profile form, as on the phone.
var resolutions = []string{"640x360", "1280x720", "1920x1080", "3840x2160"}

// --- profile library --------------------------------------------------------------------------

func (s *Server) profilesPage(w http.ResponseWriter, r *http.Request) {
	list, err := s.store.ListProfiles(r.Context())
	if err != nil {
		s.fail(w, "list profiles", err)
		return
	}
	s.render(w, "profiles.html", map[string]any{"Profiles": list})
}

func (s *Server) newProfilePage(w http.ResponseWriter, r *http.Request) {
	s.renderProfile(w, http.StatusOK, "", defaultProfile(), nil)
}

func (s *Server) editProfilePage(w http.ResponseWriter, r *http.Request) {
	row, p, ok := s.loadProfile(w, r)
	if !ok {
		return
	}
	s.renderProfile(w, http.StatusOK, row.ID, p, nil)
}

func (s *Server) createProfile(w http.ResponseWriter, r *http.Request) {
	p := defaultProfile()
	if problems := applyForm(p, r); len(problems) > 0 {
		s.renderProfile(w, http.StatusUnprocessableEntity, "", p, problems)
		return
	}
	id, err := s.store.CreateProfile(r.Context(), p.GetName(), func(id string) ([]byte, error) {
		p.Id = id
		return proto.Marshal(p)
	})
	if errors.Is(err, store.ErrDuplicateName) {
		s.renderProfile(w, http.StatusUnprocessableEntity, "", p, []string{err.Error()})
		return
	}
	if err != nil {
		s.fail(w, "create profile", err)
		return
	}
	s.log.Info("profile created", "id", id, "name", p.GetName())
	http.Redirect(w, r, "/profiles", http.StatusSeeOther)
}

func (s *Server) updateProfile(w http.ResponseWriter, r *http.Request) {
	row, p, ok := s.loadProfile(w, r)
	if !ok {
		return
	}
	if problems := applyForm(p, r); len(problems) > 0 {
		s.renderProfile(w, http.StatusUnprocessableEntity, row.ID, p, problems)
		return
	}
	b, err := proto.Marshal(p)
	if err != nil {
		s.fail(w, "encode profile", err)
		return
	}
	changes, err := s.store.UpdateProfile(r.Context(), row.ID, p.GetName(), b)
	if errors.Is(err, store.ErrDuplicateName) {
		s.renderProfile(w, http.StatusUnprocessableEntity, row.ID, p, []string{err.Error()})
		return
	}
	if err != nil {
		s.fail(w, "update profile", err)
		return
	}
	s.push(changes...)
	s.log.Info("profile updated", "id", row.ID, "name", p.GetName(), "devices", len(changes))
	http.Redirect(w, r, "/profiles", http.StatusSeeOther)
}

func (s *Server) deleteProfile(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !uuidRe.MatchString(id) {
		http.NotFound(w, r)
		return
	}
	changes, err := s.store.DeleteProfile(r.Context(), id)
	if err != nil {
		s.fail(w, "delete profile", err)
		return
	}
	s.push(changes...)
	s.log.Info("profile deleted", "id", id, "devices", len(changes))
	http.Redirect(w, r, "/profiles", http.StatusSeeOther)
}

func (s *Server) loadProfile(w http.ResponseWriter, r *http.Request) (store.ProfileRow, *sparrowv1.Profile, bool) {
	id := r.PathValue("id")
	if !uuidRe.MatchString(id) {
		http.NotFound(w, r)
		return store.ProfileRow{}, nil, false
	}
	row, err := s.store.Profile(r.Context(), id)
	if errors.Is(err, store.ErrNotFound) {
		http.NotFound(w, r)
		return row, nil, false
	}
	if err != nil {
		s.fail(w, "read profile", err)
		return row, nil, false
	}
	p := &sparrowv1.Profile{}
	if err := proto.Unmarshal(row.Profile, p); err != nil {
		s.fail(w, "decode profile", err)
		return row, nil, false
	}
	return row, p, true
}

func (s *Server) renderProfile(w http.ResponseWriter, status int, id string, p *sparrowv1.Profile, problems []string) {
	if status != http.StatusOK {
		w.WriteHeader(status)
	}
	v := p.GetVideo()
	s.render(w, "profile.html", map[string]any{
		"ID": id, "P": p, "Problems": problems, "Resolutions": resolutions,
		"Resolution": fmt.Sprintf("%dx%d", v.GetWidth(), v.GetHeight()),
		"HasPassphrase": p.GetDestination().GetPassphrase() != "",
	})
}

// push tells connected phones about new config revisions. Offline phones learn it from their next
// report or subscription.
func (s *Server) push(changes ...store.ConfigChange) {
	for _, c := range changes {
		_ = s.hub.Notify(c.DeviceID, &sparrowv1.SubscribeResponse{
			Event: &sparrowv1.SubscribeResponse_ConfigChanged{ConfigChanged: uint64(c.Revision)},
		})
	}
}

// defaultProfile mirrors app.sparrow.core.config.Profile's defaults. Enabled KLV tags and
// detection classes stay empty: the phone reads empty as its own defaults.
func defaultProfile() *sparrowv1.Profile {
	return &sparrowv1.Profile{
		Name: "New profile",
		Destination: &sparrowv1.Destination{
			Mode: sparrowv1.SrtMode_SRT_MODE_CALLER, Port: 9000, LatencyMs: 500,
		},
		Video: &sparrowv1.VideoSettings{
			Codec: sparrowv1.VideoCodec_VIDEO_CODEC_H265, Width: 1280, Height: 720, Fps: 15, BitrateKbps: 1000,
			GopSeconds: 2, RateControl: sparrowv1.RateControl_RATE_CONTROL_CBR, ZoomRatio: 1,
			Focus: sparrowv1.FocusMode_FOCUS_MODE_CONTINUOUS, Stabilization: true,
		},
		Audio: &sparrowv1.AudioSettings{BitrateKbps: 96, SampleRate: 48000, Channels: 1},
		Metadata: &sparrowv1.MetadataSettings{
			RateHz: 10, PlatformDesignation: "Sparrow", ImageSourceSensor: "EO", LsVersion: 19,
			Carriage: sparrowv1.KlvCarriage_KLV_CARRIAGE_SYNC,
		},
		Detection: &sparrowv1.DetectionSettings{RateHz: 4, MinConfidence: 0.5, SendVmti: true},
		Advanced: &sparrowv1.AdvancedSettings{
			AdaptiveBitrate: true, MinBitrateKbps: 400, MaxBitrateKbps: 1000, PtsDelayMs: 250,
		},
	}
}

// applyForm copies the profile form into p and returns what is wrong with it, using the phone's
// own limits (Profile.validate). Fields the form does not show are left as they are.
func applyForm(p *sparrowv1.Profile, r *http.Request) []string {
	var problems []string
	str := func(key string, max int, label string) string {
		v := strings.TrimSpace(r.PostFormValue(key))
		if len(v) > max {
			problems = append(problems, fmt.Sprintf("%s is limited to %d bytes", label, max))
		}
		return v
	}
	num := func(key string, lo, hi float64, label string) float64 {
		v, err := strconv.ParseFloat(strings.TrimSpace(r.PostFormValue(key)), 64)
		if err != nil || v < lo || v > hi {
			problems = append(problems, fmt.Sprintf("%s must be %g–%g", label, lo, hi))
		}
		return v
	}
	on := func(key string) bool { return r.PostFormValue(key) != "" }

	p.Name = str("name", 100, "Name")
	if p.Name == "" {
		problems = append(problems, "Name is required")
	}

	d := p.Destination
	if d == nil {
		d = &sparrowv1.Destination{}
		p.Destination = d
	}
	d.Mode = sparrowv1.SrtMode_SRT_MODE_CALLER
	if r.PostFormValue("mode") == "listener" {
		d.Mode = sparrowv1.SrtMode_SRT_MODE_LISTENER
	}
	d.Host = str("host", 253, "Host")
	if d.Mode == sparrowv1.SrtMode_SRT_MODE_CALLER && d.Host == "" {
		problems = append(problems, "Host is required in caller mode")
	}
	d.Port = uint32(num("port", 1, 65535, "Port"))
	d.StreamId = str("stream_id", 512, "Stream ID")
	d.LatencyMs = uint32(num("latency_ms", 20, 8000, "SRT latency (ms)"))
	switch kl := num("key_length", 0, 32, "AES key length"); kl {
	case 0, 16, 24, 32:
		d.KeyLength = uint32(kl)
	default:
		problems = append(problems, "AES key length must be 16, 24 or 32 bytes")
	}
	if on("clear_passphrase") {
		d.Passphrase = ""
	} else if pass := r.PostFormValue("passphrase"); pass != "" {
		if len(pass) < 10 || len(pass) > 79 {
			problems = append(problems, "Passphrase must be 10–79 characters")
		}
		d.Passphrase = pass
	}

	v := p.Video
	if v == nil {
		v = &sparrowv1.VideoSettings{}
		p.Video = v
	}
	v.Codec = sparrowv1.VideoCodec_VIDEO_CODEC_H265
	if r.PostFormValue("codec") == "h264" {
		v.Codec = sparrowv1.VideoCodec_VIDEO_CODEC_H264
	}
	var w, h uint32
	if _, err := fmt.Sscanf(r.PostFormValue("resolution"), "%dx%d", &w, &h); err != nil || w < 160 || w > 3840 || h < 120 || h > 2160 {
		problems = append(problems, "Choose a resolution")
	} else {
		v.Width, v.Height = w, h
	}
	v.Fps = uint32(num("fps", 1, 60, "Frame rate"))
	v.BitrateKbps = uint32(num("bitrate_kbps", 100, 50000, "Video bitrate (kbit/s)"))
	v.GopSeconds = num("gop_seconds", 0.1, 10, "GOP (s)")
	v.RateControl = sparrowv1.RateControl_RATE_CONTROL_CBR
	if r.PostFormValue("rate_control") == "vbr" {
		v.RateControl = sparrowv1.RateControl_RATE_CONTROL_VBR
	}
	v.ZoomRatio = num("zoom_ratio", 0.1, 100, "Zoom")
	v.Focus = sparrowv1.FocusMode_FOCUS_MODE_CONTINUOUS
	if r.PostFormValue("focus") == "infinity" {
		v.Focus = sparrowv1.FocusMode_FOCUS_MODE_INFINITY
	}
	v.Stabilization = on("stabilization")
	switch r.PostFormValue("orientation") {
	case "portrait":
		v.Orientation = sparrowv1.OrientationLock_ORIENTATION_LOCK_PORTRAIT
	case "landscape":
		v.Orientation = sparrowv1.OrientationLock_ORIENTATION_LOCK_LANDSCAPE
	default:
		v.Orientation = sparrowv1.OrientationLock_ORIENTATION_LOCK_UNSPECIFIED
	}

	a := p.Audio
	if a == nil {
		a = &sparrowv1.AudioSettings{SampleRate: 48000, Channels: 1}
		p.Audio = a
	}
	a.Enabled = on("audio")
	a.BitrateKbps = uint32(num("audio_kbps", 16, 320, "Audio bitrate (kbit/s)"))

	m := p.Metadata
	if m == nil {
		m = &sparrowv1.MetadataSettings{LsVersion: 19}
		p.Metadata = m
	}
	m.RateHz = num("meta_rate_hz", 1, float64(max(v.Fps, 1)), "Metadata rate (Hz, up to the frame rate)")
	m.MissionId = str("mission_id", 127, "Mission ID")
	m.PlatformDesignation = str("platform", 127, "Platform designation")
	m.ImageSourceSensor = str("sensor", 127, "Image source sensor")
	m.CallSign = str("call_sign", 127, "Call sign")
	m.GroundElevationM = num("ground_elevation_m", -500, 9000, "Ground elevation (m)")
	m.HeadingOffsetDeg = num("heading_offset_deg", -180, 180, "Heading offset (°)")
	m.Carriage = sparrowv1.KlvCarriage_KLV_CARRIAGE_SYNC
	if r.PostFormValue("carriage") == "async" {
		m.Carriage = sparrowv1.KlvCarriage_KLV_CARRIAGE_ASYNC
	}

	det := p.Detection
	if det == nil {
		det = &sparrowv1.DetectionSettings{}
		p.Detection = det
	}
	det.Enabled = on("detection")
	det.RateHz = num("detection_rate_hz", 0.5, 15, "Detection rate (Hz)")
	det.MinConfidence = num("min_confidence", 0.05, 0.99, "Detection confidence")
	det.SendVmti = on("send_vmti")

	adv := p.Advanced
	if adv == nil {
		adv = &sparrowv1.AdvancedSettings{}
		p.Advanced = adv
	}
	adv.AdaptiveBitrate = on("abr")
	adv.MinBitrateKbps = uint32(num("min_kbps", 100, 50000, "ABR minimum (kbit/s)"))
	adv.MaxBitrateKbps = uint32(num("max_kbps", 100, 50000, "ABR maximum (kbit/s)"))
	if adv.AdaptiveBitrate && adv.MinBitrateKbps > adv.MaxBitrateKbps {
		problems = append(problems, "ABR minimum exceeds maximum")
	}
	adv.PtsDelayMs = uint32(num("pts_delay_ms", 0, 2000, "PTS delay (ms)"))
	adv.RecordLocally = on("record")
	return problems
}

// --- one device -------------------------------------------------------------------------------

func (s *Server) deviceView(ctx context.Context, id string) (deviceView, bool, error) {
	views, err := s.deviceViews(ctx)
	for _, v := range views {
		if v.ID == id {
			return v, true, err
		}
	}
	return deviceView{}, false, err
}

func (s *Server) devicePage(w http.ResponseWriter, r *http.Request) {
	v, ok := s.findDevice(w, r)
	if !ok {
		return
	}
	profiles, err := s.store.ListProfiles(r.Context())
	if err != nil {
		s.fail(w, "list profiles", err)
		return
	}
	s.render(w, "device.html", map[string]any{"D": v, "Profiles": profiles})
}

func (s *Server) deviceStatus(w http.ResponseWriter, r *http.Request) {
	if v, ok := s.findDevice(w, r); ok {
		s.render(w, "device_status", v)
	}
}

func (s *Server) findDevice(w http.ResponseWriter, r *http.Request) (deviceView, bool) {
	id := r.PathValue("id")
	if !uuidRe.MatchString(id) {
		http.NotFound(w, r)
		return deviceView{}, false
	}
	v, ok, err := s.deviceView(r.Context(), id)
	if err != nil {
		s.fail(w, "read device", err)
		return v, false
	}
	if !ok {
		http.NotFound(w, r)
	}
	return v, ok
}

func (s *Server) assignProfile(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	pid := r.PostFormValue("profile_id")
	if !uuidRe.MatchString(id) || pid != "" && !uuidRe.MatchString(pid) {
		http.NotFound(w, r)
		return
	}
	rev, err := s.store.AssignProfile(r.Context(), id, pid)
	if errors.Is(err, store.ErrNotFound) {
		http.NotFound(w, r)
		return
	}
	if err != nil {
		s.fail(w, "assign profile", err)
		return
	}
	s.push(store.ConfigChange{DeviceID: id, Revision: rev})
	s.log.Info("profile assigned", "device", id, "profile", pid)
	http.Redirect(w, r, "/devices/"+id, http.StatusSeeOther)
}

// command sends Start, Stop, Restart or Zoom and waits for the phone's answer.
func (s *Server) command(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !uuidRe.MatchString(id) {
		http.NotFound(w, r)
		return
	}
	if _, err := s.store.Device(r.Context(), id); err != nil {
		http.NotFound(w, r)
		return
	}
	kind := r.PostFormValue("kind")
	cmd := &sparrowv1.Command{Id: api.NewToken()}
	var arg *float64
	switch kind {
	case "start":
		cmd.Kind = &sparrowv1.Command_Start{Start: &sparrowv1.StartCommand{}}
	case "stop":
		cmd.Kind = &sparrowv1.Command_Stop{Stop: &sparrowv1.StopCommand{}}
	case "restart":
		cmd.Kind = &sparrowv1.Command_Restart{Restart: &sparrowv1.RestartCommand{}}
	case "zoom":
		z, err := strconv.ParseFloat(r.PostFormValue("zoom"), 64)
		if err != nil || z < 0.1 || z > 100 {
			s.render(w, "command_result", map[string]any{"Message": "Zoom must be a number from 0.1 to 100"})
			return
		}
		arg = &z
		cmd.Kind = &sparrowv1.Command_Zoom{Zoom: &sparrowv1.ZoomCommand{Ratio: z}}
	default:
		http.Error(w, "unknown command", http.StatusBadRequest)
		return
	}
	if err := s.store.InsertCommand(r.Context(), cmd.GetId(), id, kind, arg); err != nil {
		s.fail(w, "store command", err)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), commandTimeout)
	defer cancel()
	res, err := s.hub.Send(ctx, id, cmd)
	label := strings.ToUpper(kind[:1]) + kind[1:]
	msg := label + ": " + res.Message
	switch {
	case errors.Is(err, hub.ErrOffline):
		msg = label + " failed: the phone is not connected"
	case errors.Is(err, hub.ErrTimeout):
		msg = fmt.Sprintf("%s: no answer from the phone within %d s", label, int(commandTimeout.Seconds()))
	case err != nil:
		msg = label + " failed: " + err.Error()
	case res.Message == "":
		msg = label + ": done"
	}
	s.log.Info("command", "device", id, "kind", kind, "ok", err == nil && res.OK, "message", msg)
	s.render(w, "command_result", map[string]any{"OK": err == nil && res.OK, "Message": msg})
}
