# Sparrow backend: plan (v2, Go + protobuf, on-prem)

An optional backend for Sparrow. Phones register with it, report config and telemetry, and take
commands (start, stop, set config, zoom) from a web service. The backend also gives a basic live
viewer and redistributes video to NVR/VMS and other consumers. It targets on-prem installs first.

With no backend configured, the app behaves exactly as it does today. Everything here is opt-in.

Status: **proposal, not implemented.** Items marked *(decide)* need an answer before the phase
that depends on them. v1 of this plan proposed a Kotlin/Ktor server with JSON; v2 replaces it with
Go and protobuf.

---

## 1. Scope

In scope for the first release

1. **Registration.** A phone enrolls with a QR code or with a server URL plus admin approval.
2. **Config.** The web service holds the desired config per device; the phone applies it and
   reports back whether it was applied or rejected.
3. **Telemetry.** Link stats, health, session events, and (opt-in) position and attitude.
4. **Commands.** Start, stop, restart, select profile, zoom, bitrate, keyframe, upload logs.
5. **Viewer.** Live video in the browser, with a status panel and a map.
6. **Distribution.** NVR/VMS and other consumers pull each phone's stream from a stable URL
   (RTSP, SRT, HLS). Push outputs follow in a later phase.

Later (the design leaves room, nothing is built now)

* Recording on the server, ONVIF, push outputs to remote SRT and UDP multicast, alerts, OIDC,
  device groups and templates, mTLS device certificates, multi-site.

Not planned

* Transcoding or video analytics on the server. The media path relays only.
* Cloud SaaS or multi-tenancy. One install serves one organisation.

---

## 2. Architecture

```
 Phone (Sparrow)                                   On-prem server
 ┌───────────────────────────────┐                 ┌─────────────────────────────────────────────┐
 │ StreamingService / Pipeline   │   SRT (TS+KLV)  │ MediaMTX (media plane)                      │
 │  camera→enc→mux→SrtSender ────┼────────────────▶│  SRT in :8890 ─┬─ RTSP  :8554  ─▶ NVR / VMS │
 │                               │                 │                ├─ SRT read     ─▶ 4609 tools │
 │ remote/                       │                 │                ├─ WebRTC :8889 ─▶ browser    │
 │  DeviceLink (gRPC bidi) ──────┼── HTTP/2+TLS ──▶│                └─ HLS   :8888  ─▶ fallback   │
 │  TelemetrySpool               │                 │      ▲ auth hook, ready hooks, Control API  │
 │  RemoteConfig                 │                 │      │                                      │
 │  CommandDispatcher            │                 │ sparrowd (Go, control plane) :8443          │
 │  SyncWorker (idle, WorkManager)│                │  DeviceService · AdminService · web UI      │
 └───────────────────────────────┘                 │  SQLite                                     │
                                                   └─────────────────────────────────────────────┘
                                                              ▲ HTTPS (Connect protocol)
                                                        Operator browser
```

* **sparrowd** is one Go binary. It holds the API for phones and for the web UI, serves the web
  UI, stores state in SQLite, and steers MediaMTX.
* **MediaMTX** is the media plane. It accepts SRT from phones and re-serves each stream over RTSP,
  SRT, WebRTC and HLS. It asks sparrowd who may publish and read.
* **Channels are independent.** The media path works when the control link is down and vice
  versa. A phone can be managed by the backend while streaming to a third-party receiver.

### 2.1 Why this split

* MediaMTX already does the hard protocol work: RTSP for almost every NVR/VMS, WebRTC for the
  browser, SRT read for KLV-aware tools. Writing that again in Go would cost months.
* sparrowd talks to MediaMTX only through its documented interfaces: HTTP auth, run hooks and the
  Control API. MediaMTX can be upgraded or swapped without touching phones.
* Go fits the control plane well: one static binary, easy on air-gapped hosts, cheap long-lived
  streams per phone, and the SRT ecosystem (MediaMTX, `datarhei/gosrt`) is in Go if we later need
  byte-exact forwarders.

---

## 3. Protocol: protobuf + gRPC (Connect)

### 3.1 Choices

