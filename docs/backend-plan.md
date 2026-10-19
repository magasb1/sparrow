# Sparrow backend: plan (v3)

An optional on-prem backend for Sparrow. Phones register with it and receive a device
certificate. They report config and telemetry, sit in remote standby, and take commands (start,
stop, set config, zoom) from a web service. The backend shows live video in the browser and
distributes each phone's stream over SRT, RTMP, RTSP and ONVIF. KLV stays byte-exact on every
MPEG-TS output.

With no backend registered, the app behaves exactly as it does today. Everything here is opt-in.

Status: **proposal, not implemented.**

Decisions taken (v3)

| Topic | Decision |
| --- | --- |
| Repository | This repo: `proto/`, `protocol/`, `backend/`, `deploy/` next to `core/` and `app/` |
| Server | Go (`sparrowd`), protobuf, gRPC via connect-go |
| Database | PostgreSQL from the start |
| Outputs | SRT (pull and push), RTMP push, ONVIF (RTSP underneath), browser viewer |
| KLV | Byte-exact on every MPEG-TS output |
| Standby | The phone arms remote standby as soon as it is registered |
| Phone auth | Private PKI: each phone gets a client certificate at registration; the server can revoke it |

---

## 1. Scope

First release

1. **Registration** by QR or by URL plus admin approval, ending in a device certificate.
2. **Remote standby** armed right after registration, so the web service can start the stream.
3. **Config** as desired state on the server, applied and acknowledged by the phone.
4. **Telemetry**: link stats, health, session events, and opt-in position and attitude.
5. **Commands**: start, stop, restart, select profile, zoom, bitrate, keyframe, upload logs.
6. **Viewer** in the browser, with status and a map.
7. **Distribution**: SRT pull and push and UDP TS with byte-exact KLV; RTSP and ONVIF for
   NVR/VMS; RTMP push.

Later: recording, alerts, single sign-on (OIDC), device groups and templates, TURN for remote
viewers, a WebSocket fallback for sites that block HTTP/2.

Not planned: transcoding, analytics, cloud multi-tenancy.

---

## 2. Architecture

```
 Phone (Sparrow)                                    On-prem server
 ┌────────────────────────────────┐                 ┌────────────────────────────────────────────────────┐
 │ StreamingService               │                 │ sparrowd (Go)                                      │
 │  STANDBY ⇄ LIVE                │   SRT :8890     │  ingest ── stream hub (raw TS, per alias) ──┬─ SRT read (pull)      ─▶ 4609 tools
 │  camera→enc→mux→SrtSender ─────┼────────────────▶│  (gosrt)                                    ├─ SRT push (caller)    ─▶ remote SRT
 │                                │                 │                                             ├─ UDP uni/multicast TS ─▶ 4609 network
 │ remote/                        │  gRPC, mTLS     │                                             └─ SRT → MediaMTX (loopback)
 │  DeviceLink ───────────────────┼── :8443 ───────▶│  device API · PKI · hub · config · telemetry      │
 │  Keystore key + device cert    │                 │  admin API + web UI :443                          │
 │  TelemetrySpool, RemoteConfig  │                 │  onvif: one ONVIF device per phone :8100–8199     │
 │  CommandDispatcher, SyncWorker │                 │  PostgreSQL                                       │
 └────────────────────────────────┘                 │                                                   │
                                                    │ MediaMTX (internal) ─ RTSP :8554  ─▶ NVR/VMS, ONVIF stream URI
                                                    │                     ─ WebRTC :8889 ─▶ browser viewer
                                                    │                     ─ HLS :8888    ─▶ fallback viewer
                                                    │ ffmpeg -c copy workers ─ RTMP push ─▶ YouTube, VMS, CDN
                                                    └────────────────────────────────────────────────────┘
```

### 2.1 Why sparrowd takes the SRT itself

MediaMTX demuxes and remuxes every stream. That rewrites PIDs, continuity counters, PCR and PES
framing, so its output can never be byte-exact. sparrowd instead accepts the phone's SRT with the
pure-Go `datarhei/gosrt` library and forwards the phone's 188-byte TS packets unchanged to every
MPEG-TS output. The phone already muxes to ST 1402 and STANAG 4609, so passing its packets through
is the most faithful thing the server can do.

MediaMTX stays for what it does well: RTSP for NVR/VMS and ONVIF, WebRTC and HLS for viewers. It
gets the stream from sparrowd over SRT on loopback. KLV there is a bonus, not a promise.

