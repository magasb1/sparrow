# Sparrow backend: telemetry, remote configuration, hosted SRT gateway

Design and implementation plan for an optional central backend. The app keeps working exactly as
today with no backend configured; everything in this document is additive and opt-in.

Status: **proposal, not implemented.** Decisions marked *(decide)* need an answer before the
phase that depends on them starts.

---

## 1. Goals and non-goals

Goals

1. **Telemetry.** A fleet operator sees every enrolled phone: online/live state, link quality
   (bitrate, RTT, loss), position and attitude, battery/thermal, app version, recent log lines.
2. **Remote configuration.** The operator pushes profiles (destination, video, metadata, ABR) to a
   device or a group of devices; the phone applies them without the user retyping anything.
3. **Hosted SRT gateway (optional).** The backend ships with a MediaMTX instance. Enrolled devices
   can be pointed at it with one click; the operator previews the stream in the console and
   downstream 4609 consumers pull it back out over SRT with KLV intact.
4. **Commands.** While a device is live, the operator can zoom, change bitrate, force a keyframe,
   switch profile, stop, or request logs. Commands work regardless of whether the stream goes to
   the hosted gateway or a third-party receiver; the gateway just gives the operator a picture to
   act on.
5. **Optional.** Nothing leaves the phone unless a backend is enrolled *and* telemetry is enabled
   (NFR-5 "no telemetry" stays true by default). Unenrolling returns the app to its current state.

Non-goals (for now)

* Multi-tenant SaaS. The first version is single-tenant and self-hosted (one operator
  organisation per deployment). The schema reserves room for tenancy but no code path uses it.
* Replacing the app's local profiles. Local profiles stay; managed profiles are added beside them.
* Video processing on the server (transcoding, analytics). MediaMTX relays only.
* Remote *start* from a cold, backgrounded app. Android forbids camera access from a foreground
  service started in the background (API 30+), so remote start only works while Sparrow is on
  screen. See §6.4 for the notification-based fallback.

---

## 2. Architecture overview

```
 Phone (Sparrow app)                         Backend (one Docker Compose stack)
 ┌──────────────────────────────┐            ┌─────────────────────────────────────────┐
 │ StreamingService / Pipeline  │  SRT/TS    │ MediaMTX  ─ SRT in (8890) ─ WebRTC/HLS   │
 │   camera → enc → mux → SRT ──┼───────────▶│           ─ SRT out for 4609 consumers  │
 │                              │            │     │ auth hook / ready hooks             │
 │ BackendSession               │            │     ▼                                   │
 │   ControlChannel (WebSocket)─┼─ wss ─────▶│ sparrow-server (Ktor, Kotlin)           │
 │   TelemetryCollector + spool─┼─ https ───▶│   REST + WebSocket, PostgreSQL           │
 │   RemoteConfigSync           │◀─ config ──│   operator API + static web console     │
 │   CommandDispatcher          │◀─ cmd ─────│                                         │
 │   BackendSyncWorker (idle)   │            │ Postgres   Caddy (TLS)                  │
 └──────────────────────────────┘            └─────────────────────────────────────────┘
                                                        ▲ https
                                               Operator browser (console)
```

Three independent channels, so each can fail or be absent without breaking the others:

| Channel | Direction | Transport | When |
| --- | --- | --- | --- |
| Media | phone → gateway | SRT (existing `SrtSender`) | Only when the active profile points at the gateway |
| Control | both | one WebSocket, JSON messages | While streaming, and while the app is on screen |
| Sync | phone → backend | HTTPS requests from WorkManager | Every 15 min when idle; immediately after a stream ends |

Why one WebSocket rather than MQTT, gRPC or FCM: the app already runs a foreground service while
live, so a plain socket is reliable exactly when commands matter; kotlinx-serialization is already
a dependency; the backend stays a single process (no broker); no Google Play Services dependency.
MQTT would buy QoS and fan-out we do not need at this fleet size. Revisit if the fleet reaches
thousands of devices or commands must reach idle phones.

Why Kotlin/Ktor for the server: `core` is pure JVM and already holds `Profile`, `ProfileCodec`,
`SrtUrl`, validation and the ST 0601 decoder. The server depends on `:core` directly, so the
profile schema, validation rules and the new wire protocol are one set of classes with one set of
tests, and a schema change cannot drift between app and server. Go would sit closer to MediaMTX
but would mean maintaining a second copy of the profile model.

---

## 3. Repository layout

