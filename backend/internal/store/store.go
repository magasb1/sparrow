// Package store keeps sparrowd's state in PostgreSQL.
package store

import (
	"context"
	"embed"
	"errors"
	"fmt"
	"io/fs"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/jackc/pgx/v5/stdlib"
	"github.com/pressly/goose/v3"
)

//go:embed migrations/*.sql
var migrations embed.FS

var (
	ErrNotFound          = errors.New("not found")
	ErrEnrollmentInvalid = errors.New("enrollment token is unknown, used or expired")
)

type Store struct {
	pool *pgxpool.Pool
}

// Open connects and applies pending migrations.
func Open(ctx context.Context, url string) (*Store, error) {
	pool, err := pgxpool.New(ctx, url)
	if err != nil {
		return nil, err
	}
	if err := pool.Ping(ctx); err != nil {
		pool.Close()
		return nil, fmt.Errorf("connect to database: %w", err)
	}
	if err := migrate(ctx, pool); err != nil {
		pool.Close()
		return nil, fmt.Errorf("migrate: %w", err)
	}
	return &Store{pool: pool}, nil
}

func migrate(ctx context.Context, pool *pgxpool.Pool) error {
	db := stdlib.OpenDBFromPool(pool)
	defer db.Close()
	dir, err := fs.Sub(migrations, "migrations")
	if err != nil {
		return err
	}
	provider, err := goose.NewProvider(goose.DialectPostgres, db, dir)
	if err != nil {
		return err
	}
	_, err = provider.Up(ctx)
	return err
}

func (s *Store) Close() { s.pool.Close() }

// DeviceInfo is what a phone tells about itself when it registers.
type DeviceInfo struct {
	Manufacturer, Model, AppVersion string
	AndroidSDK                      int
	ZoomMin, ZoomMax                float64
}

type Device struct {
	ID, Alias, Name, PublishKey string
	DeviceInfo
	RegisteredAt time.Time
	LastSeenAt   *time.Time
}

// CreateEnrollment stores a single-use enrollment token (by hash).
func (s *Store) CreateEnrollment(ctx context.Context, tokenHash []byte, expires time.Time) error {
	_, err := s.pool.Exec(ctx, `INSERT INTO enrollments (token_hash, expires_at) VALUES ($1, $2)`, tokenHash, expires)
	return err
}

// Register uses up an enrollment token and creates a device with the next free alias.
func (s *Store) Register(ctx context.Context, enrollHash, tokenHash []byte, publishKey string, info DeviceInfo) (Device, error) {
	var d Device
	err := pgx.BeginFunc(ctx, s.pool, func(tx pgx.Tx) error {
		var ok bool
		err := tx.QueryRow(ctx, `SELECT used_at IS NULL AND expires_at > now() FROM enrollments
			WHERE token_hash = $1 FOR UPDATE`, enrollHash).Scan(&ok)
		if errors.Is(err, pgx.ErrNoRows) || err == nil && !ok {
			return ErrEnrollmentInvalid
		}
		if err != nil {
			return err
		}
		name := info.Model
		if info.Manufacturer != "" {
			name = info.Manufacturer + " " + info.Model
		}
		// cam-01 … cam-99, then cam-100 and up (lpad would truncate "100" to "10").
		err = tx.QueryRow(ctx, `INSERT INTO devices
			(alias, name, token_hash, publish_key, manufacturer, model, android_sdk, app_version, zoom_min, zoom_max)
			SELECT 'cam-' || CASE WHEN s.n < 10 THEN '0' ELSE '' END || s.n, $1, $2, $3, $4, $5, $6, $7, $8, $9
			FROM (SELECT nextval('device_alias_seq') AS n) s
			RETURNING id, alias, name, registered_at`,
			name, tokenHash, publishKey, info.Manufacturer, info.Model, info.AndroidSDK, info.AppVersion,
			info.ZoomMin, info.ZoomMax,
		).Scan(&d.ID, &d.Alias, &d.Name, &d.RegisteredAt)
		if err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `UPDATE enrollments SET used_at = now(), device_id = $2 WHERE token_hash = $1`,
			enrollHash, d.ID); err != nil {
			return err
		}
		_, err = tx.Exec(ctx, `INSERT INTO device_config (device_id) VALUES ($1)`, d.ID)
		return err
	})
	if err != nil {
		return Device{}, err
	}
	d.PublishKey = publishKey
	d.DeviceInfo = info
	return d, nil
}

