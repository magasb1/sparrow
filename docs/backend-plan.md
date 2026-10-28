# Sparrow backend: first implementation plan (v4, simple)

A small on-prem backend for Sparrow. Phones register with it, send status and telemetry, wait in
standby, and take start, stop, zoom and config from a web page. The backend shows live video and
passes each phone's stream on to other systems, with KLV byte-exact on SRT.

The app works exactly as today when no backend is registered.

Status: **milestones 0, 1 and 2 implemented** (see [backend/README.md](../backend/README.md) for
results and how to run it). Milestones 3–4 are not started. Milestone 2 differs from §4.4 in one
way: profiles live in a library on the server, and each phone is assigned one of them (editing a
profile re-pushes it to every phone that has it). Standby holds a partial wake lock. This version deliberately leaves out
PKI, ONVIF and everything else in §9. The design keeps room for them.

---

## 1. What the first version does

1. **Register** a phone by scanning or pasting an enrollment link from the web page.
2. **Standby**: a registered phone arms itself, so the web page can start it remotely.
3. **Status and telemetry** every few seconds: state, bitrate, RTT, loss, position, heading,
   zoom, battery.
4. **Commands** from the web page: Start, Stop, Restart, Zoom.
5. **Config** from the web page: one managed profile per phone, pushed and acknowledged.
6. **Viewer** in the web page.
7. **Distribution**: SRT read with byte-exact KLV, RTSP for NVR/VMS, SRT push and RTMP push.

---

## 2. Architecture

```
 Phone                                       Server (docker compose)
 ┌──────────────────────────┐               ┌──────────────────────────────────────────────────┐
 │ StreamingService         │  SRT :8890    │ sparrowd (Go)                                    │
 │  STANDBY ⇄ LIVE ─────────┼──────────────▶│  ingest (gosrt) ─ hub ─┬─ SRT read :8891 ─▶ 4609 tools (byte-exact)
 │                          │               │                        ├─ SRT push       ─▶ remote listener (byte-exact)
 │ remote/                  │  HTTPS :8443  │                        ├─ RTMP push (ffmpeg -c copy) ─▶ RTMP server
 │  BackendClient ──────────┼──────────────▶│  device API            └─ loopback SRT ─▶ MediaMTX
 │  Report every 5–30 s     │  Connect      │  web pages :8443 (Go templates + htmx)           │
 │  Subscribe (commands) ◀──┼───────────────│  PostgreSQL                                      │
 └──────────────────────────┘               │ MediaMTX ─ RTSP :8554 ─▶ NVR/VMS                 │
                                            │          ─ WebRTC (proxied by sparrowd) ─▶ viewer │
                                            └──────────────────────────────────────────────────┘
```

Three containers: `sparrowd`, `postgres`, `mediamtx`. One port for HTTPS, two for SRT, one for
RTSP, one UDP port for WebRTC media.

### 2.1 Why sparrowd takes the SRT stream

MediaMTX unpacks and repacks every stream, so its output cannot be byte-exact. sparrowd accepts the
phone's SRT with the pure-Go `datarhei/gosrt` library and forwards the phone's TS packets unchanged.
MediaMTX gets a copy over loopback only for RTSP and the browser viewer.

---

## 3. Phone ↔ server protocol

### 3.1 Transport

* **Protobuf messages, Connect protocol over HTTPS.** A Connect unary call is an HTTP POST with a
  protobuf body. A server stream is the same POST with a chunked response of length-prefixed
  messages. Both work over HTTP/1.1, through any proxy, and can be tried with `curl` using the JSON
  form.
* **Server:** `connectrpc.com/connect` in Go.
* **Phone:** generated `protobuf-kotlin-lite` messages and a hand-written client on OkHttp of about
  150 lines. That avoids gRPC's HTTP/2 requirement and the beta connect-kotlin library.
* **Auth:** `Authorization: Bearer <device token>`. The token is 32 random bytes, stored hashed on
  the server and in the existing `SecretStore` on the phone.
* **TLS:** sparrowd uses a certificate from files if given; otherwise it creates a self-signed one
  on first start. The enrollment link carries the SHA-256 of the certificate's public key (SPKI)
  and the phone pins it, so renewing the certificate with the same key keeps phones working. `http://` is accepted for lab use, with a warning in the app.

### 3.2 Service