```
sparrow/
  core/      existing; + app.sparrow.core.remote (wire protocol DTOs, shared)
  app/       existing; + app.sparrow.remote (client), UI additions
  server/    NEW  Ktor service, PostgreSQL, Flyway migrations, Dockerfile
  console/   NEW  web console (TypeScript + Vite), built into server resources   [phase 2]
  deploy/    NEW  docker-compose.yml, mediamtx.yml, Caddyfile, .env.example
  docs/      this plan, protocol reference, operator guide
```

`settings.gradle.kts` gains `include(":server")`. The server module uses the Kotlin JVM plugin
only (no Android), so `./gradlew :core:test :server:test` runs on any CI box without the SDK.

---

## 4. Shared wire protocol (`core/src/main/kotlin/app/sparrow/core/remote/`)

All messages are `@Serializable` Kotlin classes, JSON on the wire, `ignoreUnknownKeys = true` so
either side can add fields. A `protocolVersion` integer (start at 1) travels in `hello`; the server
refuses unknown major versions with a clear error the app shows in Settings.

### 4.1 Identity and device description

```kotlin
@Serializable data class DeviceInfo(
    val manufacturer: String, val model: String, val androidSdk: Int,
    val appVersion: String, val appVersionCode: Int,
    val cameras: List<CameraSummary>,          // id, facing, zoomRange, encoderSizes (max only)
    val codecs: List<String>,                  // "H264", "H265"
)
```

### 4.2 Telemetry

```kotlin
@Serializable data class LinkTelemetry(        // one per second while live (from LinkSample)
    val tsUtcMs: Long, val sessionId: String,
    val sendKbps: Double, val targetKbps: Int, val rttMs: Double,
    val packetsSent: Long, val packetsLost: Int, val packetsRetransmitted: Int,
    val packetsDropped: Int, val sendBufferMs: Int,
)
@Serializable data class PositionTelemetry(    // 1 Hz while live, only at telemetry level FULL
    val tsUtcMs: Long, val sessionId: String,
    val lat: Double, val lon: Double, val altMsl: Double?, val ellipsoidHeight: Double?,
    val accuracyM: Float?, val headingDeg: Double?, val elevationDeg: Double?, val rollDeg: Double?,
    val zoom: Double, val hfovDeg: Double?, val vfovDeg: Double?,
)
@Serializable data class HealthTelemetry(      // every 60 s, and on change
    val tsUtcMs: Long, val batteryPercent: Int, val charging: Boolean,
    val thermalStatus: Int,                    // PowerManager.THERMAL_STATUS_*
    val network: String,                       // "wifi" | "cellular" | "ethernet" | "none"
    val freeStorageMb: Long,
)
@Serializable data class SessionEvent(         // start / stop / failure of a stream session
    val tsUtcMs: Long, val sessionId: String, val kind: Kind,
    val profileSummary: ProfileSummary,        // name, codec, resolution, fps, bitrate, destination host:port (no secrets)
    val reason: String? = null,
) { enum class Kind { STARTED, LIVE, RECONNECTING, STOPPED, FAILED } }
@Serializable data class StatusSnapshot(       // debounced copy of StreamStatus + extras
    val tsUtcMs: Long, val link: String, val running: Boolean, val profileId: String?, val profileName: String,
    val sendKbps: Double, val rttMs: Double?, val lossPercent: Double?, val gnssFix: Boolean,
    val zoom: Double?, val resolution: String, val liveSinceUtcMs: Long?,
    val configRevision: Long, val appForeground: Boolean,
)
@Serializable data class TelemetryBatch(
    val link: List<LinkTelemetry> = emptyList(), val position: List<PositionTelemetry> = emptyList(),
    val health: List<HealthTelemetry> = emptyList(), val events: List<SessionEvent> = emptyList(),
)
```

Timestamps are device UTC; every batch also carries `deviceElapsedMs` and the server stores its own
receive time, so clock skew is detectable and correctable later.

### 4.3 Remote configuration

```kotlin
@Serializable data class ConfigSnapshot(
    val revision: Long,
    val bundle: ProfileBundle,                 // existing class; managed profiles only, secrets stripped
    val secrets: Map<String, ProfileSecrets>,  // profileId → passphrase; TLS-only, stored via SecretStore
    val activeProfileId: String? = null,       // null = leave the user's choice alone
    val policy: DevicePolicy = DevicePolicy(),
)
@Serializable data class ProfileSecrets(val passphrase: String = "")
@Serializable data class DevicePolicy(
    val telemetryLevel: TelemetryLevel = TelemetryLevel.LINK_AND_HEALTH,
    val lockManagedProfiles: Boolean = true,   // false lets the user edit managed profiles locally
    val restartOnConfigChange: Boolean = false,
)
enum class TelemetryLevel { OFF, LINK_AND_HEALTH, FULL }  // FULL adds position/attitude
```

