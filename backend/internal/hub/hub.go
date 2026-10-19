// Package hub tracks each phone's open Subscribe stream and delivers events to it.
package hub

import (
	"context"
	"errors"
	"sync"

	sparrowv1 "github.com/magasb1/sparrow/backend/gen/sparrow/v1"
)

var (
	ErrOffline = errors.New("device is not connected")
	ErrTimeout = errors.New("device did not answer in time")
)

// queueLen is generous: events are small and rare. A phone that lets it fill up is cut off and
// resynchronises when it reconnects (Report tells it the latest config revision).
const queueLen = 32

// Result is a phone's answer to a command.
type Result struct {
	OK      bool
	Message string
}

// Session is one open Subscribe stream.
type Session struct {
	Events chan *sparrowv1.SubscribeResponse
	closed chan struct{}
	once   sync.Once
}

// Closed is closed when the hub ends the session: replaced by a newer stream, kicked on revoke,
// or cut off for not reading.
func (s *Session) Closed() <-chan struct{} { return s.closed }

func (s *Session) close() { s.once.Do(func() { close(s.closed) }) }

type Hub struct {
	mu       sync.Mutex
	sessions map[string]*Session
	pending  map[string]pendingCmd
}

type pendingCmd struct {
	deviceID string
	ch       chan Result
}

func New() *Hub {
	return &Hub{sessions: map[string]*Session{}, pending: map[string]pendingCmd{}}
}

// Attach registers a new stream for deviceID, replacing (and closing) an older one. Call the
// returned function when the stream ends.
func (h *Hub) Attach(deviceID string) (*Session, func()) {
	s := &Session{Events: make(chan *sparrowv1.SubscribeResponse, queueLen), closed: make(chan struct{})}
	h.mu.Lock()
	old := h.sessions[deviceID]
	h.sessions[deviceID] = s
	h.mu.Unlock()
	if old != nil {
		old.close()
	}
	return s, func() {
		h.mu.Lock()
		if h.sessions[deviceID] == s {
			delete(h.sessions, deviceID)
		}
		h.mu.Unlock()
		s.close()
	}
}

// Online reports whether the device has an open stream.
func (h *Hub) Online(deviceID string) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.sessions[deviceID] != nil
}

// Notify queues an event for the device. It returns ErrOffline when the device has no stream.
func (h *Hub) Notify(deviceID string, ev *sparrowv1.SubscribeResponse) error {
	h.mu.Lock()
	s := h.sessions[deviceID]
	h.mu.Unlock()
	if s == nil {
		return ErrOffline
	}
	select {
	case s.Events <- ev:
		return nil
	case <-s.closed:
		return ErrOffline
	default:
		// The phone is not reading; drop the stream so it reconnects and resyncs.
		s.close()
		return ErrOffline
	}
}

// Kick closes the device's stream, if any.
func (h *Hub) Kick(deviceID string) {
	h.mu.Lock()
	s := h.sessions[deviceID]
	delete(h.sessions, deviceID)
	h.mu.Unlock()
	if s != nil {
		s.close()
	}
}

// Send delivers a command and waits for the phone's CommandDone, or ctx's deadline.
// Commands are never queued for later: an offline phone fails at once.
func (h *Hub) Send(ctx context.Context, deviceID string, cmd *sparrowv1.Command) (Result, error) {
	ch := make(chan Result, 1)
	h.mu.Lock()
	h.pending[cmd.GetId()] = pendingCmd{deviceID: deviceID, ch: ch}
	h.mu.Unlock()
	defer func() {
		h.mu.Lock()
		delete(h.pending, cmd.GetId())
		h.mu.Unlock()
	}()
	if err := h.Notify(deviceID, &sparrowv1.SubscribeResponse{
		Event: &sparrowv1.SubscribeResponse_Command{Command: cmd},
	}); err != nil {
		return Result{}, err
	}
	select {
	case r := <-ch:
		return r, nil
	case <-ctx.Done():
		return Result{}, ErrTimeout
	}
}

// Done hands a phone's answer to the waiting Send. Answers for unknown commands, or from a
// different device than the command went to, are ignored.
func (h *Hub) Done(deviceID, id string, r Result) {
	h.mu.Lock()
	p, ok := h.pending[id]
	h.mu.Unlock()
	if ok && p.deviceID == deviceID {
		select {
		case p.ch <- r:
		default:
		}
	}
}
