package relay

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/binary"
	"io"
	"log/slog"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"

	srt "github.com/datarhei/gosrt"
)

const (
	testAlias      = "cam-01"
	testKey        = "k3y"
	pubPassphrase  = "publish-pass-123"
	readPassphrase = "reader-pass-456"
)

func startServer(t *testing.T) *Server {
	t.Helper()
	s := NewServer(Config{
		PublishAddr:       "127.0.0.1:0",
		ReadAddr:          "127.0.0.1:0",
		PublishPassphrase: pubPassphrase,
		ReadPassphrase:    readPassphrase,
		Latency:           120 * time.Millisecond,
		Authorize: func(_ context.Context, alias, key string) bool {
			return alias == testAlias && key == testKey
		},
		Logger: testLogger(),
	})
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { _ = s.Run(ctx); close(done) }()
	t.Cleanup(func() { cancel(); <-done })
	select {
	case <-s.Ready():
	case <-time.After(5 * time.Second):
		t.Fatal("server did not start")
	}
	return s
}

func testLogger() *slog.Logger {
	if os.Getenv("RELAY_TEST_LOG") != "" {
		return slog.New(slog.NewTextHandler(os.Stderr, nil))
	}
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

func dial(t *testing.T, addr net.Addr, streamID, passphrase string) (srt.Conn, error) {
	t.Helper()
	c := srt.DefaultConfig()
	c.StreamId = streamID
	c.Passphrase = passphrase
	c.PayloadSize = PayloadSize
	c.Latency = 120 * time.Millisecond
	return srt.Dial("srt", addr.String(), c)
}

// tsPayload builds seven TS packets with a sync byte and the payload number inside.
func tsPayload(n int) []byte {
	b := make([]byte, PayloadSize)
	for i := 0; i < 7; i++ {
		p := b[i*188 : (i+1)*188]
		p[0] = 0x47
		binary.BigEndian.PutUint32(p[4:], uint32(n))
		_, _ = rand.Read(p[8:])
	}
	return b
}

func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func TestRelayIsByteExact(t *testing.T) {
	s := startServer(t)
	pubAddr, readAddr := s.Addrs()

	// Two readers, to check that both get the full stream.
	var readers []srt.Conn
	for i := 0; i < 2; i++ {
		r, err := dial(t, readAddr, "#!::r=cam-01,m=request", readPassphrase)
		if err != nil {
			t.Fatalf("reader dial: %v", err)
		}
		defer r.Close()
		readers = append(readers, r)
	}
	waitFor(t, "readers", func() bool { _, n := s.Hub.Live(testAlias); return n == 2 })

	pub, err := dial(t, pubAddr, "#!::r=cam-01,m=publish,k="+testKey, pubPassphrase)
	if err != nil {
		t.Fatalf("publisher dial: %v", err)
	}
	const count = 1500 // about 2 MB: 13 s of a 1.2 Mbit/s stream, sent in about 1.5 s
	var sent bytes.Buffer
	results := make([]bytes.Buffer, len(readers))
	// gosrt ignores read deadlines, so a timer closes the readers if a payload never arrives;
	// the comparison below then fails instead of the test hanging.
	stuck := time.AfterFunc(60*time.Second, func() {
		for _, r := range readers {
			r.Close()
		}
	})
	defer stuck.Stop()
	var wg sync.WaitGroup
	for i, r := range readers {
		wg.Add(1)
		go func(i int, r srt.Conn) {
			defer wg.Done()
			buf := make([]byte, 1500)
			for results[i].Len() < count*PayloadSize {
				n, err := r.Read(buf)
				if err != nil {
					return
				}
				results[i].Write(buf[:n])
			}
		}(i, r)
	}
	for i := 0; i < count; i++ {
		p := tsPayload(i)
		sent.Write(p)
		if _, err := pub.Write(p); err != nil {
			t.Fatalf("publish write %d: %v", i, err)
		}
		if i%10 == 9 {
			// About 10 Mbit/s: well above a phone's bitrate, but paced like a live encoder so the
			// test does not depend on how fast a loaded CI machine drains a burst.
			time.Sleep(10 * time.Millisecond)
		}
	}
	wg.Wait()
	pub.Close()
	for i := range results {
		if !bytes.Equal(results[i].Bytes(), sent.Bytes()) {
			t.Fatalf("reader %d: got %d bytes, sent %d; streams differ", i, results[i].Len(), sent.Len())
		}
	}
}

func TestRelayRejects(t *testing.T) {
	s := startServer(t)
	pubAddr, readAddr := s.Addrs()
	cases := []struct {
		name       string
		addr       net.Addr
		streamID   string
		passphrase string
	}{
		{"wrong key", pubAddr, "#!::r=cam-01,m=publish,k=nope", pubPassphrase},
		{"unknown alias", pubAddr, "#!::r=cam-99,m=publish,k=" + testKey, pubPassphrase},
		{"wrong publish passphrase", pubAddr, "#!::r=cam-01,m=publish,k=" + testKey, "not-the-passphrase"},
		{"no encryption", pubAddr, "#!::r=cam-01,m=publish,k=" + testKey, ""},
		{"read mode on publish port", pubAddr, "#!::r=cam-01,m=request", pubPassphrase},
		{"publish on read port", readAddr, "#!::r=cam-01,m=publish,k=" + testKey, readPassphrase},
		{"wrong read passphrase", readAddr, "#!::r=cam-01,m=request", pubPassphrase},
		{"plain stream id", readAddr, "cam-01", readPassphrase},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			conn, err := dial(t, c.addr, c.streamID, c.passphrase)
			if err == nil {
				conn.Close()
				t.Fatal("connection was accepted")
			}
		})
	}
	if live, _ := s.Hub.Live(testAlias); live {
		t.Fatal("a rejected publisher is registered")
	}
}