```proto
syntax = "proto3";
package sparrow.v1;

service DeviceService {
  rpc Register(RegisterRequest) returns (RegisterResponse);        // no auth; uses the enrollment token
  rpc Report(Report) returns (ReportResponse);                     // status + telemetry
  rpc Subscribe(SubscribeRequest) returns (stream ServerEvent);    // commands and config changes
  rpc CommandDone(CommandResult) returns (Empty);
  rpc GetConfig(Empty) returns (DeviceConfig);
}

message RegisterRequest {
  string enrollment_token = 1;
  DeviceInfo device = 2;                 // manufacturer, model, Android version, app version, camera zoom range
}
message RegisterResponse {
  string device_id = 1;
  string device_token = 2;
  string alias = 3;                      // stream name, e.g. "cam-07"
  DeviceConfig config = 4;
}

message DeviceConfig {
  uint64 revision = 1;
  Profile profile = 2;                   // the one managed profile, mirrors the Kotlin Profile
}

message Report {
  google.protobuf.Timestamp time = 1;
  State state = 2;                       // IDLE, STANDBY, STARTING, LIVE, RECONNECTING, FAILED
  string reason = 3;
  uint64 config_revision = 4;            // revision the phone has applied
  // Link, while live
  double send_kbps = 10; double rtt_ms = 11; double loss_percent = 12; uint32 target_kbps = 13;
  // Position, when there is a fix
  optional double lat = 20; optional double lon = 21; optional double alt_msl = 22;
  optional float accuracy_m = 23; optional double heading_deg = 24; optional double elevation_deg = 25;
  // Camera and device
  double zoom = 30; double zoom_max = 31; string resolution = 32;
  uint32 battery_percent = 33; bool charging = 34; uint32 thermal_status = 35;
}
message ReportResponse { uint64 latest_config_revision = 1; }   // phone calls GetConfig if newer

message ServerEvent {
  oneof event {
    Command command = 1;
    uint64 config_changed = 2;           // new revision; phone calls GetConfig
    Empty heartbeat = 3;                 // every 15 s, so the phone notices a dead stream
  }
}
message Command {
  string id = 1;
  oneof kind { Empty start = 10; Empty stop = 11; Empty restart = 12; double zoom = 13; }
}
message CommandResult { string id = 1; bool ok = 2; string message = 3; }
```

`Profile` in proto copies the Kotlin `Profile` fields one to one. A small mapper converts between
them, with a round-trip test that sets every field.

### 3.3 Timing

| Phone state | Subscribe stream | Report interval |
| --- | --- | --- |
| Live | Open | 5 s |
| Standby | Open | 30 s |
| App open, not armed | Open | 30 s |
| Not armed, app closed | Closed | none |

Telemetry produced while the phone has no connection is dropped. The next report is the current
state, which is all the web page needs.

---

## 4. Server: sparrowd

### 4.1 Layout

```
proto/sparrow/v1/device.proto        the file above; buf.yaml, buf.gen.yaml
backend/
  go.mod                             module github.com/magasb1/sparrow/backend
  cmd/sparrowd/main.go               config from env, wiring, shutdown
  internal/
    api/        DeviceService handlers, bearer-token middleware, command routing
    store/      PostgreSQL with pgx v5 and sqlc; migrations with goose
    relay/      gosrt listeners, per-alias hub, SRT read (milestone 0); SRT push later
    outputs/    loopback feed to MediaMTX, ffmpeg RTMP workers (milestone 3–4)
    web/        html/template pages, htmx (embedded), login, MediaMTX WebRTC proxy
  gen/          buf-generated Go
deploy/
  docker-compose.yml   sparrowd, postgres:17, mediamtx (pinned versions)
  mediamtx.yml
  .env.example
```

### 4.2 Configuration (environment)

```
SPARROW_DATABASE_URL     postgres://...
SPARROW_ADMIN_USER       admin
SPARROW_ADMIN_PASSWORD   at least 8 characters
SPARROW_PUBLIC_URL       base URL phones use, e.g. https://192.168.1.10:8443
SPARROW_TLS_CERT/KEY     optional; self-signed if empty
SPARROW_SRT_PUBLISH_PASSPHRASE   phones → server
SPARROW_SRT_READ_PASSPHRASE      readers → server
SPARROW_READ_USER/PASSWORD       RTSP readers (NVR/VMS)
```

### 4.3 Database

```sql
devices (
  id uuid primary key, alias text unique, name text,
  token_hash bytea, publish_key text,
  model text, app_version text, zoom_max double precision,
  registered_at timestamptz, last_seen_at timestamptz, revoked_at timestamptz
);
enrollments (token_hash bytea primary key, expires_at timestamptz, used_at timestamptz, device_id uuid);
device_config (device_id uuid primary key, revision bigint, profile bytea, applied_revision bigint, updated_at timestamptz);
reports (device_id uuid, ts timestamptz, state text, send_kbps real, rtt_ms real, loss real,
         lat double precision, lon double precision, alt real, heading real, zoom real,
         battery smallint, thermal smallint, primary key (device_id, ts));
commands (id uuid primary key, device_id uuid, kind text, arg double precision,
          issued_at timestamptz, done_at timestamptz, ok boolean, message text);
outputs (id uuid primary key, alias text, kind text, url text, enabled boolean);   -- srt_push | rtmp
```

