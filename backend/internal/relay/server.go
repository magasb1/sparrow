package relay

import (
	"context"
	"errors"
	"log/slog"
	"net"
	"sync"
	"time"

	srt "github.com/datarhei/gosrt"
)

// PayloadSize is the SRT payload phones send: seven 188-byte TS packets.
const PayloadSize = 1316

// readerQueue is about 2 s at 4 Mbit/s. A reader further behind than that loses payloads.
const readerQueue = 800

// maxPending caps handshakes being checked at once, so a flood of connection requests cannot
// start unbounded goroutines and database lookups.
const maxPending = 64

// Config for the SRT listeners.
type Config struct {
	PublishAddr       string // phones, e.g. ":8890"
	ReadAddr          string // readers, e.g. ":8891"
	PublishPassphrase string // empty = unencrypted
	ReadPassphrase    string // empty = unencrypted
	Latency           time.Duration
	// Authorize checks a publisher's alias and key. Required.
	Authorize func(ctx context.Context, alias, key string) bool
	Logger    *slog.Logger
}

// Server runs the publish and read listeners on one Hub.
type Server struct {
	cfg Config
	Hub *Hub
	log *slog.Logger

	mu        sync.Mutex
	listeners []srt.Listener
	ready     chan struct{}
	pending   chan struct{}
	conns     sync.WaitGroup
}

func NewServer(cfg Config) *Server {
	if cfg.Logger == nil {
		cfg.Logger = slog.Default()
	}
	if cfg.Latency == 0 {
		cfg.Latency = 500 * time.Millisecond
	}
	return &Server{cfg: cfg, Hub: NewHub(), log: cfg.Logger, ready: make(chan struct{}), pending: make(chan struct{}, maxPending)}
}

func (s *Server) srtConfig() srt.Config {
	c := srt.DefaultConfig()
	c.PayloadSize = PayloadSize
	c.Latency = s.cfg.Latency
	c.ReceiverLatency = s.cfg.Latency
	c.PeerLatency = s.cfg.Latency
	return c
}

// Run listens until ctx ends. Addrs are available once Ready is closed.
func (s *Server) Run(ctx context.Context) error {
	if s.cfg.Authorize == nil {
		return errors.New("relay: Authorize is required")
	}
	pub, err := srt.Listen("srt", s.cfg.PublishAddr, s.srtConfig())
	if err != nil {
		return err
	}
	rd, err := srt.Listen("srt", s.cfg.ReadAddr, s.srtConfig())
	if err != nil {
		pub.Close()
		return err
	}
	s.mu.Lock()
	s.listeners = []srt.Listener{pub, rd}
	s.mu.Unlock()
	close(s.ready)

	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); s.acceptLoop(ctx, pub, true) }()
	go func() { defer wg.Done(); s.acceptLoop(ctx, rd, false) }()
	<-ctx.Done()
	pub.Close()
	rd.Close()
	wg.Wait()
	s.conns.Wait()
	return nil
}

// Ready is closed when both listeners are bound.
func (s *Server) Ready() <-chan struct{} { return s.ready }

// Addrs returns the bound publish and read addresses (useful with port 0 in tests), or nil
// before Ready.
func (s *Server) Addrs() (publish, read net.Addr) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(s.listeners) < 2 {
		return nil, nil
	}
	return s.listeners[0].Addr(), s.listeners[1].Addr()
}

func (s *Server) acceptLoop(ctx context.Context, ln srt.Listener, publish bool) {
	for {
		req, err := ln.Accept2()
		if err != nil {
			if !errors.Is(err, srt.ErrListenerClosed) && ctx.Err() == nil {
				s.log.Warn("srt accept failed", "err", err)
			}
			return
		}
		select {
		case s.pending <- struct{}{}:
		default:
			s.log.Warn("srt rejected: too many pending handshakes", "remote", req.RemoteAddr().String())
			req.Reject(srt.REJ_BACKLOG)
			continue
		}
		// Authorization may hit the database; keep it off the accept loop.
		s.conns.Add(1)
		go func() {
			defer s.conns.Done()
			s.handle(ctx, req, publish)
		}()
	}
}