func TestParseStreamID(t *testing.T) {
	id, err := ParseStreamID("#!::r=cam-07,m=publish,k=abc,u=ignored")
	if err != nil || id.Resource != "cam-07" || id.Mode != "publish" || id.Key != "abc" {
		t.Fatalf("got %+v, %v", id, err)
	}
	if id, _ := ParseStreamID("#!::r=cam-07"); id.Mode != "request" {
		t.Fatalf("default mode = %q", id.Mode)
	}
	for _, bad := range []string{"", "cam-07", "#!::m=publish", "#!::r=CAM", "#!::r=a/b"} {
		if _, err := ParseStreamID(bad); err == nil {
			t.Errorf("%q accepted", bad)
		}
	}
}

func TestSlowSubscriberDoesNotBlockOthers(t *testing.T) {
	h := NewHub()
	slow := h.Subscribe("a", 1)
	fast := h.Subscribe("a", 100)
	p := h.Publish("a", nil)
	for i := 0; i < 50; i++ {
		p.Write([]byte{byte(i)})
	}
	if len(fast.C) != 50 {
		t.Fatalf("fast subscriber got %d of 50", len(fast.C))
	}
	if slow.Dropped.Load() != 49 {
		t.Fatalf("slow subscriber dropped %d, want 49", slow.Dropped.Load())
	}
	// A second publisher replaces the first; the old one can no longer write.
	p2 := h.Publish("a", nil)
	p.Write([]byte{0xff})
	if len(fast.C) != 50 {
		t.Fatal("replaced publisher still delivers")
	}
	// Kick disconnects the current publisher but keeps subscribers.
	kicked := false
	p3 := h.Publish("a", func() { kicked = true })
	h.Kick("a")
	p3.Write([]byte{0xfe})
	if !kicked || len(fast.C) != 50 {
		t.Fatalf("kick: called=%v, fast queue %d", kicked, len(fast.C))
	}
	if live, n := h.Live("a"); live || n != 2 {
		t.Fatalf("after kick: live=%v subs=%d", live, n)
	}
	p2.Close()
	slow.Close()
	fast.Close()
	if live, n := h.Live("a"); live || n != 0 {
		t.Fatalf("stream not pruned: live=%v subs=%d", live, n)
	}
}