| Concern | Choice | Reason |
| --- | --- | --- |
| Schema | Protobuf, package `sparrow.v1`, in `proto/` at the repo root | One schema for Kotlin, Go and TypeScript |
| Tooling | `buf` for lint, `buf breaking` against `main` in CI, `buf generate` for Go and TS | Catches wire breaks before they ship to phones in the field |
| Server | `connectrpc.com/connect` (connect-go) | One handler speaks gRPC (phones), Connect (browser) and gRPC-Web |
| Android client | `grpc-kotlin` + `grpc-okhttp` + `protobuf-kotlin-lite` | Google's supported Android stack; coroutines and Flow for the bidi stream. Square's Wire is lighter but reports of hung bidi streams make it the second choice |
| Browser client | `@connectrpc/connect-web` + `@bufbuild/protobuf` | Unary and server-streaming over plain HTTP/1.1 or HTTP/2 |
| Validation | `buf.build/go/protovalidate` annotations, enforced in sparrowd | The phone still runs `Profile.validate()`; shared fixtures keep both in line (§3.5) |

The phone's control link is a **gRPC bidirectional stream**. It needs HTTP/2 end to end. On-prem,
phones usually reach sparrowd directly, so this is fine. A site whose proxy only passes HTTP/1.1
gets a WebSocket fallback that carries the same `DeviceMessage`/`ServerMessage` frames (phase 4,
only if a site needs it).

### 3.2 Files

```
proto/
  buf.yaml  buf.gen.yaml
  sparrow/v1/config.proto          Profile and sub-messages, DeviceConfig, DevicePolicy
  sparrow/v1/telemetry.proto       LinkSample, PositionSample, HealthSample, SessionEvent, StatusSnapshot, TelemetryBatch
  sparrow/v1/command.proto         Command (oneof), CommandResult
  sparrow/v1/device.proto          DeviceInfo, CameraInfo
  sparrow/v1/device_service.proto  phone ↔ server
  sparrow/v1/admin_service.proto   web UI ↔ server
  sparrow/v1/media_service.proto   viewer URLs, consumers, outputs
  testdata/profiles/*.json         shared validation fixtures (protojson)
```

### 3.3 Key messages (sketch)

```proto
// config.proto — mirrors app.sparrow.core.config.Profile field for field.
message Profile {
  string id = 1;
  string name = 2;
  Destination destination = 3;
  VideoSettings video = 4;
  AudioSettings audio = 5;
  MetadataSettings metadata = 6;
  AdvancedSettings advanced = 7;
  bool managed = 8;                         // set by the server; locked on the phone
}
message Destination {
  SrtMode mode = 1; string host = 2; uint32 port = 3; string stream_id = 4;
  uint32 latency_ms = 5; string passphrase = 6; uint32 key_length = 7;
}
// VideoSettings, AudioSettings, MetadataSettings, AdvancedSettings: same fields as the Kotlin classes.

message DeviceConfig {
  uint64 revision = 1;                      // bumped on every server-side change
  repeated Profile profiles = 2;            // managed profiles only; local ones stay on the phone
  string active_profile_id = 3;             // empty = keep the user's choice
  DevicePolicy policy = 4;
}
message DevicePolicy {
  TelemetryLevel telemetry_level = 1;       // OFF, LINK_AND_HEALTH (default), FULL (adds position)
  bool lock_managed_profiles = 2;
  bool allow_remote_start = 3;              // remote standby, §5.4
  uint32 telemetry_interval_s = 4;          // default 5
}

// telemetry.proto
message LinkSample {                         // 1 Hz while live, from SrtSender stats
  google.protobuf.Timestamp time = 1; string session_id = 2;
  double send_kbps = 3; uint32 target_kbps = 4; double rtt_ms = 5;
  uint64 packets_sent = 6; uint32 packets_lost = 7; uint32 packets_retransmitted = 8;
  uint32 packets_dropped = 9; uint32 send_buffer_ms = 10;
}
message PositionSample {                     // 1 Hz while live, only at TelemetryLevel FULL
  google.protobuf.Timestamp time = 1; string session_id = 2;
  double lat = 3; double lon = 4; optional double alt_msl = 5; optional float accuracy_m = 6;
  optional double heading_deg = 7; optional double elevation_deg = 8; optional double roll_deg = 9;
  double zoom = 10; optional double hfov_deg = 11; optional double vfov_deg = 12;
  repeated LatLon footprint = 13;            // frame corners from MetadataComposer, when known
}
message HealthSample {                       // every 60 s and on change
  google.protobuf.Timestamp time = 1; uint32 battery_percent = 2; bool charging = 3;
  uint32 thermal_status = 4; NetworkType network = 5; uint64 free_storage_mb = 6;
}
message StatusSnapshot {                     // on change, debounced to 1 per second
  StreamState state = 1;                     // IDLE, STANDBY, STARTING, CONNECTING, LISTENING, LIVE, RECONNECTING, FAILED
  string reason = 2; string active_profile_id = 3; uint64 applied_config_revision = 4;
  double send_kbps = 5; optional double rtt_ms = 6; optional double loss_percent = 7;
  bool gnss_fix = 8; double zoom = 9; string resolution = 10;
  google.protobuf.Timestamp live_since = 11; bool app_foreground = 12;
}
message TelemetryBatch {
  repeated LinkSample link = 1; repeated PositionSample position = 2;
  repeated HealthSample health = 3; repeated SessionEvent events = 4;
  int64 device_elapsed_ms = 5;               // lets the server detect device clock skew
}

// command.proto
message Command {
  string id = 1;
  oneof kind {
    Start start = 10;  Stop stop = 11;  Restart restart = 12;
    SelectProfile select_profile = 13;  SetZoom set_zoom = 14;
    SetBitrate set_bitrate = 15;  RequestKeyFrame key_frame = 16;
    UploadLogs upload_logs = 17;  Ping ping = 18;
  }
}
message CommandResult {
  string id = 1;
  Status status = 2;   // OK, NOT_STREAMING, NOT_IN_STANDBY, OUT_OF_RANGE, REJECTED, UNSUPPORTED, FAILED
  string message = 3;
  StatusSnapshot status_after = 4;
}

// device_service.proto
service DeviceService {
  rpc Register(RegisterRequest) returns (RegisterResponse);         // enrollment token or pending approval
  rpc WaitForApproval(WaitRequest) returns (RegisterResponse);      // long poll while pending
  rpc Connect(stream DeviceMessage) returns (stream ServerMessage); // control link
  rpc UploadTelemetry(TelemetryBatch) returns (UploadResponse);     // spool flush when idle
  rpc UploadLogs(stream LogChunk) returns (UploadResponse);
  rpc GetConfig(GetConfigRequest) returns (DeviceConfig);           // used by the idle worker
}
message DeviceMessage {
  oneof msg { Hello hello = 1; StatusSnapshot status = 2; TelemetryBatch telemetry = 3;
              CommandResult command_result = 4; ConfigAck config_ack = 5; }
}
message ServerMessage {
  oneof msg { Welcome welcome = 1; Command command = 2; DeviceConfig config = 3; }
}
message ConfigAck { uint64 revision = 1; bool applied = 2; repeated string errors = 3; }
```

