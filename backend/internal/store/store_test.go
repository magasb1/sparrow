package store_test

import (
	"context"
	"crypto/sha256"
	"errors"
	"testing"
	"time"

	"github.com/magasb1/sparrow/backend/internal/store"
	"github.com/magasb1/sparrow/backend/internal/testdb"
)

func hash(s string) []byte {
	h := sha256.Sum256([]byte(s))
	return h[:]
}

func register(t *testing.T, st *store.Store, n string) store.Device {
	t.Helper()
	ctx := context.Background()
	if err := st.CreateEnrollment(ctx, hash("e"+n), time.Now().Add(time.Hour)); err != nil {
		t.Fatal(err)
	}
	d, err := st.Register(ctx, hash("e"+n), hash("t"+n), "key", store.DeviceInfo{Model: "M"})
	if err != nil {
		t.Fatal(err)
	}
	return d
}

func TestAliasesKeepCountingPast99(t *testing.T) {
	st := testdb.New(t)
	if got := register(t, st, "1").Alias; got != "cam-01" {
		t.Fatalf("first alias %q", got)
	}
	if _, err := st.DB().Exec(context.Background(), `SELECT setval('device_alias_seq', 98)`); err != nil {
		t.Fatal(err)
	}
	for i, want := range []string{"cam-99", "cam-100", "cam-101"} {
		if got := register(t, st, string(rune('a'+i))).Alias; got != want {
			t.Fatalf("alias %d: got %q, want %q", i, got, want)
		}
	}
}

func TestTouchFailsForRevokedDevice(t *testing.T) {
	st := testdb.New(t)
	ctx := context.Background()
	d := register(t, st, "1")
	if err := st.Touch(ctx, d.ID); err != nil {
		t.Fatal(err)
	}
	if err := st.Revoke(ctx, d.ID); err != nil {
		t.Fatal(err)
	}
	if err := st.Touch(ctx, d.ID); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("touch after revoke: %v", err)
	}
	if err := st.Revoke(ctx, d.ID); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("second revoke: %v", err)
	}
}