`Profile` gains one field, `val managed: Boolean = false`. The default keeps existing JSON valid.

### 4.4 Commands

```kotlin
@Serializable sealed class Command {            // discriminator "type"
    @Serializable @SerialName("camera.zoom")     data class Zoom(val ratio: Double) : Command()
    @Serializable @SerialName("encoder.bitrate") data class Bitrate(val kbps: Int) : Command()
    @Serializable @SerialName("encoder.keyframe")data object KeyFrame : Command()
    @Serializable @SerialName("stream.start")    data object Start : Command()
    @Serializable @SerialName("stream.stop")     data object Stop : Command()
    @Serializable @SerialName("stream.restart")  data object Restart : Command()
    @Serializable @SerialName("profile.select")  data class SelectProfile(val profileId: String, val restart: Boolean = false) : Command()
    @Serializable @SerialName("config.sync")     data object SyncConfig : Command()
    @Serializable @SerialName("logs.upload")     data class UploadLogs(val maxBytes: Int = 2_000_000) : Command()
    @Serializable @SerialName("device.ping")     data object Ping : Command()
}
@Serializable data class CommandResult(
    val id: String, val status: Status, val message: String? = null, val applied: JsonElement? = null,
) { enum class Status { OK, NOT_STREAMING, APP_IN_BACKGROUND, OUT_OF_RANGE, UNSUPPORTED, FAILED } }
```

### 4.5 WebSocket envelope

```json
{ "t": "hello" | "status" | "telemetry" | "cmd" | "cmd.result" | "config.changed" | "ping" | "pong",
  "id": "uuid (commands/results)", "ts": 1760000000000, "data": { ... } }
```

| Message | Direction | Payload |
| --- | --- | --- |
| `hello` | ↑ | `protocolVersion`, `DeviceInfo`, `configRevision`, initial `StatusSnapshot` |
| `status` | ↑ | `StatusSnapshot`, on change, debounced to ≤ 1/s |
| `telemetry` | ↑ | `TelemetryBatch`, every 5 s while live |
| `cmd` | ↓ | `Command` |
| `cmd.result` | ↑ | `CommandResult` |
| `config.changed` | ↓ | `{ revision }`; device then does `GET /v1/device/config` |
| `ping`/`pong` | both | keepalive every 25 s; two missed pongs = reconnect |

---

## 5. Server (`server/`)

### 5.1 Stack

| Concern | Choice | Notes |
| --- | --- | --- |
| HTTP/WS | Ktor 3 (Netty) | ContentNegotiation + kotlinx-json, WebSockets, Auth (bearer), CallLogging, Micrometer |
| DB | PostgreSQL 16, HikariCP, Exposed DSL | Flyway migrations in `server/src/main/resources/db/migration` |
| Secrets at rest | AES-GCM with a KEK from `SPARROW_KEK` env | Device tokens stored as SHA-256; passphrases encrypted |
| Metrics | Prometheus endpoint `/metrics` | devices online, live sessions, commands/min |
| Build | `./gradlew :server:installDist`, multi-stage Dockerfile | Image `sparrow-server` |
| Tests | JUnit + Ktor test host; Testcontainers Postgres, skipped when Docker is absent | Same skip pattern as `FfmpegInteropTest` |

Configuration is environment variables only (`SPARROW_DB_URL`, `SPARROW_KEK`,
`SPARROW_OPERATOR_TOKENS`, `SPARROW_PUBLIC_URL`, `SPARROW_MTX_API_URL`, `SPARROW_MTX_PUBLIC_HOST`,
`SPARROW_MTX_SRT_PASSPHRASE`).

### 5.2 Data model

```
devices            id uuid, name, token_hash, enrolled_at, last_seen_at, online bool, app_version,
                   manufacturer, model, android_sdk, device_info jsonb, group_id null, revoked_at null
enrollment_tokens  token_hash, created_by, expires_at, used_by_device null, label, group_id null
device_config      device_id pk, revision bigint, bundle jsonb, active_profile_id, policy jsonb,
                   updated_at, updated_by
device_secrets     device_id, profile_id, passphrase_enc bytea
groups             id, name                                   [phase 4: group config templates]
sessions           id uuid, device_id, started_at, ended_at null, end_reason, profile_summary jsonb,
                   gateway_path null, gateway_live bool
link_samples       ts, device_id, session_id, send_kbps, target_kbps, rtt_ms, pkt_sent, pkt_lost,
                   pkt_retrans, pkt_dropped, snd_buf_ms            (index (device_id, ts))
position_samples   ts, device_id, session_id, lat, lon, alt_msl, ell_h, acc_m, heading, elev, roll,
                   zoom, hfov, vfov
health_samples     ts, device_id, battery, charging, thermal, network, free_mb
commands           id uuid, device_id, type, args jsonb, issued_by, issued_at, delivered_at null,
                   completed_at null, status, message, applied jsonb
log_uploads        id, device_id, received_at, bytes, storage_path
gateway_streams    path pk, device_id, publish_user, publish_pass_enc, created_at
```