`Hello` carries `DeviceInfo` (model, Android version, app version, cameras with zoom ranges and
encoder sizes, codecs) and the applied config revision. If the server holds a newer revision, it
sends `DeviceConfig` straight after `Welcome`.

### 3.4 Desired and reported config

The server stores **desired** config with a revision. The phone reports the **applied** revision
in `ConfigAck` and in every `StatusSnapshot`. The web UI shows one of three states per device:
in sync, pending (phone offline or not yet acked), or rejected with the phone's reasons. This
makes "set config" reliable for phones that are offline when the operator saves.

### 3.5 Keeping Kotlin, Go and proto in line

The phone keeps its Kotlin `Profile` (kotlinx-serialization) as its internal model, because the UI,
storage and import/export already use it. A mapper converts at the edge.

* A new JVM module `:protocol` holds the generated Kotlin lite classes and gRPC stubs, plus
  `ProfileMapper` (`Profile ⇄ sparrow.v1.Profile`). It runs as plain JVM unit tests, like `core`.
* A round-trip test fills every field with non-default values and checks `toProto → fromProto`
  equality. A reflection test fails when a field is added to `Profile` but not to the mapper.
* `proto/testdata/profiles/*.json` holds valid and invalid profiles with the expected errors.
  Kotlin tests run them through `Profile.validate()`; Go tests run them through protovalidate.
  A rule change that lands on one side only fails CI on the other.

---

## 4. Server: sparrowd (Go)

### 4.1 Layout

```
backend/
  go.mod                         module github.com/magasb1/sparrow/backend, Go 1.25+
  cmd/sparrowd/main.go           flags/env, wiring, graceful shutdown
  internal/
    gen/                         buf-generated Go (committed)
    device/                      DeviceService handlers: register, connect, uploads
    admin/                       AdminService and MediaService handlers
    hub/                         live device sessions, command routing, watcher fan-out
    store/                       SQLite access (sqlc), migrations (goose)
    telemetry/                   ingest, downsampling, retention
    media/                       MediaMTX: auth hook, ready hooks, Control API reconciliation
    auth/                        users, sessions, device tokens, enrollment tokens
    tlsboot/                     self-signed CA and server cert on first run, SPKI pin
  web/                           React + Vite + connect-web; build output embedded with go:embed
deploy/
  docker-compose.yml             sparrowd + mediamtx (pinned versions)
  mediamtx.yml
  systemd/                       sparrowd.service, mediamtx.service for bare-metal installs
```