const deviceColumns = `id, alias, name, publish_key, manufacturer, model, android_sdk, app_version,
	zoom_min, zoom_max, registered_at, last_seen_at`

func scanDevice(row pgx.Row) (Device, error) {
	var d Device
	err := row.Scan(&d.ID, &d.Alias, &d.Name, &d.PublishKey, &d.Manufacturer, &d.Model, &d.AndroidSDK,
		&d.AppVersion, &d.ZoomMin, &d.ZoomMax, &d.RegisteredAt, &d.LastSeenAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return d, ErrNotFound
	}
	return d, err
}

// DeviceByToken finds an active (not revoked) device by its token hash.
func (s *Store) DeviceByToken(ctx context.Context, tokenHash []byte) (Device, error) {
	return scanDevice(s.pool.QueryRow(ctx, `SELECT `+deviceColumns+` FROM devices
		WHERE token_hash = $1 AND revoked_at IS NULL`, tokenHash))
}

// Device finds an active device by id.
func (s *Store) Device(ctx context.Context, id string) (Device, error) {
	return scanDevice(s.pool.QueryRow(ctx, `SELECT `+deviceColumns+` FROM devices
		WHERE id = $1 AND revoked_at IS NULL`, id))
}

// Touch records that the device was heard from. It returns ErrNotFound when the device has been
// revoked in the meantime.
func (s *Store) Touch(ctx context.Context, id string) error {
	tag, err := s.pool.Exec(ctx, `UPDATE devices SET last_seen_at = now() WHERE id = $1 AND revoked_at IS NULL`, id)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

// Revoke disables a device. Its token stops working at once. Returns ErrNotFound when the device
// does not exist or is already revoked.
func (s *Store) Revoke(ctx context.Context, id string) error {
	tag, err := s.pool.Exec(ctx, `UPDATE devices SET revoked_at = now() WHERE id = $1 AND revoked_at IS NULL`, id)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

// Report is one status and telemetry sample. Nil pointers are values the phone did not have.
type Report struct {
	Time                     time.Time
	State, Reason, Profile   string
	SendKbps                 float64
	TargetKbps               int
	RttMs, LossPercent       *float64
	Lat, Lon                 *float64
	AltMsl                   *float64
	AccuracyM                *float64
	HeadingDeg, ElevationDeg *float64
	Zoom                     float64
	Resolution               string
	Battery                  *int
	Charging                 bool
	ThermalStatus            int
	ConfigRevision           int64
	ReceivedAt               time.Time
}

// InsertReport stores a report and updates last-seen time and the applied config revision.
func (s *Store) InsertReport(ctx context.Context, deviceID string, r Report) error {
	return pgx.BeginFunc(ctx, s.pool, func(tx pgx.Tx) error {
		_, err := tx.Exec(ctx, `INSERT INTO reports (device_id, ts, state, reason, profile_name, send_kbps,
			target_kbps, rtt_ms, loss_percent, lat, lon, alt_msl, accuracy_m, heading_deg, elevation_deg, zoom,
			resolution, battery, charging, thermal_status)
			VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20)`,
			deviceID, r.Time, r.State, r.Reason, r.Profile, r.SendKbps, r.TargetKbps, r.RttMs, r.LossPercent,
			r.Lat, r.Lon, r.AltMsl, r.AccuracyM, r.HeadingDeg, r.ElevationDeg, r.Zoom, r.Resolution, r.Battery,
			r.Charging, r.ThermalStatus)
		if err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `UPDATE devices SET last_seen_at = now() WHERE id = $1`, deviceID); err != nil {
			return err
		}
		// A phone never reports a revision newer than the server's.
		_, err = tx.Exec(ctx, `UPDATE device_config SET applied_revision = LEAST($2, revision)
			WHERE device_id = $1`, deviceID, r.ConfigRevision)
		return err
	})
}

// Config returns the managed profile (protobuf bytes, nil if none) and its revision.
func (s *Store) Config(ctx context.Context, deviceID string) (revision int64, profile []byte, err error) {
	err = s.pool.QueryRow(ctx, `SELECT revision, profile FROM device_config WHERE device_id = $1`, deviceID).
		Scan(&revision, &profile)
	if errors.Is(err, pgx.ErrNoRows) {
		return 0, nil, ErrNotFound
	}
	return revision, profile, err
}

// DeviceRow is one line of the devices page.
type DeviceRow struct {
	Device
	ConfigRevision, AppliedRevision int64
	// Assigned library profile; empty when the phone uses its own profiles.
	ProfileID, ProfileName string
	Latest                 *Report
}

// ListDevices returns active devices with their latest report.
func (s *Store) ListDevices(ctx context.Context) ([]DeviceRow, error) {
	rows, err := s.pool.Query(ctx, `SELECT d.id, d.alias, d.name, d.publish_key, d.manufacturer, d.model,
			d.android_sdk, d.app_version, d.zoom_min, d.zoom_max, d.registered_at, d.last_seen_at,
			c.revision, c.applied_revision, COALESCE(p.id::text, ''), COALESCE(p.name, ''),
			r.ts, r.received_at, r.state, r.reason, r.profile_name, r.send_kbps, r.target_kbps, r.rtt_ms,
			r.loss_percent, r.lat, r.lon, r.alt_msl, r.accuracy_m, r.heading_deg, r.elevation_deg, r.zoom,
			r.resolution, r.battery, r.charging, r.thermal_status
		FROM devices d
		JOIN device_config c ON c.device_id = d.id
		LEFT JOIN profiles p ON p.id = c.profile_id
		LEFT JOIN LATERAL (SELECT * FROM reports WHERE device_id = d.id ORDER BY received_at DESC LIMIT 1) r ON true
		WHERE d.revoked_at IS NULL
		ORDER BY d.alias`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []DeviceRow
	for rows.Next() {
		var row DeviceRow
		var ts, received *time.Time
		var state, reason, profile, resolution *string
		var sendKbps, zoom *float64
		var targetKbps, thermal *int
		var charging *bool
		r := &Report{}
		d := &row.Device
		if err := rows.Scan(&d.ID, &d.Alias, &d.Name, &d.PublishKey, &d.Manufacturer, &d.Model, &d.AndroidSDK,
			&d.AppVersion, &d.ZoomMin, &d.ZoomMax, &d.RegisteredAt, &d.LastSeenAt,
			&row.ConfigRevision, &row.AppliedRevision, &row.ProfileID, &row.ProfileName,
			&ts, &received, &state, &reason, &profile, &sendKbps, &targetKbps, &r.RttMs, &r.LossPercent,
			&r.Lat, &r.Lon, &r.AltMsl, &r.AccuracyM, &r.HeadingDeg, &r.ElevationDeg, &zoom, &resolution,
			&r.Battery, &charging, &thermal); err != nil {
			return nil, err
		}
		if ts != nil {
			r.Time, r.ReceivedAt = *ts, *received
			r.State, r.Reason, r.Profile, r.Resolution = *state, *reason, *profile, *resolution
			r.SendKbps, r.TargetKbps, r.Zoom = *sendKbps, *targetKbps, *zoom
			r.Charging, r.ThermalStatus = *charging, *thermal
			row.Latest = r
		}
		out = append(out, row)
	}
	return out, rows.Err()
}

// DeleteReportsBefore removes reports received before t.
func (s *Store) DeleteReportsBefore(ctx context.Context, t time.Time) (int64, error) {
	tag, err := s.pool.Exec(ctx, `DELETE FROM reports WHERE received_at < $1`, t)
	return tag.RowsAffected(), err
}

// InsertCommand records a command sent to a device.
func (s *Store) InsertCommand(ctx context.Context, id, deviceID, kind string, arg *float64) error {
	_, err := s.pool.Exec(ctx, `INSERT INTO commands (id, device_id, kind, arg) VALUES ($1, $2, $3, $4)`,
		id, deviceID, kind, arg)
	return err
}

// CommandDone records a command's result. Unknown ids are ignored.
func (s *Store) CommandDone(ctx context.Context, deviceID, id string, ok bool, message string) error {
	_, err := s.pool.Exec(ctx, `UPDATE commands SET done_at = now(), ok = $3, message = $4
		WHERE id = $1 AND device_id = $2 AND done_at IS NULL`, id, deviceID, ok, message)
	return err
}

// DB exposes the pool for tests.
func (s *Store) DB() *pgxpool.Pool { return s.pool }
