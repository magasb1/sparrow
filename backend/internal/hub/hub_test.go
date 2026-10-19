package hub

import (
	"context"
	"errors"
	"testing"
	"time"

	sparrowv1 "github.com/magasb1/sparrow/backend/gen/sparrow/v1"
)

func configChanged(rev uint64) *sparrowv1.SubscribeResponse {
	return &sparrowv1.SubscribeResponse{Event: &sparrowv1.SubscribeResponse_ConfigChanged{ConfigChanged: rev}}
}

func TestNotifyAndReplace(t *testing.T) {
	h := New()
	if err := h.Notify("d1", configChanged(1)); !errors.Is(err, ErrOffline) {
		t.Fatalf("offline notify: %v", err)
	}
	s1, done1 := h.Attach("d1")
	if err := h.Notify("d1", configChanged(2)); err != nil {
		t.Fatal(err)
	}
	if ev := <-s1.Events; ev.GetConfigChanged() != 2 {
		t.Fatalf("got %v", ev)
	}
	// A second stream replaces the first and closes it.
	s2, done2 := h.Attach("d1")
	select {
	case <-s1.Closed():
	default:
		t.Fatal("old session not closed")
	}
	// The old stream ending must not remove the new one.
	done1()
	if !h.Online("d1") {
		t.Fatal("new session lost")
	}
	done2()
	if h.Online("d1") {
		t.Fatal("still online after done")
	}
	<-s2.Closed()
}

func TestFullQueueCutsOff(t *testing.T) {
	h := New()
	s, done := h.Attach("d1")
	defer done()
	for i := 0; i < queueLen; i++ {
		if err := h.Notify("d1", configChanged(uint64(i))); err != nil {
			t.Fatal(err)
		}
	}
	if err := h.Notify("d1", configChanged(99)); !errors.Is(err, ErrOffline) {
		t.Fatalf("want ErrOffline, got %v", err)
	}
	<-s.Closed()
}

func TestSendCommand(t *testing.T) {
	h := New()
	s, done := h.Attach("d1")
	defer done()
	go func() {
		ev := <-s.Events
		// A result from another device is ignored.
		h.Done("d2", ev.GetCommand().GetId(), Result{OK: false})
		h.Done("d1", ev.GetCommand().GetId(), Result{OK: true, Message: "zoom 2.0"})
	}()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	r, err := h.Send(ctx, "d1", &sparrowv1.Command{Id: "c1", Kind: &sparrowv1.Command_Zoom{Zoom: &sparrowv1.ZoomCommand{Ratio: 2}}})
	if err != nil || !r.OK || r.Message != "zoom 2.0" {
		t.Fatalf("got %+v, %v", r, err)
	}
}

func TestSendTimeoutAndOffline(t *testing.T) {
	h := New()
	if _, err := h.Send(context.Background(), "d1", &sparrowv1.Command{Id: "c1"}); !errors.Is(err, ErrOffline) {
		t.Fatalf("offline: %v", err)
	}
	_, done := h.Attach("d1")
	defer done()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	if _, err := h.Send(ctx, "d1", &sparrowv1.Command{Id: "c2"}); !errors.Is(err, ErrTimeout) {
		t.Fatalf("timeout: %v", err)
	}
}