func (s *Server) handle(ctx context.Context, req srt.ConnRequest, publish bool) {
	// The slot covers the handshake checks only; it is freed before the connection is served.
	released := false
	release := func() {
		if !released {
			released = true
			<-s.pending
		}
	}
	defer release()
	remote := req.RemoteAddr().String()
	id, err := ParseStreamID(req.StreamId())
	if err != nil {
		s.log.Info("srt rejected", "remote", remote, "reason", err.Error())
		req.Reject(srt.REJX_BAD_REQUEST)
		return
	}
	pass := s.cfg.ReadPassphrase
	wantMode := "request"
	if publish {
		pass, wantMode = s.cfg.PublishPassphrase, "publish"
	}
	if id.Mode != wantMode {
		s.log.Info("srt rejected", "remote", remote, "reason", "wrong mode for port", "mode", id.Mode)
		req.Reject(srt.REJX_BAD_MODE)
		return
	}
	// The cheap passphrase checks come before Authorize, which may hit the database.
	if pass != "" {
		if !req.IsEncrypted() {
			req.Reject(srt.REJ_UNSECURE)
			return
		}
		if err := req.SetPassphrase(pass); err != nil {
			req.Reject(srt.REJ_BADSECRET)
			return
		}
	} else if req.IsEncrypted() {
		req.Reject(srt.REJ_UNSECURE)
		return
	}
	if publish {
		// Shorter than libsrt's 3 s connect timeout, so the phone gets a clear rejection.
		actx, cancel := context.WithTimeout(ctx, 2*time.Second)
		ok := s.cfg.Authorize(actx, id.Resource, id.Key)
		cancel()
		if !ok {
			s.log.Info("srt rejected", "remote", remote, "alias", id.Resource, "reason", "unauthorized")
			req.Reject(srt.REJX_UNAUTHORIZED)
			return
		}
	}
	conn, err := req.Accept()
	if err != nil {
		s.log.Info("srt accept failed", "remote", remote, "err", err)
		return
	}
	release()
	if publish {
		s.servePublisher(ctx, conn, id.Resource)
	} else {
		s.serveReader(ctx, conn, id.Resource)
	}
}

func (s *Server) servePublisher(ctx context.Context, conn srt.Conn, alias string) {
	s.log.Info("publisher connected", "alias", alias, "remote", conn.RemoteAddr().String())
	p := s.Hub.Publish(alias, func() { conn.Close() })
	defer func() {
		p.Close()
		conn.Close()
		s.log.Info("publisher disconnected", "alias", alias)
	}()
	stop := context.AfterFunc(ctx, func() { conn.Close() })
	defer stop()
	for {
		pkt, err := conn.ReadPacket()
		if err != nil {
			return
		}
		p.Write(pkt.Data())
		pkt.Decommission()
	}
}

func (s *Server) serveReader(ctx context.Context, conn srt.Conn, alias string) {
	s.log.Info("reader connected", "alias", alias, "remote", conn.RemoteAddr().String())
	sub := s.Hub.Subscribe(alias, readerQueue)
	defer func() {
		sub.Close()
		conn.Close()
		s.log.Info("reader disconnected", "alias", alias, "dropped", sub.Dropped.Load())
	}()
	// Readers send no data; Read returns when the reader goes away.
	go func() {
		buf := make([]byte, 1500)
		for {
			if _, err := conn.Read(buf); err != nil {
				sub.Close()
				return
			}
		}
	}()
	for {
		select {
		case <-ctx.Done():
			return
		case <-sub.Done():
			return
		case b := <-sub.C:
			// gosrt returns an error when its send queue is full; a reader that far behind is dropped.
			if _, err := conn.Write(b); err != nil {
				return
			}
		}
	}
}