### 4.2 Libraries

| Need | Library |
| --- | --- |
| RPC | `connectrpc.com/connect`, `google.golang.org/protobuf`, `buf.build/go/protovalidate` |
| HTTP/2 | `net/http` with TLS; unencrypted HTTP/2 only on loopback for development |
| Database | `modernc.org/sqlite` (pure Go, no cgo), `sqlc` for typed queries, `pressly/goose` migrations |
| Passwords | `golang.org/x/crypto/argon2` |
| Logs and metrics | `log/slog` (JSON), `prometheus/client_golang` on `/metrics` |
| Later: forwarders | `github.com/datarhei/gosrt` (pure Go SRT) |

SQLite keeps the install to one binary and one file. It comfortably handles tens of phones with
1 Hz telemetry. The store sits behind an interface so PostgreSQL can be added if a site outgrows it.

### 4.3 Data model

```
users              id, username, password_hash, role (admin|operator|viewer), created_at
sessions_web       token_hash, user_id, expires_at
devices            id, alias (unique, used in stream paths), name, status (pending|active|revoked),
                   token_hash, pairing_code, registered_at, approved_by, last_seen_at,
                   device_info (proto bytes), app_version
enrollment_tokens  token_hash, label, created_by, expires_at, max_uses, uses, auto_alias_prefix
device_config      device_id, desired_revision, desired (proto bytes), applied_revision,
                   applied_at, rejected_errors
device_secrets     device_id, profile_id, passphrase (AES-GCM, key from SPARROW_KEK or key file)
stream_sessions    id, device_id, started_at, ended_at, end_reason, profile_summary, gateway_path
link_samples       device_id, ts, session_id, send_kbps, target_kbps, rtt_ms, sent, lost, retrans, dropped, snd_buf_ms
position_samples   device_id, ts, session_id, lat, lon, alt, acc, heading, elev, roll, zoom, hfov, vfov, footprint
health_samples     device_id, ts, battery, charging, thermal, network, free_mb
commands           id, device_id, kind, args (proto bytes), issued_by, issued_at, delivered_at,
                   completed_at, status, message
log_uploads        id, device_id, received_at, size, path
consumers          id, name, username, password_hash, allowed_paths (glob), protocols, created_by
```

Retention runs nightly. Raw samples are kept 14 days by default; 1-minute averages are kept a year.

### 4.4 The hub

`hub` holds one entry per connected phone: its send channel, last `StatusSnapshot` and pending
commands. It does three jobs:

1. **Commands.** `AdminService.SendCommand` stores the command, pushes it to the phone's stream and
   waits up to 10 s for `CommandResult`. A phone that is not connected fails at once with
   `DEVICE_OFFLINE`. Commands are never queued, so a zoom issued at 10:00 cannot fire at 10:20.
2. **Config.** Saving config bumps the revision and, if the phone is connected, pushes
   `DeviceConfig` right away. Otherwise it goes out on the next `Connect` or idle `GetConfig`.
3. **Watchers.** `AdminService.WatchDevices` is a server stream to the web UI that sends status
   and telemetry deltas, so the device list and viewer update live without polling.

Keepalive: gRPC pings every 20 s from the phone, so cellular NAT timeouts do not drop the link.
The server marks a phone offline after 45 s with no traffic.

### 4.5 APIs

`DeviceService` is in §3.3. The web-facing services:

```proto
service AdminService {
  rpc Login(LoginRequest) returns (LoginResponse);                       // sets an HttpOnly cookie
  rpc ListDevices(ListDevicesRequest) returns (ListDevicesResponse);
  rpc GetDevice(GetDeviceRequest) returns (Device);
  rpc ApproveDevice(ApproveDeviceRequest) returns (Device);               // pending → active, sets alias
  rpc UpdateDevice(UpdateDeviceRequest) returns (Device);                 // rename, alias
  rpc RevokeDevice(RevokeDeviceRequest) returns (Device);
  rpc CreateEnrollment(CreateEnrollmentRequest) returns (Enrollment);     // token + QR payload + PNG
  rpc GetConfig(GetDeviceConfigRequest) returns (DeviceConfigState);      // desired + applied + errors
  rpc SetConfig(SetDeviceConfigRequest) returns (DeviceConfigState);
  rpc SendCommand(SendCommandRequest) returns (CommandResult);
  rpc ListCommands(ListCommandsRequest) returns (ListCommandsResponse);
  rpc QueryTelemetry(QueryTelemetryRequest) returns (QueryTelemetryResponse); // range, bucket size
  rpc WatchDevices(WatchDevicesRequest) returns (stream DeviceEvent);
  rpc ListLogs / GetLog / ListUsers / CreateUser / ...
}
service MediaService {
  rpc GetViewer(GetViewerRequest) returns (ViewerInfo);       // WHEP + HLS URLs with a 10-min read token
  rpc EnableGateway(EnableGatewayRequest) returns (Device);    // adds the managed gateway profile, §6.2
  rpc ListStreams(ListStreamsRequest) returns (ListStreamsResponse); // per alias: live, readers, URLs
  rpc CreateConsumer / ListConsumers / DeleteConsumer (...);   // NVR/VMS credentials, §6.3
}
```