Retention job (daily): link/position/health samples older than `SPARROW_RETENTION_DAYS`
(default 30) are deleted; sessions and commands are kept for a year. Volume estimate: one live
device produces 86k link rows/day (~10 MB); 50 devices streaming 8 h/day for 30 days is ~2 GB,
which plain Postgres handles without TimescaleDB.

### 5.3 HTTP API

Device-facing, `Authorization: Bearer <deviceToken>` (except enroll):

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/v1/enroll` | `{ enrollmentToken, device: DeviceInfo, name? }` → `{ deviceId, deviceToken, serverTimeMs, wsUrl }` |
| GET | `/v1/device/config` | `ConfigSnapshot`; honours `If-None-Match: <revision>` → 304 |
| POST | `/v1/device/telemetry` | `TelemetryBatch` (spool flush from WorkManager) |
| POST | `/v1/device/logs` | gzip body, ≤ 4 MB; redacted on device before upload |
| POST | `/v1/device/token/rotate` | returns a new token, old one valid for 5 min |
| DELETE | `/v1/device` | self-unenroll; server deletes token and secrets, keeps history |
| WS | `/v1/device/ws` | control channel (§4.5) |

Operator-facing, `Authorization: Bearer <operatorToken>`:

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/v1/enrollments` | create enrollment token (+ `sparrow://enroll?...` URL and QR PNG) |
| GET | `/v1/devices` | list with online/live state and last status |
| GET | `/v1/devices/{id}` | detail: info, status, config revision, current session |
| PUT | `/v1/devices/{id}/config` | replace managed bundle/secrets/policy; bumps revision; pushes `config.changed` |
| PATCH | `/v1/devices/{id}` | rename, group |
| DELETE | `/v1/devices/{id}` | revoke (token invalid, WS closed) |
| POST | `/v1/devices/{id}/commands` | body `Command`; `?wait=5s` returns the `CommandResult` or 202 |
| GET | `/v1/devices/{id}/commands` | history |
| GET | `/v1/devices/{id}/telemetry/{link|position|health}?from&to&step` | time series, optional bucketing |
| GET | `/v1/devices/{id}/sessions` | session list |
| GET | `/v1/devices/{id}/logs` / `/logs/{uploadId}` | uploaded logs |
| POST | `/v1/devices/{id}/gateway` | enable hosted gateway: provisions path + credentials, adds the managed profile |
| GET | `/v1/devices/{id}/stream` | playback: WHEP URL, HLS URL, SRT read URL with a 10-min read token |
| WS | `/v1/console/ws` | live status/telemetry fan-out for the console |