A daily job deletes reports older than 30 days.

### 4.4 Commands and config

* sparrowd keeps a map of open `Subscribe` streams, one per device.
* **Command:** the web page posts it; sparrowd stores it, sends it down the device's stream, and
  waits up to 10 s for `CommandDone`. If the phone has no open stream, the command fails at once.
  Commands are never queued.
* **Config:** the web page saves the profile; sparrowd bumps the revision and sends
  `config_changed`. The phone fetches it, stores it as its managed profile, selects it, and reports
  the new revision. The page shows "applied" or "waiting for phone". A changed config takes effect
  at the next start; the page offers Restart when the phone is live.

### 4.5 Web pages

Server-rendered with Go templates and htmx, with no JavaScript build step. Device rows refresh
every 2 s.

* **Login:** one admin user from the environment, session cookie.
* **Devices:** alias, name, state, bitrate, battery, last seen, config applied or pending.
* **Add phone:** creates a single-use enrollment link (24 h) and shows it as text and QR:
  `sparrow://enroll?u=https://host:8443&t=<token>&fp=<sha256>`
* **Device:** live viewer, status, map pin, Start / Stop / Restart, zoom slider, config form,
  stream addresses to copy, outputs (add SRT push or RTMP push), Remove (revokes the token).

The viewer embeds MediaMTX's WebRTC page through a reverse proxy in sparrowd
(`/live/<alias>/` → `127.0.0.1:8889/<alias>/`). The page then has the same origin and certificate,
and MediaMTX's web ports stay closed to the network. Media flows on MediaMTX's WebRTC UDP port.

---

## 5. Media

### 5.1 Ingest

* **Phones publish** to `:8890` with stream ID `#!::r=<alias>,m=publish,k=<publish key>` and the
  site publish passphrase. Both come to the phone in its managed profile. The accept callback checks
  the alias and key against the database and rejects revoked phones.
* **Hub:** per alias, every SRT payload from the phone goes to each subscriber's bounded queue. A
  slow subscriber drops its own packets and never slows the others.
* **Keyframe on join:** the phone already sends an IDR on every new SRT connection. A reader that
  joins later waits at most one GOP (2 s by default). Requesting a keyframe through the control
  link is a later improvement.

### 5.2 Outputs

| Output | Address | KLV |
| --- | --- | --- |
| SRT read | `srt://host:8891?streamid=#!::r=<alias>,m=request&passphrase=<read passphrase>` | Byte-exact |
| SRT push | sparrowd calls out to a configured `srt://` URL | Byte-exact |
| RTSP | `rtsp://user:pass@host:8554/<alias>` from MediaMTX | Not guaranteed |
| Viewer | Web page, WebRTC from MediaMTX | None |
| RTMP push | `ffmpeg -i srt://127.0.0.1:8891?... -c copy -f flv rtmp://...`, one supervised process per output | None (RTMP cannot carry KLV) |

"Byte-exact" means the TS packets leave sparrowd exactly as the phone sent them.

### 5.3 MediaMTX

```yaml
rtsp: yes                     # :8554, readers need SPARROW_READ_USER
webrtc: yes                   # 127.0.0.1:8889 for signalling, UDP :8189 for media
hls: no
rtmp: no
api: no
authInternalUsers:
  - user: sparrowd            # loopback publisher
    pass: ${MTX_FEED_PASSWORD}
    ips: [127.0.0.1/32]
    permissions: [{action: publish}]
  - user: ${SPARROW_READ_USER}
    pass: ${SPARROW_READ_PASSWORD}
    permissions: [{action: read}]
  - user: any                 # viewer through the sparrowd proxy only
    ips: [127.0.0.1/32]
    permissions: [{action: read}]
```

sparrowd publishes each live alias to MediaMTX over SRT on loopback and stops when the phone stops.

---

## 6. App changes

### 6.1 New code (`app/src/main/kotlin/app/sparrow/remote/`)

| Class | Job |
| --- | --- |
| `BackendSettings` | Server URL, certificate fingerprint, device id, alias, applied revision. Token in `SecretStore`. |
| `BackendClient` | OkHttp with the pinned certificate. `register`, `report`, `getConfig`, `commandDone`, and `subscribe()` as a Flow that reconnects with `Backoff` from `core`. |
| `BackendSession` | Runs while the app is open or the service is running and the phone is registered. Sends reports on the timer in §3.3, applies config, hands commands to the dispatcher. |
| `CommandDispatcher` | Start, Stop, Restart, Zoom onto existing code (§6.3). |
| `BootReceiver` | After reboot, posts "Tap to re-arm Sparrow". |