MediaMTX-facing endpoints are plain HTTP on a loopback or internal listener, guarded by a shared
secret header: `POST /mtx/auth`, `POST /mtx/hooks/ready`, `POST /mtx/hooks/notready`.

### 4.6 Web UI

React + TypeScript + Vite, talking to sparrowd through connect-web with the generated TS clients.
The build is embedded in the binary, so there is nothing to deploy separately.

First release screens:

* **Devices.** List with state, profile, bitrate, battery, last seen, config sync state. Pending
  registrations with an Approve button that shows the phone's pairing code.
* **Device.** Live viewer, status panel, link chart for the last 15 minutes, map with position and
  frame footprint at `FULL`, controls (Start, Stop, Restart, zoom slider within the reported range,
  bitrate, keyframe, profile picker), command history, logs.
* **Config.** Profile editor generated from the same fields as the app's Settings, validated by
  protovalidate on save, with per-device desired vs applied state.
* **Streams.** One row per alias with copyable RTSP, SRT and HLS URLs and live reader count.
* **Consumers, Users, Enrollment.** Credentials for NVR/VMS, local users, QR codes.

---

## 5. App changes

### 5.1 New code (`app/src/main/kotlin/app/sparrow/remote/`)

| Class | Job |
| --- | --- |
| `BackendSettings` | Server URL, device id, alias, server certificate pin, telemetry level, applied revision. Token in `SecretStore` under `backend-token`. |
| `DeviceLink` | gRPC channel (OkHttp transport, TLS with the pinned certificate). Runs `Connect` as a Flow. Reconnects with `Backoff` from `core`. Sends `Hello`, status, telemetry, results; emits incoming commands and config. |
| `BackendSession` | Process-wide owner. Reference-counted holders: the visible activity, the streaming service, and remote standby. Link is up while any holder exists. |
| `TelemetryCollector` | Observes `StreamController` (status, new link-sample and position flows) and battery, thermal and network. Builds a `TelemetryBatch` every 5 s, filtered by `TelemetryLevel`. |
| `TelemetrySpool` | Length-prefixed protobuf records in `filesDir/telemetry/`, capped at 20 MB, oldest dropped first. Every batch is written here first, then drained over the link or by the worker. |
| `RemoteConfig` | Applies `DeviceConfig` through a new `ProfileRepository.applyManaged()`, validates first, answers with `ConfigAck`. |
| `CommandDispatcher` | Maps `Command` to existing APIs (§5.3). |
| `SyncWorker` | WorkManager, every 15 min with network: drain spool, `GetConfig` if the revision moved, pending log upload. Also one run right after a stream stops. |

New dependencies: `grpc-okhttp`, `grpc-kotlin-stub`, `protobuf-kotlin-lite`, `androidx.work`.
They add roughly 2 MB to the APK.

### 5.2 Hooks in existing code

* `StreamController`: add a `linkSamples` shared flow, a `position` state flow at 1 Hz, and a
  `pipelineCommands` flow (`SetBitrate`, `KeyFrame`, `Restart`) that `StreamingService` collects
  the same way it already collects `zoom`.
* `StreamPipeline`: `setBitrate(kbps)` that also caps ABR, `requestKeyFrame()`, publish
  `LinkSample` from `onStats`, publish position and footprint from `writeKlv` at 1 Hz.
* `StreamingService`: a restart path that does not count against the 5-failures-a-minute budget;
  a `STANDBY` mode (§5.4).
* `ProfileRepository`: `applyManaged(config)` replaces all managed profiles, keeps local ones,
  stores passphrases in `SecretStore`, selects `active_profile_id` when set. Managed profiles are
  read-only in Settings when `lock_managed_profiles` is set.
* `Profile`: one new field `managed: Boolean = false`. Old JSON still loads.
* `MainActivity`: holds `BackendSession` while started; handles `sparrow://enroll?...` links.