Owning the SRT listener also gives sparrowd per-connection auth, per-device passphrases, and a
keyframe request to the phone whenever a new reader joins.

### 2.2 Channels

* **Media**: phone to sparrowd over SRT. Works when the control link is down.
* **Control**: one gRPC bidirectional stream per phone over mutual TLS (mTLS). Works when no stream
  is running, and when the phone streams to a third-party receiver.
* **Idle sync**: WorkManager every 15 minutes, for when standby is not armed.

---

## 3. Protocol

### 3.1 Choices

| Concern | Choice |
| --- | --- |
| Schema | Protobuf, package `sparrow.v1`, in `proto/` |
| Tooling | `buf lint`, `buf breaking` against `main` in CI, `buf generate` for Go and TypeScript |
| Server | `connectrpc.com/connect`: gRPC for phones, Connect protocol for the browser |
| Android | `grpc-kotlin` + `grpc-okhttp` + `protobuf-kotlin-lite`, generated in a JVM module `:protocol` |
| Browser | `@connectrpc/connect-web` + `@bufbuild/protobuf` |
| Validation | `protovalidate` in sparrowd; `Profile.validate()` on the phone; shared fixtures keep them equal |

### 3.2 Files

```
proto/sparrow/v1/
  config.proto           Profile and sub-messages, DeviceConfig, DevicePolicy
  telemetry.proto        LinkSample, PositionSample, HealthSample, SessionEvent, StatusSnapshot, TelemetryBatch
  command.proto          Command (oneof), CommandResult
  device.proto           DeviceInfo, CameraInfo
  device_service.proto   phone ↔ server
  admin_service.proto    web UI ↔ server
  media_service.proto    streams, outputs, consumers, viewer, ONVIF
proto/testdata/profiles/*.json   shared validation fixtures
```

### 3.3 Device service

```proto
service DeviceService {
  // Plain TLS (the phone has no certificate yet).
  rpc GetChallenge(GetChallengeRequest) returns (Challenge);          // nonce, valid 5 min
  rpc Register(RegisterRequest) returns (RegisterResponse);
  rpc WaitForApproval(WaitForApprovalRequest) returns (RegisterResponse); // long poll while pending

  // mTLS with the device certificate.
  rpc Connect(stream DeviceMessage) returns (stream ServerMessage);
  rpc RenewCertificate(RenewCertificateRequest) returns (CertificateBundle);
  rpc UploadTelemetry(TelemetryBatch) returns (UploadResponse);
  rpc UploadLogs(stream LogChunk) returns (UploadResponse);
  rpc GetConfig(GetConfigRequest) returns (DeviceConfig);
}

message RegisterRequest {
  bytes challenge = 1;                      // from GetChallenge
  string enrollment_token = 2;              // empty: wait for admin approval
  bytes public_key = 3;                     // SubjectPublicKeyInfo, DER, P-256
  repeated bytes attestation_chain = 4;     // Android key attestation, leaf first
  bytes challenge_signature = 5;            // proof of possession, ECDSA over challenge
  DeviceInfo device = 6;
  string requested_name = 7;
}
message RegisterResponse {
  oneof result { Pending pending = 1; Registered registered = 2; Rejected rejected = 3; }
}
message Pending { string registration_id = 1; string pairing_code = 2; }   // 6 digits, shown on the phone
message Registered {
  string device_id = 1; string alias = 2;
  CertificateBundle certificate = 3;
  DeviceConfig config = 4;                  // includes the gateway profile and policy
  google.protobuf.Timestamp server_time = 5;
}
message CertificateBundle { bytes leaf = 1; repeated bytes chain = 2; google.protobuf.Timestamp not_after = 3; }

message DeviceMessage {
  oneof msg { Hello hello = 1; StatusSnapshot status = 2; TelemetryBatch telemetry = 3;
              CommandResult command_result = 4; ConfigAck config_ack = 5; }
}
message ServerMessage {
  oneof msg { Welcome welcome = 1; Command command = 2; DeviceConfig config = 3;
              PublishCredentials publish = 4; }
}
message PublishCredentials {               // pushed on connect and renewed hourly
  string stream_id = 1;                     // #!::r=cam/<alias>,m=publish,t=<token>
  string passphrase = 2;                    // per device, rotated with the token
  google.protobuf.Timestamp expires = 3;    // 24 h
}
```