New dependencies: `okhttp` and `protobuf-kotlin-lite`. Code is generated in a small JVM module
`:protocol`, together with the `Profile` mapper and its tests.

### 6.2 Changes to existing code

* `Profile`: add `managed: Boolean = false`. Existing JSON still loads.
* `ProfileRepository`: `applyManaged(profile)` replaces the managed profile (passphrase to
  `SecretStore`) and selects it. Settings shows a managed profile read-only.
* `StreamController`: expose the latest link sample and position so reports can read them.
* `StreamPipeline`: keep the latest `LinkSample` and fix in fields the controller can read.
* `StreamingService`: a `STANDBY` mode (§6.4) and an `ACTION_RESTART`.
* `MainActivity`: handle `sparrow://enroll` links; arm standby on open when registered.
* New **Backend** screen from the menu: paste link or pick a QR image (reusing the existing
  `srt://` paste and QR code), connection state, alias, Unregister.

### 6.3 Commands on the phone

| Command | Action | Fails when |
| --- | --- | --- |
| Start | Start the pipeline in the running standby service | Not armed (the phone then shows a "tap to go live" notification) |
| Stop | Stop the pipeline; stay in standby | Not streaming |
| Restart | Stop and start, picking up new config | Not streaming |
| Zoom | `StreamController.setZoom(value)`, clamped to the camera range | Never; replies with the applied value |

Each applied command shows a short banner on the phone and writes a log line.

### 6.4 Standby

* Armed right after registration, and again each time the app opens. Arming starts
  `StreamingService` while the app is visible, with the camera and location service types, which
  Android requires for later camera use.
* In standby the camera is closed. The service keeps the `Subscribe` stream open and shows an
  ongoing notification "Sparrow standby: can be started remotely" with a Disarm action.
* After a reboot, Android 15 does not allow a camera service to start from boot. `BootReceiver`
  posts a notification; one tap opens the app, which re-arms.
* **Check first:** that the camera opens after hours in standby with the screen off, and that the
  stream survives Doze without a wake lock. If not, standby holds a partial wake lock.

---

## 7. Milestones

| # | Content | Effort |
| --- | --- | --- |
| 0 | Checks: gosrt with the phone's SRT library and a passphrase, byte-exact pass-through, standby camera after hours with the screen off | 3–4 days |
| 1 | `proto/`, `:protocol`, sparrowd with PostgreSQL, enrollment link, Register, Report, Subscribe, Login, Devices and Add phone pages, Backend screen in the app | 2 weeks |
| 2 | Config form and apply, Start / Stop / Restart / Zoom, standby, boot re-arm | 1.5 weeks |
| 3 | Ingest, hub, SRT read, MediaMTX feed, viewer, RTSP address on the page | 2 weeks |
| 4 | SRT push and RTMP push outputs | 1 week |

About 7 weeks in total.

Done when

* **1:** A phone registers from a pasted link and shows up on the Devices page within 5 s. Removing
  it on the page stops its reports.
* **2:** A config saved on the page is applied by the phone. A registered phone is in standby
  without user action, and Start from the page works with the screen off.
* **3:** An SRT reader gets TS packets identical to what the phone sent, and `St0601Decoder` reads
  the same KLV. An NVR records the RTSP address. The viewer shows video within 3 s of going live.
* **4:** SRT push delivers identical packets to a remote listener; RTMP push plays on a test server.

---

## 8. Tests

* **Proto and mapper:** round trip with every field set, in `:protocol`.
* **App:** Robolectric tests for the dispatcher, `applyManaged`, and the standby state changes.
* **Server:** handler tests against PostgreSQL in Testcontainers, skipped without Docker.
* **Media:** a `fakephone` command publishes a recorded `TsMuxer` stream; the test compares the
  packets from every byte-exact output with the input.

---

## 9. Left out of the first version

| Item | Why it can wait | Where it would fit |
| --- | --- | --- |
| Device certificates (PKI), attestation | Bearer token over pinned TLS is enough to start | Replaces the token in `BackendClient` and the api middleware |
| ONVIF | RTSP address works with most NVR/VMS | New `internal/onvif` using the same RTSP paths |
| Several users and roles | One admin is enough on-site | Users table, role check in `web` |
| Per-reader accounts | One read credential per site | MediaMTX HTTP auth via sparrowd |
| Telemetry buffering offline | Status is current-state only | Spool file plus WorkManager |
| UDP multicast output, recording | Not asked for yet | New subscriber types on the hub |
| Admin approval of new phones | Enrollment link is approved by design | Pending state on `devices` |
| Bitrate and keyframe commands | Not needed for a first test | Two more `Command` kinds |
