// sparrowd is the Sparrow backend: phone registration, status and telemetry, and the web pages.
package main

import (
	"context"
	"crypto/tls"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/magasb1/sparrow/backend/internal/api"
	"github.com/magasb1/sparrow/backend/internal/config"
	"github.com/magasb1/sparrow/backend/internal/hub"
	"github.com/magasb1/sparrow/backend/internal/store"
	"github.com/magasb1/sparrow/backend/internal/tlsutil"
	"github.com/magasb1/sparrow/backend/internal/web"
)

func main() {
	log := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	if err := run(log); err != nil {
		log.Error("sparrowd stopped", "err", err)
		os.Exit(1)
	}
}

func run(log *slog.Logger) error {
	cfg, err := config.FromEnv()
	if err != nil {
		return err
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	st, err := store.Open(ctx, cfg.DatabaseURL)
	if err != nil {
		return err
	}
	defer st.Close()

	var cert tls.Certificate
	pin := ""
	if !cfg.InsecureHTTP {
		if cfg.TLSCert != "" {
			cert, err = tlsutil.Load(cfg.TLSCert, cfg.TLSKey)
		} else {
			cert, err = tlsutil.LoadOrCreateSelfSigned(cfg.DataDir, cfg.TLSHosts)
		}
		if err != nil {
			return err
		}
		if pin, err = tlsutil.Pin(cert); err != nil {
			return err
		}
	} else {
		log.Warn("serving plain HTTP: device tokens cross the network unencrypted; use only in a lab")
	}

	h := hub.New()
	mux := http.NewServeMux()
	mux.Handle(api.New(st, h, log).Handler())
	web.New(web.Config{
		AdminUser:     cfg.AdminUser,
		AdminPassword: cfg.AdminPassword,
		PublicURL:     cfg.PublicURL,
		Pin:           pin,
		SecureCookies: !cfg.InsecureHTTP,
		EnrollmentTTL: cfg.EnrollmentTTL,
	}, st, h, log).Routes(mux)
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) { _, _ = w.Write([]byte("ok\n")) })

	srv := &http.Server{
		Addr:              cfg.Listen,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       2 * time.Minute,
		// No read or write timeout: Subscribe streams stay open for hours.
		// Handlers see ctx, so open streams end when sparrowd shuts down.
		BaseContext: func(net.Listener) context.Context { return ctx },
		ErrorLog:    slog.NewLogLogger(log.Handler(), slog.LevelWarn),
	}
	if !cfg.InsecureHTTP {
		srv.TLSConfig = &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS12}
	}

	go pruneReports(ctx, st, cfg.ReportRetention, log)

	errc := make(chan error, 1)
	go func() {
		log.Info("sparrowd listening", "addr", cfg.Listen, "tls", !cfg.InsecureHTTP, "pin", pin)
		if cfg.InsecureHTTP {
			errc <- srv.ListenAndServe()
		} else {
			errc <- srv.ListenAndServeTLS("", "")
		}
	}()
	select {
	case err := <-errc:
		return err
	case <-ctx.Done():
	}
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil && !errors.Is(err, context.DeadlineExceeded) {
		return err
	}
	log.Info("sparrowd stopped")
	return nil
}

// pruneReports deletes old reports at start and then once a day.
func pruneReports(ctx context.Context, st *store.Store, keep time.Duration, log *slog.Logger) {
	for {
		n, err := st.DeleteReportsBefore(ctx, time.Now().Add(-keep))
		if err != nil && ctx.Err() == nil {
			log.Warn("prune reports", "err", err)
		} else if n > 0 {
			log.Info("pruned old reports", "rows", n)
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(24 * time.Hour):
		}
	}
}