`config.proto`, `telemetry.proto` and `command.proto` are as in v2: `Profile` mirrors the Kotlin
class field for field plus `managed`; `DeviceConfig` carries `revision`, managed profiles,
`active_profile_id` and `DevicePolicy`; telemetry has link samples at 1 Hz, position at 1 Hz with
the frame footprint, health every 60 s, and a debounced `StatusSnapshot` whose state now includes
`STANDBY` and `DISARMED`. `DevicePolicy` gains `allow_user_disarm` (default true).

### 3.4 Desired and applied config

The server stores desired config with a revision. The phone answers each config with `ConfigAck`
(applied, or rejected with reasons) and reports its applied revision in every status. The web UI
shows each phone as in sync, pending, or rejected.

### 3.5 Kotlin, Go and proto in line

* `:protocol` (JVM module) holds generated Kotlin and `ProfileMapper` (`Profile ⇄ sparrow.v1.Profile`).
* A round-trip test with every field set, and a reflection test that fails when `Profile` gains a
  field the mapper misses.
* `proto/testdata/profiles/*.json` runs through `Profile.validate()` in Kotlin and protovalidate
  in Go. A rule changed on one side only fails CI.

---

## 4. Phone identity: private PKI

### 4.1 Verdict

Certificates per phone are a good fit here and worth doing from day one. They replace the bearer
token from v2 rather than adding to it, and cost about a week more.

What they buy:

* **The identity cannot be copied.** The private key is generated inside the Android Keystore
  (StrongBox where present, otherwise the TEE) and cannot be exported. A backup, a rooted file
  copy or a leaked database does not give anyone a working device identity.
* **Revocation is immediate.** sparrowd is both the CA and the only party that checks the
  certificates, so it checks its own database on every connection. No CRL distribution or OCSP
  responder is needed.
* **Key attestation, optionally.** Android can prove that the key lives in secure hardware and
  was created by the genuine Sparrow app (package name and signing certificate). The server can
  record this or require it.
* **Standard tooling.** It is ordinary TLS client authentication: Go's `crypto/tls` on the server,
  OkHttp's key manager on the phone.

What it costs:

* **CA key custody.** Losing the CA key means re-registering every phone. Backup is part of install.
* **Clocks.** Certificates have validity windows. Phones normally have network or GNSS time;
  validity starts one hour in the past to absorb skew.
* **TLS proxies.** A proxy that terminates TLS in front of sparrowd breaks mTLS. The device port
  must be passed through as TCP.
* **SRT has no client certificates.** The certificate guards the control link; the control link
  hands out short-lived SRT credentials (§4.6).
* **OEM Keystore quirks.** StrongBox is slow or flaky on some phones. The app falls back to the TEE
  and reports which it used.

### 4.2 CA layout

```
Sparrow Root CA          P-384, 20 years, created at install. Key encrypted at rest; can be
 │                       exported for offline storage and removed from the server.
 └─ Sparrow Issuing CA   P-256, 3 years, online, signs device and server certificates.
     ├─ server cert      sparrowd's TLS certificate for :8443 and :443 (or a site-supplied one for :443)
     └─ device certs     one per phone, 1 year
```

Phones trust the **root**, pinned by its SPKI SHA-256 from the enrollment QR or confirmed at the
first approval. Renewing the server or issuing certificates therefore never touches the phones.

The CA keys sit in PostgreSQL encrypted with a key-encryption key from a file or environment
variable. PKCS#11 (an HSM or a TPM) is a later option behind the same `pki.Signer` interface.

### 4.3 Device certificate

| Field | Value |
| --- | --- |
| Subject | `CN=<device-id>, O=Sparrow, OU=<site>` |
| SAN | `URI:urn:sparrow:device:<device-id>` |
| Key | P-256, from the phone's Keystore |
| Extended key usage | clientAuth only |
| Validity | not before = now − 1 h, not after = now + 365 days |
| Serial | 128-bit random; stored with the device |

### 4.4 Registration flow

