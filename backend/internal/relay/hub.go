// Package relay forwards each phone's SRT payloads to readers unchanged. The TS packets a reader
// receives are byte for byte the ones the phone sent, so KLV stays intact.
package relay

import (
	"sync"
	"sync/atomic"
)

// Hub holds one stream per alias. A stream exists while it has a publisher or a subscriber, so a
// reader can connect before the phone and keeps its connection when the phone reconnects.
type Hub struct {
	mu      sync.Mutex
	streams map[string]*stream
}

type stream struct {
	publisher *Publication
	subs      map[*Subscriber]struct{}
}

// Publication is the single publisher of an alias.
type Publication struct {
	hub    *Hub
	alias  string
	onKick func()
	closed atomic.Bool
}

// Subscriber receives every payload of one alias on C. Payloads are shared between subscribers
// and must not be modified.
type Subscriber struct {
	C       chan []byte
	Dropped atomic.Uint64
	hub     *Hub
	alias   string
	done    chan struct{}
	once    sync.Once
}

func NewHub() *Hub { return &Hub{streams: map[string]*stream{}} }

func (h *Hub) get(alias string) *stream {
	s := h.streams[alias]
	if s == nil {
		s = &stream{subs: map[*Subscriber]struct{}{}}
		h.streams[alias] = s
	}
	return s
}

func (h *Hub) prune(alias string, s *stream) {
	if s.publisher == nil && len(s.subs) == 0 {
		delete(h.streams, alias)
	}
}

// Publish makes a new publisher for alias. An existing publisher is replaced and onKick of the
// old one is called (outside the lock), so a phone that reconnects before its old connection
// times out takes over at once.
func (h *Hub) Publish(alias string, onKick func()) *Publication {
	p := &Publication{hub: h, alias: alias, onKick: onKick}
	h.mu.Lock()
	s := h.get(alias)
	old := s.publisher
	s.publisher = p
	h.mu.Unlock()
	if old != nil {
		old.closed.Store(true)
		if old.onKick != nil {
			old.onKick()
		}
	}
	return p
}

// Write copies payload once and offers it to every subscriber. A subscriber whose queue is full
// misses this payload; it never slows the publisher or the other subscribers.
func (p *Publication) Write(payload []byte) {
	if p.closed.Load() {
		return
	}
	b := append([]byte(nil), payload...)
	p.hub.mu.Lock()
	defer p.hub.mu.Unlock()
	s := p.hub.streams[p.alias]
	if s == nil || s.publisher != p {
		return
	}
	for sub := range s.subs {
		select {
		case sub.C <- b:
		default:
			sub.Dropped.Add(1)
		}
	}
}

// Close ends this publication. Subscribers stay attached and wait for the next publisher.
func (p *Publication) Close() {
	p.closed.Store(true)
	p.hub.mu.Lock()
	defer p.hub.mu.Unlock()
	if s := p.hub.streams[p.alias]; s != nil && s.publisher == p {
		s.publisher = nil
		p.hub.prune(p.alias, s)
	}
}

// Subscribe attaches a reader to alias with a queue of queueLen payloads.
func (h *Hub) Subscribe(alias string, queueLen int) *Subscriber {
	sub := &Subscriber{C: make(chan []byte, queueLen), hub: h, alias: alias, done: make(chan struct{})}
	h.mu.Lock()
	h.get(alias).subs[sub] = struct{}{}
	h.mu.Unlock()
	return sub
}

// Done is closed when the subscriber is closed.
func (s *Subscriber) Done() <-chan struct{} { return s.done }

// Close detaches the subscriber. Safe to call more than once.
func (s *Subscriber) Close() {
	s.once.Do(func() {
		s.hub.mu.Lock()
		if st := s.hub.streams[s.alias]; st != nil {
			delete(st.subs, s)
			s.hub.prune(s.alias, st)
		}
		s.hub.mu.Unlock()
		close(s.done)
	})
}

// Kick disconnects the publisher of alias, if any, e.g. when its phone is revoked.
// Subscribers stay attached.
func (h *Hub) Kick(alias string) {
	h.mu.Lock()
	var p *Publication
	if s := h.streams[alias]; s != nil {
		p = s.publisher
		s.publisher = nil
		h.prune(alias, s)
	}
	h.mu.Unlock()
	if p != nil {
		p.closed.Store(true)
		if p.onKick != nil {
			p.onKick()
		}
	}
}

// Live reports whether alias has a publisher and how many subscribers it has.
func (h *Hub) Live(alias string) (publishing bool, subscribers int) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if s := h.streams[alias]; s != nil {
		return s.publisher != nil, len(s.subs)
	}
	return false, 0
}