### 5.3 Command mapping

| Command | On the phone | When it cannot run |
| --- | --- | --- |
| Start | In standby: start the pipeline in the running service. App on screen: the existing permission and start path. | `NOT_IN_STANDBY` plus a notification asking the user to go live |
| Stop | `StreamingService.stop()`; standby stays armed if it was | `NOT_STREAMING` |
| Restart | `pipelineCommands.emit(Restart)` | `NOT_STREAMING` |
| SelectProfile | `profiles.select(id)`, restart if live and asked | `REJECTED` for an unknown id |
| SetZoom | `StreamController.setZoom(clamped)`; the UI follows | `OUT_OF_RANGE` returns the applied value |
| SetBitrate | `pipelineCommands.emit(SetBitrate)` | `NOT_STREAMING` |
| RequestKeyFrame | `pipelineCommands.emit(KeyFrame)` | `NOT_STREAMING` |
| UploadLogs | `AppLog.exportTo` (redacted) → `UploadLogs` stream | `FAILED` with reason |

Every applied command shows a short banner on the phone and writes an `AppLog` line, so the person
holding the phone knows the stream was changed remotely.

### 5.4 Remote start: standby mode

Android does not let a foreground service started from the background open the camera (API 30+).
So "start from the web" needs the service already running in the foreground.

* The user taps **Remote standby** on the main screen (or it is the default in a managed profile
  with `allow_remote_start`). That starts `StreamingService` while the app is visible, with the
  camera, location and microphone service types, and an ongoing "Standby: waiting for operator"
  notification with a Disarm action.
* In standby the service holds the control link and a partial wake lock only. The camera is closed
  and nothing is encoded, so battery use is small.
* A Start command opens the camera and starts the pipeline. Stop returns to standby.
* **Phase 0 spike:** confirm on target devices (Android 12–16) that a camera-type service started
  while visible can open the camera hours later with the screen off, and that its network survives
  Doze. If not, the fallback is to keep the camera open at a low frame rate in standby.
* Without standby, Start from the web posts a high-priority notification that opens the app and
  starts the stream with one tap.

### 5.5 Registration

Two ways, both supported:

1. **QR enrollment.** The admin creates an enrollment in the web UI. The QR holds
   `sparrow://enroll?u=https://host:8443&t=<token>&pin=<sha256 of the server key>`. The phone
   scans it (system camera app, or QR from image as today), calls `Register`, and is active at once.
2. **URL and approval.** The user types the server URL. The phone calls `Register` without a token,
   shows a 6-digit pairing code, and waits in `WaitForApproval`. The admin checks the code and
   approves in the web UI. The phone confirms the server certificate fingerprint on first contact.

`RegisterResponse` returns the device id, a random 32-byte token (stored hashed on the server and
in `SecretStore` on the phone), the alias, and the server time.

### 5.6 UI

* **Backend screen** from the menu: register (scan QR, paste link, or URL + pairing code),
  connection state, last sync, telemetry level with a plain description of what each sends,
  Unregister (wipes token, managed profiles and the spool).
* **Main screen:** small link chip (connected, offline, standby), standby toggle when allowed,
  banner for remote commands.
* **Profiles and Settings:** lock badge and read-only fields on managed profiles, "Duplicate as
  local" to edit a copy.
* README NFR-5 line becomes "no telemetry unless a backend is registered and enabled".

---

## 6. Media: viewer and distribution

### 6.1 MediaMTX setup

```yaml
authMethod: http
authHTTPAddress: http://127.0.0.1:9100/mtx/auth     # sparrowd internal listener
authHTTPExclude: [{action: api}, {action: metrics}]
api: yes
apiAddress: 127.0.0.1:9997
srt: yes          # :8890, phones publish, SRT readers
rtsp: yes         # :8554, NVR/VMS
webrtc: yes       # :8889 (+ ICE UDP), browser viewer via WHEP
hls: yes          # :8888, fallback viewer
pathDefaults:
  runOnReady: /usr/local/bin/sparrow-hook ready      # posts MTX_PATH, MTX_SOURCE_ID to sparrowd
  runOnNotReady: /usr/local/bin/sparrow-hook notready
paths:
  '~^cam/[a-z0-9-]+$': {}
```

`sparrow-hook` is a tiny helper built from the same Go module, so the install needs no curl. Hook
names differ between MediaMTX releases (`runOnReady` vs `runOnAvailable`); the pinned version
decides. sparrowd also reconciles against `GET /v3/paths/list` every 30 s in case a hook is lost.