// TestLibsrtInterop checks gosrt against libsrt, the library the phone uses through srtdroid.
// FFmpeg publishes like the phone (caller, AES, access-control stream ID) and libsrt's
// srt-live-transmit reads like a 4609 tool. The reader must get exactly what the relay received.
func TestLibsrtInterop(t *testing.T) {
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("ffmpeg not found")
	}
	if out, _ := exec.Command(ffmpeg, "-hide_banner", "-protocols").Output(); !strings.Contains(string(out), "srt") {
		t.Skip("ffmpeg built without libsrt")
	}
	slt, err := exec.LookPath("srt-live-transmit")
	if err != nil {
		t.Skip("srt-live-transmit not found (Debian/Ubuntu package srt-tools)")
	}
	for _, keyLen := range []int{16, 32} {
		t.Run("pbkeylen="+strconv.Itoa(keyLen), func(t *testing.T) { runLibsrtInterop(t, ffmpeg, slt, keyLen) })
	}
}

func runLibsrtInterop(t *testing.T, ffmpeg, slt string, keyLen int) {
	dir := t.TempDir()
	in := filepath.Join(dir, "in.ts")
	gen := exec.Command(ffmpeg, "-nostdin", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=size=640x360:rate=15",
		"-t", "4", "-c:v", "libx264", "-g", "30", "-b:v", "800k", "-f", "mpegts", in)
	if b, err := gen.CombinedOutput(); err != nil {
		t.Skipf("cannot make test input (libx264 missing?): %v %s", err, b)
	}

	s := startServer(t)
	pubAddr, readAddr := s.Addrs()

	// Record what the relay receives from the publisher.
	tap := s.Hub.Subscribe(testAlias, 10000)
	var received bytes.Buffer
	tapDone := make(chan struct{})
	go func() {
		defer close(tapDone)
		for {
			select {
			case b := <-tap.C:
				received.Write(b)
			case <-tap.Done():
				return
			}
		}
	}()

	// Neither FFmpeg 6 nor srt-live-transmit URL-decodes the stream ID, so it goes in unencoded.
	srtURL := func(addr net.Addr, streamID, pass string) string {
		return "srt://" + addr.String() + "?pbkeylen=" + strconv.Itoa(keyLen) + "&latency=120&passphrase=" + pass +
			"&streamid=" + streamID
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	var got, readerErr bytes.Buffer
	reader := exec.CommandContext(ctx, slt, "-q", srtURL(readAddr, "#!::r=cam-01,m=request", readPassphrase), "file://con")
	reader.Stdout = &got
	reader.Stderr = &readerErr
	if err := reader.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if t.Failed() {
			t.Logf("srt-live-transmit stderr: %s", readerErr.String())
		}
	})
	waitFor(t, "srt-live-transmit reader", func() bool { _, n := s.Hub.Live(testAlias); return n == 2 })

	publisher := exec.CommandContext(ctx, ffmpeg, "-nostdin", "-loglevel", "error", "-re", "-i", in, "-map", "0",
		"-c", "copy", "-f", "mpegts", strings.Replace(srtURL(pubAddr, "#!::r=cam-01,m=publish,k="+testKey, pubPassphrase),
			"latency=120", "latency=120000", 1)) // FFmpeg takes microseconds
	if b, err := publisher.CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg publisher: %v %s", err, b)
	}
	// Let the last packets through, then stop the reader.
	time.Sleep(1500 * time.Millisecond)
	_ = reader.Process.Signal(syscall.SIGINT)
	waited := make(chan struct{})
	go func() { _ = reader.Wait(); close(waited) }()
	select {
	case <-waited:
	case <-time.After(5 * time.Second):
		_ = reader.Process.Kill()
		<-waited
	}
	tap.Close()
	<-tapDone

	// FFmpeg remuxes the input file, so the reference is what the relay received from the
	// publisher, not the file. This checks the relay leg: relay in == reader out.
	want := received.Bytes()
	if len(want) < 50_000 {
		t.Fatalf("relay received only %d bytes", len(want))
	}
	if !bytes.Equal(got.Bytes(), want) {
		t.Fatalf("reader got %d bytes, relay received %d; streams differ", got.Len(), len(want))
	}
	for i := 0; i < got.Len(); i += 188 {
		if got.Bytes()[i] != 0x47 {
			t.Fatalf("lost TS sync at byte %d", i)
		}
	}
	t.Logf("pbkeylen %d: %d bytes forwarded unchanged", keyLen, got.Len())
}
