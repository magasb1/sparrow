// Package testdb gives tests a fresh, migrated PostgreSQL database. Set SPARROW_TEST_DATABASE_URL
// to a server where the user may create databases; tests are skipped otherwise.
package testdb

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"net/url"
	"os"
	"testing"

	"github.com/jackc/pgx/v5"
	"github.com/magasb1/sparrow/backend/internal/store"
)

func New(t *testing.T) *store.Store {
	t.Helper()
	admin := os.Getenv("SPARROW_TEST_DATABASE_URL")
	if admin == "" {
		t.Skip("SPARROW_TEST_DATABASE_URL not set")
	}
	ctx := context.Background()
	conn, err := pgx.Connect(ctx, admin)
	if err != nil {
		t.Fatalf("connect: %v", err)
	}
	b := make([]byte, 6)
	_, _ = rand.Read(b)
	name := "sparrow_test_" + hex.EncodeToString(b)
	if _, err := conn.Exec(ctx, "CREATE DATABASE "+name); err != nil {
		conn.Close(ctx)
		t.Fatalf("create database: %v", err)
	}
	// Registered before anything else can fail, so the database is always dropped.
	t.Cleanup(func() {
		_, _ = conn.Exec(context.Background(), "DROP DATABASE "+name+" WITH (FORCE)")
		conn.Close(context.Background())
	})
	u, err := url.Parse(admin)
	if err != nil {
		t.Fatal(err)
	}
	u.Path = "/" + name
	st, err := store.Open(ctx, u.String())
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	t.Cleanup(st.Close) // runs before the drop: cleanups are last in, first out
	return st
}