### 6.2 Phones publishing to the server

`MediaService.EnableGateway` gives the phone a managed profile "On-prem gateway": caller mode to
the server's SRT port, stream ID `publish:cam/<alias>:<device-id>:<publish-secret>`, and the site's
SRT passphrase. MediaMTX asks `/mtx/auth`; sparrowd checks that the device is active and the secret
matches. Revoking a device blocks its next publish and kicks the current one through the Control API.

Paths use the device **alias**, not its id. When a phone is replaced, the new one takes the alias
and every NVR keeps working without reconfiguration.

### 6.3 Consumers (NVR, VMS, 4609 tools)

| Consumer | URL | Notes |
| --- | --- | --- |
| NVR/VMS (Milestone, Genetec, Nx Witness, Frigate, Blue Iris, ...) | `rtsp://user:pass@host:8554/cam/<alias>` | H.264 recommended; H.265 where the VMS supports it. KLV is not used by these. |
| KLV-aware tools (4609 players, ISR exploitation) | `srt://host:8890?streamid=read:cam/<alias>:user:pass` | MediaMTX keeps the KLV track on SRT read. RTSP re-wraps KLV as RFC 6597, which FFmpeg does not decode. |
| Browser viewer | WHEP via the web UI | Video only; the web UI draws telemetry beside it |
| Simple players | HLS `http://host:8888/cam/<alias>/index.m3u8` | Several seconds of latency |

Each consumer gets its own credentials from the web UI, limited to path globs and protocols.
`/mtx/auth` checks them with `action=read`.

### 6.4 Later: push outputs and byte-exact forwarding

Some sites need sparrowd to push: SRT caller to a remote listener, or MPEG-TS over UDP multicast
(common for 4609 networks). MediaMTX does not push to arbitrary targets. Plan: a `forward` package
in sparrowd using `datarhei/gosrt` reads each stream from MediaMTX over SRT and writes the TS
packets unchanged to the configured outputs. If the KLV spike shows that MediaMTX alters KLV, the
same package can take the phone's SRT directly and feed MediaMTX for viewing only, keeping the
4609 path byte-exact.

### 6.5 Risks

| Risk | Plan |
| --- | --- |
| KLV through MediaMTX | Phase 0 spike: publish a `TsMuxer` stream, read it back over SRT, compare KLV byte-exact with `TsDemuxer` and `St0601Decoder`, sync and async carriage. Keep it as an opt-in integration test. |
| H.265 in browsers | Viewer falls back to HLS; the web UI suggests H.264 for gateway profiles. |
| Credentials in the SRT stream ID | The stream ID crosses the network in the clear; the media is encrypted by the SRT passphrase. Per-device publish secrets can be rotated or revoked. |
| WebRTC through firewalls | On-prem LAN works directly. Remote viewers need the ICE UDP port open or a TURN server (later). |

---

## 7. Security

* **TLS everywhere.** With no certificate configured, sparrowd creates a private CA and a server
  certificate on first run. The phone pins the key hash from the QR or the first approved contact,
  so self-signed works without trusting a public CA. Sites can supply their own certificate.
* **Device auth.** Bearer token over TLS in phase 1. Later: a Keystore key pair on the phone, a CSR
  at registration, and mTLS with a certificate from the sparrowd CA.
* **Web auth.** Local users with argon2 hashes and HttpOnly session cookies; roles admin, operator
  (commands and config) and viewer (read only). OIDC later.
* **Secrets.** SRT passphrases encrypted at rest on the server and in the Android Keystore on the
  phone; never logged on either side.
* **Telemetry level** is enforced on the phone; position is sent only at `FULL`.
* **Audit.** Every command, config change, approval and revocation stores the user who did it.
* **Air-gapped friendly.** sparrowd makes no outbound calls; the web UI loads no CDN assets.

---

## 8. Repository

```
sparrow/
  proto/        NEW  protobuf schema, buf config, shared fixtures
  protocol/     NEW  Gradle JVM module: generated Kotlin lite + gRPC stubs, ProfileMapper, tests
  core/         unchanged
  app/          + remote/, Backend screen, standby
  backend/      NEW  Go module: sparrowd, sparrow-hook, web/
  deploy/       NEW  docker-compose, mediamtx.yml, systemd units
  docs/
```

One repository keeps the schema, phone and server changes in one pull request, and `buf breaking`
runs against `main` on every change. *(decide: or a separate backend repository that pulls `proto/`
as a buf module.)*