```
Phone                                         sparrowd
  │ GetChallenge ───────────────────────────────▶ │ nonce (stored, 5 min)
  │ generate P-256 key in Keystore,
  │   attestation challenge = nonce
  │ Register(token?, SPKI, attestation, sig) ───▶ │ verify signature (and attestation, per policy)
  │                                               │ token valid → issue cert
  │ ◀─ Registered(cert, config) ────────────────── │ no token → Pending(pairing code)
  │   or Pending(code) ── shows code ──           │ admin compares code, approves in web UI
  │ WaitForApproval(sig over new nonce) ───────▶  │ → Registered(cert, config)
  │ store chain in Keystore entry
  │ arm standby, open Connect over mTLS ───────▶  │
```

The phone stores the issued chain with `KeyStore.setKeyEntry(alias, privateKey, null, chain)` so
the key and certificate stay together in the Keystore.

### 4.5 Checks, renewal and revocation

* **Two ports.** `:8443` for phones uses `tls.VerifyClientCertIfGiven`; an interceptor requires a
  verified certificate for every RPC except `GetChallenge`, `Register` and `WaitForApproval`.
  `:443` serves the web UI with ordinary server-only TLS.
* **On each connection** the interceptor maps the certificate serial to a device and checks that
  the device is active and the serial current. The result is cached for 60 s and dropped on any
  revocation.
* **Renewal.** With fewer than 60 days left, the phone calls `RenewCertificate` over mTLS. A fresh
  Keystore key is generated each time, so keys rotate yearly. A phone whose certificate has expired
  registers again and needs approval.
* **Revocation** from the web UI: mark the serial revoked, close the phone's control stream, kick
  its live SRT publish, stop renewing its SRT credentials. It takes effect in under a second.
* **CRL.** sparrowd still publishes a CRL at `/pki/crl.der`, refreshed hourly, for any other party
  a site may put in front of it.

### 4.6 Key attestation

* The phone sends the attestation chain from the key it generated, with the server's nonce as the
  attestation challenge.
* sparrowd checks the chain against Google's attestation roots. Since February 2026 there are two:
  the legacy RSA root, and a new ECDSA P-384 root that devices with Remote Key Provisioning chain
  to from April 2026. Both are bundled.
* It then checks the challenge, the security level (TEE or StrongBox), the package name, the app
  signing certificate digest, and that the bootloader is locked.
* Policy per site: **off**, **record** (default: stored and shown in the web UI), or **require**.
  The online revocation status list from Google is checked only when the server has internet.

### 4.7 SRT credentials

SRT cannot present a client certificate, so the control link hands out media credentials:

* `PublishCredentials` holds a stream ID with a signed token (Ed25519, 24 h, device id and alias
  inside) and a per-device SRT passphrase. Both are renewed hourly while the phone is connected.
* sparrowd's SRT accept callback verifies the token, sets that device's passphrase for the
  handshake, and rejects anything else.
* After a revocation, no new token is issued and the running publish is kicked.

---

## 5. Server: sparrowd

### 5.1 Layout

```
backend/
  go.mod                        module github.com/magasb1/sparrow/backend, Go 1.25+
  cmd/sparrowd/                 the server
  cmd/sparrowctl/               admin CLI: init CA, backup and restore, create first user, revoke
  cmd/fakephone/                simulated phone: registers, speaks DeviceService, publishes a recorded TS
  internal/
    gen/                        buf-generated Go
    pki/                        CA, issuing, CRL, attestation verification, Signer interface
    device/                     DeviceService handlers, mTLS interceptor
    admin/                      AdminService, MediaService handlers, web sessions
    hub/                        connected phones, command routing, watcher fan-out
    store/                      PostgreSQL: pgx v5, sqlc, goose migrations
    telemetry/                  ingest, partitions, rollups, retention
    ingest/                     gosrt listener, accept auth, per-alias stream hub
    output/                     SRT push, UDP TS, MediaMTX feed, RTMP workers
    onvif/                      ONVIF device endpoints, WS-Discovery, PTZ zoom
    mediamtx/                   auth hook for RTSP/WebRTC readers, Control API client
  web/                          React + Vite + connect-web, embedded with go:embed
deploy/
  docker-compose.yml            sparrowd, postgres:17, mediamtx (pinned versions)
  mediamtx.yml
  systemd/
```

### 5.2 Libraries

| Need | Library |
| --- | --- |
| RPC | `connectrpc.com/connect`, `google.golang.org/protobuf`, `buf.build/go/protovalidate` |
| Database | `github.com/jackc/pgx/v5`, `sqlc`, `pressly/goose` |
| SRT | `github.com/datarhei/gosrt` |
| PKI | Go standard `crypto/x509`, `crypto/ecdsa`; attestation ASN.1 parsing in `internal/pki` |
| Passwords | `golang.org/x/crypto/argon2` |
| Logs, metrics | `log/slog`, `prometheus/client_golang` |
| RTMP | ffmpeg (`-c copy`) workers first; `bluenviron/gortmplib` later to drop the ffmpeg dependency |

