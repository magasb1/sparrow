// Package web serves sparrowd's pages: login, devices and enrollment links.
package web

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"embed"
	"encoding/base64"
	"errors"
	"fmt"
	"html/template"
	"io/fs"
	"log/slog"
	"math"
	"net/http"
	"net/url"
	"regexp"
	"sync"
	"time"

	"rsc.io/qr"

	"github.com/magasb1/sparrow/backend/internal/api"
	"github.com/magasb1/sparrow/backend/internal/hub"
	"github.com/magasb1/sparrow/backend/internal/store"
)

//go:embed templates/*.html
var templateFS embed.FS

//go:embed static
var staticFS embed.FS

const (
	sessionCookie = "sparrow_session"
	sessionTTL    = 12 * time.Hour
	// A report older than this means the phone is offline (phones report at least every 30 s).
	onlineWindow = 75 * time.Second
)

type Config struct {
	AdminUser, AdminPassword string
	// PublicURL is the base URL in enrollment links. Empty = the URL the browser used.
	PublicURL string
	// Pin is the server key pin phones check. Empty when serving plain HTTP.
	Pin           string
	SecureCookies bool
	EnrollmentTTL time.Duration
}

type Server struct {
	cfg   Config
	store *store.Store
	hub   *hub.Hub
	log   *slog.Logger
	tmpl  *template.Template
	now   func() time.Time

	mu       sync.Mutex
	sessions map[string]time.Time
}

func New(cfg Config, st *store.Store, h *hub.Hub, log *slog.Logger) *Server {
	s := &Server{cfg: cfg, store: st, hub: h, log: log, now: time.Now, sessions: map[string]time.Time{}}
	s.tmpl = template.Must(template.New("").Funcs(template.FuncMap{
		"ago":  s.ago,
		"kbps": func(v float64) string { return fmt.Sprintf("%.0f kb/s", v) },
		"deref": func(p *float64) float64 {
			if p == nil {
				return math.NaN()
			}
			return *p
		},
		"derefInt": func(p *int) int {
			if p == nil {
				return 0
			}
			return *p
		},
	}).ParseFS(templateFS, "templates/*.html"))
	return s
}

// Routes adds the pages to mux.
func (s *Server) Routes(mux *http.ServeMux) {
	static, _ := fs.Sub(staticFS, "static")
	mux.Handle("GET /static/", http.StripPrefix("/static/", http.FileServerFS(static)))
	mux.HandleFunc("GET /login", s.loginPage)
	mux.HandleFunc("POST /login", s.login)
	mux.HandleFunc("POST /logout", s.authed(s.logout))
	mux.HandleFunc("GET /{$}", s.authed(s.devicesPage))
	mux.HandleFunc("GET /devices/table", s.authed(s.devicesTable))
	mux.HandleFunc("GET /enroll", s.authed(s.enrollPage))
	mux.HandleFunc("POST /enroll", s.authed(s.enroll))
	mux.HandleFunc("POST /devices/{id}/remove", s.authed(s.remove))
}

// --- sessions ---------------------------------------------------------------------------------

func (s *Server) newSession() string {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	id := base64.RawURLEncoding.EncodeToString(b)
	s.mu.Lock()
	defer s.mu.Unlock()
	now := s.now()
	for k, exp := range s.sessions {
		if now.After(exp) {
			delete(s.sessions, k)
		}
	}
	s.sessions[id] = now.Add(sessionTTL)
	return id
}

func (s *Server) validSession(r *http.Request) bool {
	c, err := r.Cookie(sessionCookie)
	if err != nil {
		return false
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	exp, ok := s.sessions[c.Value]
	if ok && s.now().After(exp) {
		delete(s.sessions, c.Value)
		return false
	}
	return ok
}

// authed requires a session, and for POST a same-origin request.
func (s *Server) authed(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !s.validSession(r) {
			if r.Header.Get("HX-Request") != "" {
				w.Header().Set("HX-Redirect", "/login")
				w.WriteHeader(http.StatusUnauthorized)
				return
			}
			http.Redirect(w, r, "/login", http.StatusSeeOther)
			return
		}
		if r.Method == http.MethodPost && !sameOrigin(r) {
			http.Error(w, "cross-origin request refused", http.StatusForbidden)
			return
		}
		w.Header().Set("Cache-Control", "no-store")
		next(w, r)
	}
}

