-- +goose Up
-- Server-side profile library. A device is assigned at most one; device_config.profile holds the
-- copy last pushed to it, so GetConfig stays a single-row read.
CREATE TABLE profiles (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name       text NOT NULL UNIQUE,
    -- sparrow.v1.Profile, protobuf binary.
    profile    bytea NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE device_config ADD COLUMN profile_id uuid REFERENCES profiles(id) ON DELETE SET NULL;

-- +goose Down
ALTER TABLE device_config DROP COLUMN profile_id;
DROP TABLE profiles;