MediaMTX-facing (internal network only; `SPARROW_MTX_HOOK_SECRET` header):

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/v1/mtx/auth` | MediaMTX `authMethod: http` body `{user,password,ip,action,path,protocol,id,query}` → 200/401 |
| POST | `/v1/mtx/hooks/ready`, `/notready` | from `runOnReady`/`runOnNotReady` (`runOnAvailable`/`runOnUnavailable` on current main): `path`, `sourceId`, `query` |

### 5.4 Operator authentication

Phase 1: static bearer tokens from `SPARROW_OPERATOR_TOKENS` (comma-separated, each
`label:token`). Phase 4: OIDC (any provider) with roles `viewer` / `operator` / `admin`.
*(decide: is static-token login acceptable for the first deployments?)*

### 5.5 Command execution on the server

1. Insert `commands` row, status `PENDING`.
2. If the device's WS is connected, send `cmd`; set `delivered_at`. Otherwise fail fast with
   `DEVICE_OFFLINE` (commands are not queued: a zoom issued at 10:00 must not fire at 10:20).
3. On `cmd.result`, update the row and complete any waiting HTTP request. Timeout 10 s → `TIMEOUT`.

---

## 6. App changes (`app/src/main/kotlin/app/sparrow/remote/`)

### 6.1 Components

| Class | Responsibility |
| --- | --- |
| `BackendConfig` | Server URL, device id, enabled flags, telemetry level, config revision, last sync. SharedPreferences `backend`; token in `SecretStore` under key `backend-token`. |
| `BackendClient` | HTTP via OkHttp + kotlinx-serialization: enroll, config, telemetry, logs, rotate. Retries with `Backoff` from `core`. |
| `ControlChannel` | OkHttp WebSocket; `hello` on open; ping/pong; reconnect with `Backoff`; exposes `incoming: Flow<Envelope>` and `send()`. |
| `BackendSession` | Process-wide object (like `StreamController`). Reference-counted *holders*: `MainActivity` (onStart/onStop) and `StreamingService` (start/stop). Connected while holders > 0 and backend enabled; disconnected otherwise. Owns the collector, sync and dispatcher below. |
| `TelemetryCollector` | Observes `StreamController.status`, `SrtSender` stats (via a new `StreamController.linkSamples` flow), `SensorHub` (via a new `StreamController.position` flow at 1 Hz), battery/thermal/network. Builds `TelemetryBatch` every 5 s. Respects `TelemetryLevel`. |
| `TelemetrySpool` | Disk-backed JSONL queue in `filesDir/telemetry/`, capped at 20 MB (oldest dropped). Batches go to the spool first; the channel or the worker drains it. Nothing is lost when the link drops mid-stream. |
| `RemoteConfigSync` | `GET /v1/device/config` on connect, on `config.changed`, and from the worker. Applies via `ProfileRepository.applyManaged(snapshot)` (new). |
| `CommandDispatcher` | Maps `Command` → existing APIs (table below), returns `CommandResult`. |
| `BackendSyncWorker` | WorkManager periodic (15 min, network required): drain spool over HTTPS, check config revision, upload pending logs. A one-shot expedited run is enqueued when a stream stops. |

New dependencies: `com.squareup.okhttp3:okhttp` (HTTP + WebSocket), `androidx.work:work-runtime-ktx`.
Both are small and well-maintained; no Play Services.

### 6.2 Hooks into existing code

* `StreamController`: add `linkSamples: SharedFlow<LinkSample>`, `position: StateFlow<PositionTelemetry?>`,
  and a `pipelineCommands: MutableSharedFlow<PipelineCommand>` (`SetBitrate`, `RequestKeyFrame`,
  `Restart`) that `StreamingService` collects like it already collects `zoom`.
* `StreamPipeline`: `setBitrate(kbps)` (calls `encoder.setBitrate` and caps `abr.maxKbps`), expose
  `requestKeyFrame()`. Publish `LinkSample` to the controller from `onStats`.
* `StreamingService`: `restartPipeline()` that reuses the restart path in `onPipelineFailure`
  without counting against the 5-per-minute failure budget; `ACTION_RESTART` intent.
* `ProfileRepository`: `applyManaged(snapshot)` replaces all `managed = true` profiles with the
  snapshot's set (secrets into `SecretStore`), leaves local ones untouched, selects
  `activeProfileId` when given. `save()` refuses edits to managed profiles when
  `policy.lockManagedProfiles`.
* `AppLog`: unchanged; uploads pass through `exportTo`, so redaction still applies.
* `SparrowApp.onCreate`: `BackendSession.init(this)`; schedule the worker if enrolled.
* `MainActivity`: `BackendSession.acquire("ui")` in `onStart`, `release` in `onStop`; handle
  `sparrow://enroll?...` intents (QR scanned by the system camera app) via an intent filter.
* Manifest: add `WorkManager` nothing extra; `android:networkSecurityConfig` allowing cleartext
  only for `localhost`/`10.0.2.2` in debug builds. Release builds are HTTPS-only.

### 6.3 Command mapping

| Command | Implementation | Result when not possible |
| --- | --- | --- |
| `camera.zoom` | `StreamController.setZoom(ratio.coerceIn(zoomRange))`; UI follows automatically because it observes the same flow | `OUT_OF_RANGE` (applied value returned), `NOT_STREAMING` if idle and no idle camera |
| `encoder.bitrate` | `pipelineCommands.emit(SetBitrate)`; also caps ABR max so ABR cannot raise it back | `NOT_STREAMING` |
| `encoder.keyframe` | `pipelineCommands.emit(RequestKeyFrame)` | `NOT_STREAMING` |
| `stream.stop` | `StreamingService.stop(context)` | `NOT_STREAMING` |
| `stream.restart` | `pipelineCommands.emit(Restart)` | `NOT_STREAMING` |
| `stream.start` | If the activity is resumed: `MainActivity` runs its existing permission + start path. Otherwise post a high-priority notification "Operator asks you to go live" whose action opens the activity and starts; return `APP_IN_BACKGROUND` | — |
| `profile.select` | `profiles.select(id)`; `restart = true` → `Restart` when live | `FAILED` if the id is unknown |
| `config.sync` | `RemoteConfigSync.syncNow()` | — |
| `logs.upload` | `AppLog.exportTo` → gzip → `POST /v1/device/logs` | `FAILED` with message |
| `device.ping` | `OK` with `StatusSnapshot` in `applied` | — |