// sameOrigin checks Origin (or Referer) against Host. Together with the SameSite=Strict cookie
// this stops other sites from posting forms here.
func sameOrigin(r *http.Request) bool {
	src := r.Header.Get("Origin")
	if src == "" {
		src = r.Header.Get("Referer")
	}
	if src == "" {
		return false
	}
	u, err := url.Parse(src)
	return err == nil && u.Host == r.Host
}

func equalSecret(a, b string) bool {
	ha, hb := sha256.Sum256([]byte(a)), sha256.Sum256([]byte(b))
	return subtle.ConstantTimeCompare(ha[:], hb[:]) == 1
}

func (s *Server) loginPage(w http.ResponseWriter, r *http.Request) {
	s.render(w, "login.html", map[string]any{})
}

func (s *Server) login(w http.ResponseWriter, r *http.Request) {
	if !sameOrigin(r) {
		http.Error(w, "cross-origin request refused", http.StatusForbidden)
		return
	}
	userOK := equalSecret(r.PostFormValue("user"), s.cfg.AdminUser)
	passOK := equalSecret(r.PostFormValue("password"), s.cfg.AdminPassword)
	if !userOK || !passOK {
		s.log.Warn("failed login", "remote", r.RemoteAddr)
		time.Sleep(500 * time.Millisecond) // slows down guessing
		w.WriteHeader(http.StatusUnauthorized)
		s.render(w, "login.html", map[string]any{"Error": "Wrong user name or password."})
		return
	}
	http.SetCookie(w, &http.Cookie{
		Name: sessionCookie, Value: s.newSession(), Path: "/", HttpOnly: true,
		Secure: s.cfg.SecureCookies, SameSite: http.SameSiteStrictMode, MaxAge: int(sessionTTL.Seconds()),
	})
	http.Redirect(w, r, "/", http.StatusSeeOther)
}

func (s *Server) logout(w http.ResponseWriter, r *http.Request) {
	if c, err := r.Cookie(sessionCookie); err == nil {
		s.mu.Lock()
		delete(s.sessions, c.Value)
		s.mu.Unlock()
	}
	http.SetCookie(w, &http.Cookie{Name: sessionCookie, Value: "", Path: "/", MaxAge: -1, HttpOnly: true,
		Secure: s.cfg.SecureCookies, SameSite: http.SameSiteStrictMode})
	http.Redirect(w, r, "/login", http.StatusSeeOther)
}

// --- devices ----------------------------------------------------------------------------------

type deviceView struct {
	store.DeviceRow
	Connected bool   // has an open Subscribe stream
	Status    string // what the page shows
	Fresh     bool   // latest report is recent
}

func (s *Server) deviceViews(ctx context.Context) ([]deviceView, error) {
	rows, err := s.store.ListDevices(ctx)
	if err != nil {
		return nil, err
	}
	views := make([]deviceView, 0, len(rows))
	for _, row := range rows {
		v := deviceView{DeviceRow: row, Connected: s.hub.Online(row.ID)}
		v.Fresh = row.Latest != nil && s.now().Sub(row.Latest.ReceivedAt) < onlineWindow
		switch {
		case v.Fresh:
			v.Status = row.Latest.State
		case v.Connected:
			v.Status = "connected"
		default:
			v.Status = "offline"
		}
		views = append(views, v)
	}
	return views, nil
}

func (s *Server) devicesPage(w http.ResponseWriter, r *http.Request) {
	views, err := s.deviceViews(r.Context())
	if err != nil {
		s.fail(w, "list devices", err)
		return
	}
	s.render(w, "devices.html", map[string]any{"Devices": views})
}

func (s *Server) devicesTable(w http.ResponseWriter, r *http.Request) {
	views, err := s.deviceViews(r.Context())
	if err != nil {
		s.fail(w, "list devices", err)
		return
	}
	s.render(w, "devices_table", map[string]any{"Devices": views})
}

func (s *Server) remove(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !uuidRe.MatchString(id) {
		http.NotFound(w, r)
		return
	}
	err := s.store.Revoke(r.Context(), id)
	if err != nil && !errors.Is(err, store.ErrNotFound) {
		s.fail(w, "remove device", err)
		return
	}
	s.hub.Kick(id)
	s.log.Info("device removed", "id", id)
	if r.Header.Get("HX-Request") != "" {
		s.devicesTable(w, r)
		return
	}
	http.Redirect(w, r, "/", http.StatusSeeOther)
}