### 5.3 PostgreSQL

* PostgreSQL 17 in Compose; sites with their own server point `SPARROW_DATABASE_URL` at it.
* Telemetry tables are partitioned by month (`PARTITION BY RANGE (ts)`). Retention drops whole
  partitions, so it costs nothing. Raw samples are kept 30 days by default, one-minute rollups a year.
* sqlc generates typed Go from the SQL. Migrations run at startup and are also available through
  `sparrowctl migrate`.

### 5.4 Data model

```
users              id, username, password_hash, role (admin|operator|viewer), created_at, disabled
web_sessions       token_hash, user_id, expires_at
ca                 id, kind (root|issuing), cert, key_encrypted, not_after, created_at
devices            id, alias (unique), name, state (pending|active|revoked), pairing_code,
                   registered_at, approved_by, last_seen_at, device_info, app_version,
                   attestation (jsonb: level, verified, package, signer digest, boot state)
device_certs       serial, device_id, not_before, not_after, public_key, revoked_at, revoked_by, reason
challenges         nonce, expires_at, used_at
enrollment_tokens  token_hash, label, created_by, expires_at, max_uses, uses
device_config      device_id, desired_revision, desired, applied_revision, applied_at, rejected_errors
device_secrets     device_id, profile_id, passphrase_encrypted
srt_credentials    device_id, passphrase_encrypted, token_expires_at
stream_sessions    id, device_id, started_at, ended_at, end_reason, profile_summary
link_samples       partitioned by month: device_id, ts, session_id, kbps, target, rtt, sent, lost, retrans, dropped, snd_buf
position_samples   partitioned by month: device_id, ts, session_id, lat, lon, alt, acc, heading, elev, roll, zoom, hfov, vfov, footprint
health_samples     partitioned by month: device_id, ts, battery, charging, thermal, network, free_mb
commands           id, device_id, kind, args, issued_by, issued_at, delivered_at, completed_at, status, message
log_uploads        id, device_id, received_at, size, path
consumers          id, name, username, password_hash, allowed_aliases (glob), protocols, created_by
outputs            id, alias, kind (srt_push|udp|rtmp), target, options, enabled, created_by
onvif_devices      alias, port, uuid, enabled
audit_log          ts, user_id, action, target, details
```

### 5.5 Hub and commands

As in v2: one entry per connected phone; commands go straight down the stream and wait up to 10 s
for a result; a phone that is not connected fails at once with `DEVICE_OFFLINE`; commands are never
queued. Config changes bump the revision and push immediately when the phone is connected.
`WatchDevices` streams status and telemetry deltas to the web UI.

Keepalive: the **server** pings each phone every 30 s. Incoming packets wake a sleeping phone, so
the phone does not need a wake lock just to keep the link alive in standby (to be confirmed in the
phase 0 spike).

### 5.6 Web UI

* **Devices:** state (offline, standby, disarmed, live), profile, bitrate, battery, last seen,
  config sync, certificate expiry, attestation level. Pending registrations with the pairing code
  and an Approve button.
* **Device:** viewer, status, link chart, map with footprint, controls (Start, Stop, Restart, zoom,
  bitrate, keyframe, profile), commands, logs, certificate (renew, revoke).
* **Config:** profile editor with protovalidate, desired vs applied.
* **Streams:** one row per alias with copyable SRT, RTSP, HLS and ONVIF addresses, reader count,
  and outputs (add SRT push, UDP, RTMP).
* **Consumers, Users, Enrollment, PKI** (CA status, backup, CRL), **Audit log**.

---

## 6. Media

### 6.1 Ingest and the stream hub

* `ingest` listens for SRT on `:8890` with gosrt. Stream IDs use the SRT access-control syntax:
  `#!::r=cam/<alias>,m=publish,t=<token>` for phones and `#!::r=cam/<alias>,m=request,u=<user>`
  for readers, whose password is the SRT passphrase of their consumer account.
* The accept callback checks the token or consumer, sets the matching passphrase, and accepts or
  rejects. Only one publisher per alias; a second publish with a valid token replaces the first.