Every applied command shows a short on-screen banner ("Operator set zoom 2.0×") and an `AppLog`
line, so the person holding the phone always knows the stream changed under them.

### 6.4 Lifecycle and Android constraints

* **Live:** the foreground service holds a `BackendSession` reference, so the WebSocket stays up for
  the whole stream, screen off included, under the existing wake lock.
* **App on screen, idle:** the activity holds the reference; commands such as `stream.start`,
  `profile.select`, `config.sync` work.
* **Idle, backgrounded:** no socket. Doze would kill it anyway and a persistent `dataSync` service
  is limited to 6 h/day on API 34+. `BackendSyncWorker` keeps config fresh and telemetry flushed.
  The console shows the device as "reachable: no (last seen hh:mm)".
* **Remote start** needs the app in the foreground because camera access from a background-started
  foreground service is denied on API 30+. The notification fallback above is the honest version
  of "remote start".
* **Battery:** WebSocket keepalive every 25 s costs little against camera + encoder + SRT. Telemetry
  is batched (one message per 5 s) and the spool means no retry storms.
* **OEM task killers** already affect the stream itself; the backend session rides along and needs
  nothing extra.

### 6.5 UI

* New `Screen.Backend` reached from the main menu: server URL; "Enroll" via pasted
  `sparrow://enroll?server=…&token=…` URL or QR from image (reuse the existing `srt://` paste/QR
  code paths, generalised); connection state and last sync time; telemetry level (Off / Link &
  health / Full incl. position) with a plain-language description of what each sends; "Unenroll"
  (deletes token, managed profiles and the spool).
* Main screen: a small cloud chip in `InfoChips` (connected / syncing / offline); banner on applied
  commands.
* Profiles screen: lock badge on managed profiles; Settings shows "Managed by <server>. Duplicate
  as local profile to edit" when `lockManagedProfiles` is set.
* Onboarding: one sentence that the app sends nothing anywhere unless a backend is enrolled.
* README: NFR-5 row becomes "no telemetry unless a backend is enrolled and enabled".

---

## 7. Hosted SRT gateway (MediaMTX)

### 7.1 Topology

```
device ── SRT publish ──▶ MediaMTX :8890 ── SRT read ──▶ 4609 consumers (KLV intact)
                                        ── WebRTC/WHEP :8889 ──▶ console preview (video only)
                                        ── HLS :8888 ──▶ fallback preview
```

`deploy/mediamtx.yml` essentials:

```yaml
authMethod: http
authHTTPAddress: http://sparrow-server:8080/v1/mtx/auth
authHTTPExclude: [{action: api}, {action: metrics}, {action: pprof}]
api: yes
apiAddress: :9997
srt: yes
srtAddress: :8890
webrtc: yes
hls: yes
pathDefaults:
  srtPublishPassphrase: ${SPARROW_MTX_SRT_PASSPHRASE}   # shared link encryption, see 7.3
  runOnReady: curl -s -X POST -H "X-Hook-Secret: $SPARROW_MTX_HOOK_SECRET" \
     http://sparrow-server:8080/v1/mtx/hooks/ready -d "path=$MTX_PATH&sourceId=$MTX_SOURCE_ID&query=$MTX_QUERY"
  runOnNotReady: curl ... /v1/mtx/hooks/notready ...
paths:
  '~^dev/[A-Za-z0-9-]+$': {}
```

(On current MediaMTX main the hooks are named `runOnAvailable`/`runOnUnavailable`; pin a version in
`docker-compose.yml` and match the names to it.)

### 7.2 Provisioning flow

1. Operator clicks "Enable hosted gateway" on a device (or ticks it at enrollment).
2. Server creates `gateway_streams` row: path `dev/<short-id>`, `publish_user = <short-id>`,
   `publish_pass = random 24 chars`.
3. Server adds a managed profile "Hosted gateway" to the device config: caller mode,
   `host = SPARROW_MTX_PUBLIC_HOST`, `port = 8890`,
   `streamId = publish:dev/<short-id>:<user>:<pass>`, `passphrase = shared SRT passphrase`,
   `latencyMs` from an operator default. Optionally marks it active. Revision bumps,
   `config.changed` goes out, the phone pulls the config and is ready to go live.
4. Device goes live → MediaMTX calls `/v1/mtx/auth` with `action=publish`, `path=dev/<id>`,
   `user`, `password` → server checks the row, device not revoked → 200.