var uuidRe = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

// --- enrollment -------------------------------------------------------------------------------

func (s *Server) enrollPage(w http.ResponseWriter, r *http.Request) {
	s.render(w, "enroll.html", map[string]any{"TTL": hours(s.cfg.EnrollmentTTL)})
}

func (s *Server) enroll(w http.ResponseWriter, r *http.Request) {
	token := api.NewToken()
	expires := s.now().Add(s.cfg.EnrollmentTTL)
	if err := s.store.CreateEnrollment(r.Context(), api.HashToken(token), expires); err != nil {
		s.fail(w, "create enrollment", err)
		return
	}
	link := EnrollLink(s.baseURL(r), token, s.cfg.Pin)
	code, err := qr.Encode(link, qr.M)
	if err != nil {
		s.fail(w, "make QR code", err)
		return
	}
	s.render(w, "enroll.html", map[string]any{
		"TTL":     hours(s.cfg.EnrollmentTTL),
		"Link":    link,
		"QR":      qrSVG(code),
		"Expires": expires,
		"Server":  s.baseURL(r),
	})
}

// qrSVG draws the code as an SVG with a 4-module quiet zone. Vector, so it stays sharp at any
// size; a scaled-down PNG of a long link loses modules and will not scan.
func qrSVG(c *qr.Code) template.HTML {
	var b bytes.Buffer
	n := c.Size + 8
	fmt.Fprintf(&b, `<svg class="qr" viewBox="-4 -4 %d %d" role="img" aria-label="Enrollment QR code" shape-rendering="crispEdges">`+
		`<rect x="-4" y="-4" width="%d" height="%d" fill="#fff"/><path fill="#000" d="`, n, n, n, n)
	for y := 0; y < c.Size; y++ {
		for x := 0; x < c.Size; x++ {
			if c.Black(x, y) {
				fmt.Fprintf(&b, "M%d %dh1v1h-1z", x, y)
			}
		}
	}
	b.WriteString(`"/></svg>`)
	return template.HTML(b.String()) //nolint:gosec // only numbers from the QR bitmap
}

func (s *Server) baseURL(r *http.Request) string {
	if s.cfg.PublicURL != "" {
		return s.cfg.PublicURL
	}
	scheme := "https"
	if r.TLS == nil {
		scheme = "http"
	}
	return scheme + "://" + r.Host
}

// EnrollLink is what the phone scans or pastes:
// sparrow://enroll?u=<server URL>&t=<enrollment token>&fp=<key pin>
func EnrollLink(server, token, pin string) string {
	q := url.Values{}
	q.Set("u", server)
	q.Set("t", token)
	if pin != "" {
		q.Set("fp", pin)
	}
	return "sparrow://enroll?" + q.Encode()
}

// --- helpers ----------------------------------------------------------------------------------

func (s *Server) render(w http.ResponseWriter, name string, data map[string]any) {
	var buf bytes.Buffer
	if err := s.tmpl.ExecuteTemplate(&buf, name, data); err != nil {
		s.fail(w, "render "+name, err)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_, _ = buf.WriteTo(w)
}

func (s *Server) fail(w http.ResponseWriter, what string, err error) {
	s.log.Error(what, "err", err)
	http.Error(w, what+" failed", http.StatusInternalServerError)
}

func hours(d time.Duration) string {
	if h := int(d.Hours()); h == 1 {
		return "1 hour"
	} else if h > 1 {
		return fmt.Sprintf("%d hours", h)
	}
	return fmt.Sprintf("%d minutes", int(d.Minutes()))
}

func (s *Server) ago(t *time.Time) string {
	if t == nil {
		return "never"
	}
	d := s.now().Sub(*t)
	switch {
	case d < 10*time.Second:
		return "just now"
	case d < time.Minute:
		return fmt.Sprintf("%d s ago", int(d.Seconds()))
	case d < time.Hour:
		return fmt.Sprintf("%d min ago", int(d.Minutes()))
	case d < 48*time.Hour:
		return fmt.Sprintf("%d h ago", int(d.Hours()))
	default:
		return t.Local().Format("2006-01-02 15:04")
	}
}