* Each alias has a **stream hub**: a ring of the last 2 s of the phone's SRT payloads. Every output
  is a subscriber with its own bounded queue. A slow output drops its own packets and never slows
  ingest or the other outputs.
* When an output or reader attaches, the hub sends `RequestKeyFrame` to the phone over the control
  link (at most once per 2 s), so the new reader starts within one frame instead of one GOP.

### 6.2 Outputs

| Output | How | KLV |
| --- | --- | --- |
| SRT read (pull) | Readers connect to `:8890` with `m=request`; sparrowd sends the phone's packets as is | Byte-exact |
| SRT push | sparrowd dials a remote SRT listener (caller) with optional passphrase and stream ID | Byte-exact |
| UDP TS | Unicast or multicast, 7 packets per datagram, configurable TTL and interface | Byte-exact |
| RTSP | MediaMTX path `cam/<alias>`, fed by sparrowd over SRT on loopback | Converted by MediaMTX (RFC 6597) |
| WebRTC, HLS | MediaMTX, for the browser viewer | None |
| RTMP push | ffmpeg `-c copy` worker reading the MediaMTX RTMP or SRT path, supervised by sparrowd | None (FLV cannot carry KLV). H.265 needs a target that supports Enhanced RTMP |

"Byte-exact" means every TS packet the phone sent is forwarded unchanged: same PIDs, continuity
counters, PCR, PES headers and KLV bytes. Only the transport around it differs.

MediaMTX runs with its API and listeners bound to loopback except RTSP, WebRTC and HLS. Its
`authMethod: http` asks sparrowd about RTSP and viewer reads, using the same consumer accounts.

### 6.3 ONVIF

Goal: a VMS adds a Sparrow phone the same way it adds an IP camera.

* **One ONVIF device per phone**, each on its own port (default range 8100–8199). Milestone's
  generic ONVIF driver treats several profiles on one device as streams of one camera rather than
  separate channels, so one device per phone is the most compatible choice. A single multi-channel
  device can be added later for VMSs that prefer it.
* **Services:**
  * Device: GetDeviceInformation, GetCapabilities, GetServices, GetSystemDateAndTime, GetScopes,
    GetNetworkInterfaces, GetHostname.
  * Media (Profile S) and Media2 (needed for H.265): GetProfiles, GetVideoSources,
    GetVideoEncoderConfiguration(s), GetStreamUri returning the RTSP URL of `cam/<alias>`.
  * PTZ, zoom only: GetNodes, GetConfigurations, AbsoluteMove, RelativeMove, ContinuousMove, Stop,
    GetStatus. Zoom maps to the phone's `SetZoom` command within its reported range, so VMS
    operators can zoom from their own console.
* **Discovery:** WS-Discovery on `239.255.255.250:3702`, answering Probe with each phone's address.
  It only works on the VMS's own network segment; elsewhere the VMS adds the device by IP and port.
* **Auth:** WS-UsernameToken digest on SOAP calls, the same consumer accounts as RTSP.
* **Availability:** the ONVIF device stays up while the phone is offline, so the VMS keeps its
  configuration and shows "no signal" instead of losing the camera.
* **Conformance:** formal ONVIF conformance needs ONVIF membership and the official test tool. Until
  then the product is "ONVIF-compatible", verified with ONVIF Device Manager and the target VMSs.
* **Implementation:** hand-written SOAP handlers in Go with `encoding/xml`. Go ONVIF libraries are
  client-side, so the device side is ours. Expect about 1,500 lines with tests.

---

## 7. App changes

### 7.1 New code (`app/src/main/kotlin/app/sparrow/remote/`)

| Class | Job |
| --- | --- |
| `DeviceIdentity` | Keystore P-256 key (StrongBox, falling back to TEE), attestation, certificate chain storage, renewal, wipe |
| `BackendSettings` | Server URL, device id, alias, root pin, telemetry level, applied revision |
| `DeviceLink` | gRPC over OkHttp: a key manager that presents the Keystore key and chain, a trust manager that trusts only the pinned Sparrow root. Runs `Connect` as a Flow with reconnect and `Backoff` |
| `BackendSession` | Process-wide owner, reference-counted by the visible activity, the streaming service and standby |
| `TelemetryCollector`, `TelemetrySpool` | As in v2: batches every 5 s, length-prefixed protobuf on disk, 20 MB cap |
| `RemoteConfig` | `ProfileRepository.applyManaged()` and `ConfigAck` |
| `PublishCredentialStore` | Latest `PublishCredentials`; the gateway profile reads its stream ID and passphrase from here at start |
| `CommandDispatcher` | Command to existing API, as in v2 |
| `SyncWorker` | Idle sync when standby is not armed |
| `BootReceiver` | After a reboot, posts "Tap to re-arm Sparrow" (Android 15 blocks a camera service from boot) |