5. `runOnReady` → server marks the session `gateway_live = true`; the console switches the
   preview tile on. A 30 s reconciliation against `GET /v3/paths/list` on the Control API catches
   missed hooks.
6. Console preview: `GET /v1/devices/{id}/stream` returns `https://mtx.example/dev/<id>/whep`
   plus a 10-minute read token; the console passes it as the WHEP `pass`; MediaMTX asks
   `/v1/mtx/auth` with `action=read` and the server validates the token.
7. Downstream 4609 consumers get `srt://mtx.example:8890?streamid=read:dev/<id>:<ruser>:<rpass>`
   with per-consumer read credentials created by the operator.

### 7.3 Known properties and risks of the MediaMTX path

| Topic | Finding | Plan |
| --- | --- | --- |
| KLV passthrough | SRT in → SRT out keeps the KLV data track (reported working on current MediaMTX; FFmpeg reads it as `klv (KLVA)`). RTSP out re-wraps KLV as RFC 6597, which FFmpeg does not decode. | Document SRT read as the 4609 path. **Phase 0 spike:** confirm sync and async carriage both survive byte-exact using `TsDemuxer` + `St0601Decoder` from `core` against a pinned MediaMTX version; add this as an opt-in integration test like `FfmpegInteropTest`. |
| Server-side recording | MediaMTX records fMP4/MPEG-TS; whether KLV lands in the recording is unverified. | Spike. Fallback: a tiny `sparrow-archiver` container running `ffmpeg -i srt://mtx?streamid=read:… -c copy -f mpegts` per live path, triggered by the ready hook. |
| H.265 preview | Browser WebRTC H.265 support is patchy. | Console falls back to HLS; recommend H.264 in gateway profiles; the operator sees a hint. |
| Credentials in `streamid` | SRT sends the stream ID in the handshake in clear. The media is still encrypted by the passphrase. | Per-device random publish credentials, revocable, rotated when the gateway profile is re-provisioned. Per-device SRT passphrases need per-device path config via the Control API (`POST /v3/config/paths/add/{name}`) instead of the regex path; deferred to phase 4 unless required. *(decide)* |
| If the KLV spike fails | | Replace MediaMTX for the 4609 leg with a raw-passthrough SRT relay (e.g. `voc/srtrelay`, Go, HTTP auth) and have MediaMTX pull from it for preview only. The server API does not change; only `deploy/` and the provisioning strings do. |

---

## 8. Security and privacy

* TLS everywhere outside the Compose network (Caddy terminates; Let's Encrypt or an operator-supplied
  cert). Debug builds may use cleartext to `localhost`/`10.0.2.2` only.
* Device tokens: 32 random bytes, stored SHA-256 on the server, in `SecretStore` on the phone,
  rotated on demand, deleted on revoke. Upgrade path: Keystore EC key pair with signed challenge
  (non-exportable key) if operators ask for it.
* Enrollment tokens: single-use by default, expire after 24 h, created only by operators.
* Passphrases: encrypted at rest on the server (AES-GCM, KEK from env); never in logs on either side
  (`AppLog.addSecret` on the phone, a redacting log filter on the server).
* MediaMTX hook and auth endpoints: internal network only plus a shared header secret; never on the
  public listener.
* Telemetry levels enforced on the device, not just the server; position is only sent at `FULL`.
  The Backend screen states in plain words what each level sends.
* Rate limits on `/v1/enroll` and `/v1/mtx/auth`; max body sizes on telemetry and logs.
* Audit: every command and config change records `issued_by` / `updated_by`.
* Unenroll wipes token, managed profiles, spool and backend prefs; the server keeps history unless
  the operator deletes the device.

---

## 9. Phases, deliverables, acceptance

Estimates are for one developer familiar with the codebase.

### Phase 0: spikes and decisions (3–5 days)

* MediaMTX KLV passthrough test (SRT→SRT, sync + async) with a pinned version; recording check.
* OkHttp WebSocket + Robolectric smoke test to confirm the test setup works offline.
* Decide: server auth model (static tokens first?), telemetry default level, single-tenant.
* Output: this document updated with results; `deploy/` skeleton with MediaMTX + Caddy.

### Phase 1: enrollment, telemetry, remote config (about 3 weeks)

App

* `core.remote` DTOs with round-trip tests.
* `BackendConfig`, `BackendClient`, `ControlChannel`, `BackendSession`, `TelemetryCollector`,
  `TelemetrySpool`, `RemoteConfigSync`, `BackendSyncWorker`.
* Backend screen (enroll / status / telemetry level / unenroll), cloud chip, managed-profile badge.
* `Profile.managed`, `ProfileRepository.applyManaged`.

Server

