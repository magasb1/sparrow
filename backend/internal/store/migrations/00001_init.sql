-- +goose Up
CREATE SEQUENCE device_alias_seq;

CREATE TABLE devices (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    alias         text NOT NULL UNIQUE,
    name          text NOT NULL DEFAULT '',
    token_hash    bytea NOT NULL UNIQUE,
    -- SRT publish key for the stream ID (used from milestone 3).
    publish_key   text NOT NULL,
    manufacturer  text NOT NULL DEFAULT '',
    model         text NOT NULL DEFAULT '',
    android_sdk   integer NOT NULL DEFAULT 0,
    app_version   text NOT NULL DEFAULT '',
    zoom_min      double precision NOT NULL DEFAULT 1,
    zoom_max      double precision NOT NULL DEFAULT 1,
    registered_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at  timestamptz,
    revoked_at    timestamptz
);

CREATE TABLE enrollments (
    token_hash bytea PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    device_id  uuid REFERENCES devices(id) ON DELETE SET NULL
);

CREATE TABLE device_config (
    device_id        uuid PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
    revision         bigint NOT NULL DEFAULT 0,
    -- sparrow.v1.Profile, protobuf binary; NULL while the server manages no profile.
    profile          bytea,
    applied_revision bigint NOT NULL DEFAULT 0,
    updated_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE reports (
    device_id      uuid NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    ts             timestamptz NOT NULL,
    received_at    timestamptz NOT NULL DEFAULT now(),
    state          text NOT NULL,
    reason         text NOT NULL DEFAULT '',
    profile_name   text NOT NULL DEFAULT '',
    send_kbps      real NOT NULL DEFAULT 0,
    target_kbps    integer NOT NULL DEFAULT 0,
    rtt_ms         real,
    loss_percent   real,
    lat            double precision,
    lon            double precision,
    alt_msl        real,
    accuracy_m     real,
    heading_deg    real,
    elevation_deg  real,
    zoom           real NOT NULL DEFAULT 1,
    resolution     text NOT NULL DEFAULT '',
    battery        smallint,
    charging       boolean NOT NULL DEFAULT false,
    thermal_status smallint NOT NULL DEFAULT 0
);
CREATE INDEX reports_device_received ON reports (device_id, received_at DESC);
CREATE INDEX reports_received ON reports (received_at);

CREATE TABLE commands (
    id        text PRIMARY KEY,
    device_id uuid NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    kind      text NOT NULL,
    arg       double precision,
    issued_at timestamptz NOT NULL DEFAULT now(),
    done_at   timestamptz,
    ok        boolean,
    message   text NOT NULL DEFAULT ''
);

-- +goose Down
DROP TABLE commands;
DROP TABLE reports;
DROP TABLE device_config;
DROP TABLE enrollments;
DROP TABLE devices;
DROP SEQUENCE device_alias_seq;
