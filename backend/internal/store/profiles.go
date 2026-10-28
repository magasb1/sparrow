package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

var ErrDuplicateName = errors.New("a profile with this name already exists")

// ProfileRow is a library profile.
type ProfileRow struct {
	ID, Name string
	// sparrow.v1.Profile, protobuf binary.
	Profile   []byte
	UpdatedAt time.Time
	// Devices assigned this profile.
	Devices int
}

// ConfigChange is a device whose config revision moved; the hub tells it to fetch.
type ConfigChange struct {
	DeviceID string
	Revision int64
}

func (s *Store) ListProfiles(ctx context.Context) ([]ProfileRow, error) {
	rows, err := s.pool.Query(ctx, `SELECT p.id, p.name, p.profile, p.updated_at,
			(SELECT count(*) FROM device_config c JOIN devices d ON d.id = c.device_id
			 WHERE c.profile_id = p.id AND d.revoked_at IS NULL)
		FROM profiles p ORDER BY p.name`)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(row pgx.CollectableRow) (ProfileRow, error) {
		var p ProfileRow
		err := row.Scan(&p.ID, &p.Name, &p.Profile, &p.UpdatedAt, &p.Devices)
		return p, err
	})
}

func (s *Store) Profile(ctx context.Context, id string) (ProfileRow, error) {
	var p ProfileRow
	err := s.pool.QueryRow(ctx, `SELECT id, name, profile, updated_at FROM profiles WHERE id = $1`, id).
		Scan(&p.ID, &p.Name, &p.Profile, &p.UpdatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return p, ErrNotFound
	}
	return p, err
}

// CreateProfile adds a profile and returns its id. encode gets the new id, so the stored message
// can carry it.
func (s *Store) CreateProfile(ctx context.Context, name string, encode func(id string) ([]byte, error)) (string, error) {
	var id string
	err := pgx.BeginFunc(ctx, s.pool, func(tx pgx.Tx) error {
		if err := tx.QueryRow(ctx, `SELECT gen_random_uuid()`).Scan(&id); err != nil {
			return err
		}
		b, err := encode(id)
		if err != nil {
			return err
		}
		_, err = tx.Exec(ctx, `INSERT INTO profiles (id, name, profile) VALUES ($1, $2, $3)`, id, name, b)
		return err
	})
	return id, duplicate(err)
}

// UpdateProfile replaces a profile and pushes it to every device assigned it.
func (s *Store) UpdateProfile(ctx context.Context, id, name string, profile []byte) ([]ConfigChange, error) {
	var changes []ConfigChange
	err := pgx.BeginFunc(ctx, s.pool, func(tx pgx.Tx) error {
		tag, err := tx.Exec(ctx, `UPDATE profiles SET name = $2, profile = $3, updated_at = now() WHERE id = $1`,
			id, name, profile)
		if err != nil {
			return err
		}
		if tag.RowsAffected() == 0 {
			return ErrNotFound
		}
		changes, err = bump(ctx, tx, `UPDATE device_config SET profile = $2, revision = revision + 1, updated_at = now()
			WHERE profile_id = $1 RETURNING device_id, revision`, id, profile)
		return err
	})
	return changes, duplicate(err)
}

// DeleteProfile removes a profile; devices that had it go back to their own profiles.
func (s *Store) DeleteProfile(ctx context.Context, id string) ([]ConfigChange, error) {
	var changes []ConfigChange
	err := pgx.BeginFunc(ctx, s.pool, func(tx pgx.Tx) error {
		var err error
		changes, err = bump(ctx, tx, `UPDATE device_config SET profile = NULL, profile_id = NULL,
			revision = revision + 1, updated_at = now() WHERE profile_id = $1 RETURNING device_id, revision`, id)
		if err != nil {
			return err
		}
		_, err = tx.Exec(ctx, `DELETE FROM profiles WHERE id = $1`, id)
		return err
	})
	return changes, err
}

// AssignProfile gives a device a library profile, or none when profileID is empty, and returns
// the new config revision.
func (s *Store) AssignProfile(ctx context.Context, deviceID, profileID string) (int64, error) {
	var pid *string
	if profileID != "" {
		pid = &profileID
	}
	var rev int64
	err := s.pool.QueryRow(ctx, `UPDATE device_config SET profile_id = $2,
			profile = (SELECT profile FROM profiles WHERE id = $2), revision = revision + 1, updated_at = now()
		WHERE device_id = $1 AND ($2::uuid IS NULL OR EXISTS (SELECT 1 FROM profiles WHERE id = $2))
		RETURNING revision`, deviceID, pid).Scan(&rev)
	if errors.Is(err, pgx.ErrNoRows) {
		return 0, ErrNotFound
	}
	return rev, err
}

func bump(ctx context.Context, tx pgx.Tx, sql string, args ...any) ([]ConfigChange, error) {
	rows, err := tx.Query(ctx, sql, args...)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(row pgx.CollectableRow) (ConfigChange, error) {
		var c ConfigChange
		err := row.Scan(&c.DeviceID, &c.Revision)
		return c, err
	})
}

func duplicate(err error) error {
	var pg *pgconn.PgError
	if errors.As(err, &pg) && pg.Code == "23505" {
		return ErrDuplicateName
	}
	return err
}