* `:server` module: Ktor, Postgres, Flyway, device endpoints, WS, operator JSON endpoints,
  Dockerfile, `docker-compose.yml` (server + postgres + caddy), Prometheus metrics.

Acceptance

* Fresh install streams to a third-party receiver with no backend: no network traffic other than
  SRT (checked with a packet capture).
* Enroll via pasted URL; device appears online in `GET /v1/devices`; going live produces link,
  health and session rows; a `PUT …/config` with a new profile shows up on the phone within 5 s
  while connected and within 15 min while idle.
* Kill the network mid-stream: telemetry resumes with no gap after reconnect (spool).

### Phase 2: commands and console (about 2 weeks)

* `CommandDispatcher`, pipeline hooks (`setBitrate`, keyframe, restart), start-by-notification.
* Operator commands endpoint with wait; console v1 (device list, detail with live link chart, map
  at `FULL`, zoom/bitrate/keyframe/stop/profile controls, config editor built from the same
  validation as the app, log viewer). TypeScript + Vite, served from the server jar.
* Acceptance: zoom from the console changes the stream and the phone's zoom readout within 500 ms
  on Wi-Fi; a command to an offline device fails immediately with `DEVICE_OFFLINE`; every applied
  command is visible in the phone's log.

### Phase 3: hosted gateway (about 2–3 weeks)

* MediaMTX in Compose with HTTP auth, hooks, Control API reconciliation.
* `POST …/gateway` provisioning, managed "Hosted gateway" profile, playback URLs, console preview
  (WHEP, HLS fallback), read credentials for 4609 consumers, optional archiver.
* Acceptance: one click enrolls a device onto the gateway; `ffprobe` on the SRT read URL shows
  video + `klv` data and `St0601Decoder` reproduces the device's KLV byte-exact; the console shows
  the preview within 3 s of the phone going live; revoking the device kills the publish.

### Phase 4: hardening (ongoing)

* OIDC for operators and roles; device groups with config templates and per-device overrides.
* Keystore key-pair device auth; per-device SRT passphrases via Control API path config.
* Alerts (stream dropped, GNSS lost, battery low) via webhook/e-mail; retention tuning;
  dashboards from `/metrics`.
* Live QR scanning in the app (also closes the existing SRT-4 gap).

---

## 10. Testing strategy

| Layer | Tests |
| --- | --- |
| `core.remote` | JSON round-trips, unknown-field tolerance, `Command` polymorphism, `ConfigSnapshot` merge rules (pure functions) |
| App | Robolectric: `CommandDispatcher` mapping table, `TelemetrySpool` cap/drain/corruption, `RemoteConfigSync` merge against `ProfileRepository`, Backend screen screenshot added to `ScreenshotTest` |
| Server | Ktor test host for every endpoint; Testcontainers Postgres for migrations and queries (skipped without Docker); WS integration test with a fake device; MediaMTX auth-hook contract test from a recorded request body |
| Gateway | Opt-in integration test (env `MTX_HOME` or Docker): publish a TS from `TsMuxer`, read back over SRT, assert KLV byte-exact; mirrors `FfmpegInteropTest` |
| End to end | `deploy/` Compose stack + a device or emulator; checklist in `docs/operator-guide.md` |

---

## 11. Open questions *(decide before phase 1)*

1. **Hosting model.** Self-hosted single tenant (assumed) or one shared service for several
   organisations? Multi-tenant changes auth, data model and the console early.
2. **Position telemetry default.** Off until the operator sets `FULL`, or on by default once
   enrolled? The plan assumes off by default.
3. **4609 consumers through the gateway.** Must KLV survive the gateway for external consumers,
   or is the gateway only for operator preview? This decides how hard the phase 0 spike gate is.
4. **Operator authentication.** Static tokens acceptable for v1?
5. **Fleet size and retention.** Tens or thousands of devices; how many days of samples.
6. **Server language.** Kotlin/Ktor sharing `core` (recommended) versus Go next to MediaMTX.

---

## 12. Sources checked for the gateway section

* MediaMTX issue 5612 (SRT publish with KLV, SRT read keeps KLV, RTSP read uses RFC 6597):
  https://github.com/bluenviron/mediamtx/issues/5612
* MediaMTX KLV handling PR 3688: https://github.com/bluenviron/mediamtx/pull/3688
* MediaMTX configuration reference (`authMethod: http`, hooks, Control API, SRT, HLS defaults):
  https://github.com/bluenviron/mediamtx/blob/main/mediamtx.yml
* MediaMTX MPEG-TS source docs listing KLV among supported "other" tracks:
  https://mediamtx.org/docs/static-sources/mpeg-ts