New dependencies: `grpc-okhttp`, `grpc-kotlin-stub`, `protobuf-kotlin-lite`, `androidx.work`
(about 2 MB). The existing `zxing` handles enrollment QR from an image; live QR scanning is added
for enrollment and also closes the SRT-4 gap in the README.

### 7.2 Hooks in existing code

* `StreamController`: link-sample and position flows, a `pipelineCommands` flow, a standby state.
* `StreamPipeline`: `setBitrate()` that caps ABR, `requestKeyFrame()`, link samples from
  `onStats`, position and footprint from `writeKlv` at 1 Hz.
* `StreamingService`: `STANDBY` mode (§7.3), a restart path outside the failure budget.
* `SrtSender`: takes the stream ID and passphrase at connect time, so renewed credentials apply on
  the next reconnect without restarting the pipeline.
* `ProfileRepository`, `Profile.managed`, `MainActivity` as in v2.

### 7.3 Standby, armed on registration

Android only lets a foreground service use the camera if it was started while the app was visible
(API 30+). Android 15 also stops a camera service from starting at boot. So:

* **On registration** (the app is on screen), the app starts `StreamingService` in `STANDBY`, with
  the camera, location and, if audio is on, microphone service types declared up front.
* **On every app open**, if registered and not armed, it arms again.
* **In standby** the camera is closed and nothing is encoded. The service keeps the control link
  and an ongoing notification "Sparrow standby: can be started remotely". No wake lock is held.
* **Start** from the web opens the camera and starts the pipeline. **Stop** returns to standby.
* **After a reboot or a kill**, the app cannot re-arm itself. `BootReceiver` posts a notification;
  tapping it opens the app, which re-arms. The web UI shows the phone as "not armed" meanwhile.
* **Disarm:** the notification has a Disarm action if the policy allows it (`allow_user_disarm`,
  default on). The web UI then shows "disarmed by user". Android's own camera indicator and the
  app's banner still show whenever the camera is in use.
* **Phase 0 spike:** on Android 12 to 16 devices, confirm that a standby service can open the camera
  after several hours with the screen off, and that server pings keep the link alive in Doze
  without a wake lock. Fallbacks: a partial wake lock in standby, or the camera kept open at 1 fps.

### 7.4 UI

* **Backend screen:** register (scan QR, paste link, or URL + pairing code), connection, standby
  state, certificate expiry and key protection (StrongBox or TEE), telemetry level, Unregister
  (deletes the Keystore key, managed profiles, credentials and spool, and tells the server).
* **Main screen:** a chip for link and standby state, banners for remote commands.
* **Profiles and Settings:** managed profiles locked, "Duplicate as local" to edit a copy.

---

## 8. Security summary

* TLS everywhere. Phones trust only the pinned Sparrow root; the web UI can use a site certificate.
* Phones authenticate with non-exportable Keystore keys and certificates; revocation is immediate.
* SRT media is encrypted with per-device passphrases and authorised by short-lived signed tokens.
* NVR/VMS and other readers use per-consumer accounts limited to aliases and protocols.
* Web users have roles (admin, operator, viewer); every change goes to the audit log.
* Passphrases and CA keys are encrypted at rest; nothing secret appears in logs.
* No outbound calls from sparrowd except the optional attestation status check; the web UI loads
  no external assets, so air-gapped sites work.

---

## 9. Phases

Estimates assume one developer who knows Go, Kotlin and TypeScript.

