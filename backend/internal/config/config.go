// Package config reads sparrowd's settings from the environment.
package config

import (
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	DatabaseURL string
	// HTTPS listener for phones and the web pages.
	Listen string
	// Base URL phones use, e.g. https://192.168.1.10:8443. Empty = taken from the browser's
	// request when an enrollment link is made.
	PublicURL string
	DataDir   string
	// Certificate and key files. Empty = a self-signed certificate in DataDir.
	TLSCert, TLSKey string
	// Extra host names and IPs for the self-signed certificate.
	TLSHosts []string
	// Serve plain HTTP. For lab use only: tokens cross the network in the clear.
	InsecureHTTP bool

	AdminUser     string
	AdminPassword string

	EnrollmentTTL   time.Duration
	ReportRetention time.Duration
}

// FromEnv reads SPARROW_* variables.
func FromEnv() (Config, error) {
	c := Config{
		DatabaseURL:     os.Getenv("SPARROW_DATABASE_URL"),
		Listen:          env("SPARROW_LISTEN", ":8443"),
		PublicURL:       strings.TrimRight(os.Getenv("SPARROW_PUBLIC_URL"), "/"),
		DataDir:         env("SPARROW_DATA_DIR", "/var/lib/sparrow"),
		TLSCert:         os.Getenv("SPARROW_TLS_CERT"),
		TLSKey:          os.Getenv("SPARROW_TLS_KEY"),
		TLSHosts:        splitList(os.Getenv("SPARROW_TLS_HOSTS")),
		AdminUser:       env("SPARROW_ADMIN_USER", "admin"),
		AdminPassword:   os.Getenv("SPARROW_ADMIN_PASSWORD"),
		EnrollmentTTL:   24 * time.Hour,
		ReportRetention: 30 * 24 * time.Hour,
	}
	var err error
	if c.InsecureHTTP, err = boolEnv("SPARROW_INSECURE_HTTP"); err != nil {
		return c, err
	}
	if v := os.Getenv("SPARROW_REPORT_RETENTION_DAYS"); v != "" {
		days, err := strconv.Atoi(v)
		if err != nil || days < 1 {
			return c, fmt.Errorf("SPARROW_REPORT_RETENTION_DAYS: want a whole number of days ≥ 1, got %q", v)
		}
		c.ReportRetention = time.Duration(days) * 24 * time.Hour
	}
	return c, c.validate()
}

func (c Config) validate() error {
	var errs []error
	if c.DatabaseURL == "" {
		errs = append(errs, errors.New("SPARROW_DATABASE_URL is required"))
	}
	if len(c.AdminPassword) < 8 {
		errs = append(errs, errors.New("SPARROW_ADMIN_PASSWORD is required (at least 8 characters)"))
	} else if strings.HasPrefix(strings.ToLower(c.AdminPassword), "change-me") {
		errs = append(errs, errors.New("SPARROW_ADMIN_PASSWORD is still the example value; choose a real password"))
	}
	if (c.TLSCert == "") != (c.TLSKey == "") {
		errs = append(errs, errors.New("set both SPARROW_TLS_CERT and SPARROW_TLS_KEY, or neither"))
	}
	if c.PublicURL != "" && !strings.HasPrefix(c.PublicURL, "https://") && !strings.HasPrefix(c.PublicURL, "http://") {
		errs = append(errs, errors.New("SPARROW_PUBLIC_URL must start with https:// or http://"))
	}
	return errors.Join(errs...)
}

func env(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func boolEnv(key string) (bool, error) {
	v := os.Getenv(key)
	if v == "" {
		return false, nil
	}
	b, err := strconv.ParseBool(v)
	if err != nil {
		return false, fmt.Errorf("%s: want true or false, got %q", key, v)
	}
	return b, nil
}

func splitList(s string) []string {
	var out []string
	for _, p := range strings.Split(s, ",") {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}