CI jobs: `buf lint` and `buf breaking`; `./gradlew :core:test :protocol:test :app:testDebugUnitTest`;
`go vet`, `go test ./...`, `golangci-lint`; web `npm run build` and type-check; an opt-in
integration job that starts MediaMTX and runs the KLV round trip.

---

## 9. Phases

Estimates assume one developer who knows both stacks.

| Phase | Content | Effort |
| --- | --- | --- |
| 0. Spikes | KLV through MediaMTX; standby camera access and network in Doze on two or three devices; gRPC bidi over cellular with keepalive; buf and Gradle generation working offline | 1 week |
| 1. Register and observe | `proto/`, `:protocol` module, sparrowd skeleton with SQLite and TLS bootstrap, both registration flows, `Connect` stream, status and telemetry with spool, web UI: login, devices, device detail with charts | 3 weeks |
| 2. Control | Desired/applied config, config editor, all commands, standby mode, command history, log upload | 2–3 weeks |
| 3. Media | MediaMTX in compose, auth and hooks, gateway profile, viewer, streams page, consumer credentials for RTSP and SRT | 2–3 weeks |
| 4. Later | Push outputs and UDP multicast, recording, ONVIF, alerts, OIDC, mTLS, groups and templates, WebSocket fallback | per feature |

Acceptance for each phase

* **1:** A phone without a backend streams with no traffic other than SRT (checked with a packet
  capture). A registered phone shows up live in the web UI within 2 s of opening the app. Pulling
  the network mid-stream leaves no gap in telemetry after reconnect.
* **2:** A config saved while the phone is offline applies when it reconnects, and the web UI shows
  "in sync". Zoom from the web changes the stream and the phone's readout within 500 ms on Wi-Fi.
  Start from the web works with the phone in standby and the screen off.
* **3:** An NVR records a phone through the RTSP URL. Replacing the phone and reusing its alias keeps
  the NVR recording without changes. The SRT read URL returns KLV that matches the phone byte for
  byte. The browser shows video within 3 s of the phone going live.

---

## 10. Testing

| Layer | Tests |
| --- | --- |
| Schema | `buf lint`, `buf breaking` against `main` |
| `:protocol` | Mapper round trip, field-coverage check, shared validation fixtures |
| App | Robolectric: dispatcher table, spool cap and corruption, `applyManaged` rules, Backend screen screenshots in `ScreenshotTest` |
| sparrowd | Handler tests with in-memory SQLite; hub tests with a fake phone over a real bidi stream; MediaMTX auth contract test from recorded request bodies; fixtures through protovalidate |
| Integration | Compose stack with MediaMTX, a simulated phone in Go that publishes a recorded TS and speaks `DeviceService`, KLV round trip |

The simulated phone (`backend/cmd/fakephone`) is also useful for load tests and for developing the
web UI without a device.

---

## 11. Open questions *(decide before phase 1)*

1. **Monorepo or separate backend repo?** The plan assumes one repo.
2. **Standby by default?** Should managed phones arm remote standby automatically after the user
   opens the app, or only on an explicit tap?
3. **Must KLV reach consumers byte-exact?** This decides whether a failed MediaMTX spike blocks
   phase 3 or only moves the 4609 path to the Go forwarder.
4. **Which NVR/VMS products must work first?** Some want ONVIF discovery rather than a raw RTSP URL,
   which would pull ONVIF forward from phase 4.
5. **Position telemetry default.** The plan assumes off until an admin sets `FULL`.
6. **Fleet size per site.** Tens of phones fits SQLite; hundreds suggests PostgreSQL from the start.

---

## 12. Sources

* MediaMTX issue 5612, KLV on SRT read versus RTSP: https://github.com/bluenviron/mediamtx/issues/5612
* MediaMTX KLV handling PR 3688: https://github.com/bluenviron/mediamtx/pull/3688
* MediaMTX configuration reference: https://github.com/bluenviron/mediamtx/blob/main/mediamtx.yml
* Connect for Go, streaming and HTTP/2 requirement: https://connectrpc.com/docs/go/streaming
* gRPC Kotlin for Android: https://developers.googleblog.com/en/announcing-grpc-kotlin-10-for-android-and-cloud/
* Android gRPC guide: https://developer.android.com/guide/topics/connectivity/grpc
* Wire bidi streaming issue on Android (Kotlin Slack): https://slack-chats.kotlinlang.org/t/497297/seeking-advice-on-wire-grpc-client-on-android-client-which-u
* gosrt, pure Go SRT: https://github.com/datarhei/gosrt
* Android 11 foreground service camera restriction: https://developer.android.com/about/versions/11/privacy/foreground-services