| Phase | Content | Effort |
| --- | --- | --- |
| 0. Spikes | gosrt ↔ srtdroid interop with AES and a per-connection passphrase; byte-exact pass-through check; standby camera access and Doze; Keystore mTLS through grpc-okhttp on 3 devices incl. StrongBox fallback; attestation with both Google roots; ONVIF stub added to ONVIF Device Manager and one VMS | 1.5 weeks |
| 1. Identity and observe | `proto/`, `:protocol`, sparrowd with PostgreSQL, `sparrowctl init`, PKI (CA, registration both ways, mTLS, renewal, revocation, CRL), `Connect`, status and telemetry with spool, web UI: login, devices, approval, device detail | 4 weeks |
| 2. Control | Desired/applied config and editor, all commands, standby armed on registration, boot re-arm, log upload, audit | 2–3 weeks |
| 3. Media | SRT ingest with tokens and per-device passphrases, stream hub, SRT pull and push, UDP TS, MediaMTX feed (RTSP, WebRTC, HLS), viewer, consumers, keyframe on join | 3–4 weeks |
| 4. ONVIF and RTMP | ONVIF devices per phone with PTZ zoom, WS-Discovery, Media2; RTMP push workers | 3 weeks |
| 5. Later | Recording (raw TS segments, byte-exact), alerts, OIDC, groups and templates, TURN, HSM for the CA | per feature |

Acceptance

* **1:** A phone with no backend streams with no traffic but SRT. Registration by QR gives a
  certificate and the phone appears live in the web UI within 2 s. Revoking it closes the link
  within 1 s, and the phone can no longer connect. A copied app data directory on another phone
  cannot connect.
* **2:** Config saved while a phone is offline applies on reconnect. A freshly registered phone is
  in standby without user action. Start from the web works with the screen off after 4 h in standby.
* **3:** For a recorded stream, SRT read, SRT push and UDP outputs deliver the same TS packets as the
  phone sent (sequence hash matches) and `St0601Decoder` reads identical KLV. A new reader sees
  video within 0.5 s. Revoking a live phone stops its publish.
* **4:** Milestone and at least one other VMS add a phone through ONVIF discovery, record it, and
  zoom it through PTZ. RTMP push to a test server plays.

---

## 10. Testing

| Layer | Tests |
| --- | --- |
| Schema | `buf lint`, `buf breaking` against `main` |
| `:protocol` | Mapper round trip, field coverage, shared validation fixtures |
| App | Robolectric: dispatcher, spool, `applyManaged`, standby state machine, Backend screen screenshots |
| PKI | Issue, renew, revoke, expired, wrong CA, wrong EKU; attestation chains from both roots (recorded fixtures) |
| sparrowd | Handlers against PostgreSQL in Testcontainers (skipped without Docker); hub with `fakephone`; mTLS interceptor; ONVIF SOAP golden requests and responses |
| Media | `fakephone` publishes a `TsMuxer` stream; byte-exact comparison on every TS output; reader join keyframe; slow-reader isolation |
| End to end | Compose stack, one real phone, one VMS; checklist in `docs/operator-guide.md` |

---

## 11. Still open

1. **Which VMS products define acceptance for ONVIF?** The plan assumes Milestone plus one more
   (Genetec, Nx Witness or Frigate).
2. **Position telemetry default.** The plan keeps it off until an admin sets FULL.
3. **Attestation policy default.** The plan records it; "require" would reject phones without a
   locked bootloader or with a re-signed app.
4. **Root CA key.** Kept online (simple) or exported and removed after install (safer, but issuing
   CA renewal every 3 years then needs the offline key).
5. **May users disarm standby?** The plan allows it by default, controlled by policy.

---

## 12. Sources

* MediaMTX issue 5612, KLV on SRT read versus RTSP: https://github.com/bluenviron/mediamtx/issues/5612
* MediaMTX configuration reference: https://github.com/bluenviron/mediamtx/blob/main/mediamtx.yml
* gosrt, pure Go SRT: https://github.com/datarhei/gosrt
* Connect for Go, streaming: https://connectrpc.com/docs/go/streaming
* gRPC Kotlin for Android: https://developers.googleblog.com/en/announcing-grpc-kotlin-10-for-android-and-cloud/
* Android 11 camera restriction for background-started services: https://developer.android.com/about/versions/11/privacy/foreground-services
* Android 15 restrictions on starting camera services from BOOT_COMPLETED: https://developer.android.com/about/versions/15/changes/foreground-service-types
* Android key and ID attestation: https://source.android.com/security/keystore/attestation
* Attestation root change in 2026: https://bayton.org/android/android-enterprise-faq/key-attestation-root-certificate-change/
* Milestone and multi-profile ONVIF devices: https://forum.milestonesys.com/t/milestone-vms-will-only-connect-to-one-stream-of-an-onvif-device-with-multiple-streams/9353
