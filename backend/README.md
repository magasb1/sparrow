# sparrowd

The optional Sparrow backend (milestones 0 and 1 of [docs/backend-plan.md](../docs/backend-plan.md)).
Phones register with it from an enrollment link, report status and telemetry, and keep a control
stream open for config changes and, later, commands. An admin sees the phones on a web page.

The SRT relay (`internal/relay`) is built and tested but not yet wired into `sparrowd`; that is
milestone 3.

## Run

```sh
cd deploy
cp .env.example .env   # set the passwords and the address phones use
docker compose up -d
```

Then open `https://<server>:8443`, log in, and use **Add phone**. On the phone, open
**Menu → Backend** and scan or paste the link.

On [Coolify](https://coolify.io), add a *Docker Compose* resource for this repository with the
compose location `/deploy/docker-compose.coolify.yml`. Coolify generates the database and admin
passwords (Environment Variables tab); set `SPARROW_PUBLIC_URL` (`https://<host>:8443`) and
`SPARROW_TLS_HOSTS` (the host name and IP) there before the first deploy. sparrowd is published on
host port 8443 and bypasses Coolify's proxy on purpose: phones pin its own certificate.

Without Docker, `sparrowd` needs PostgreSQL and these variables:

| Variable | Meaning |
| --- | --- |
| `SPARROW_DATABASE_URL` | PostgreSQL URL (required) |
| `SPARROW_ADMIN_PASSWORD` | Web login password, 8 characters or more (required) |
| `SPARROW_ADMIN_USER` | Web login name, default `admin` |
| `SPARROW_LISTEN` | Listen address, default `:8443` |
| `SPARROW_PUBLIC_URL` | URL phones use, e.g. `https://192.168.1.10:8443`; default is the address the browser used |
| `SPARROW_DATA_DIR` | Holds the self-signed certificate, default `/var/lib/sparrow` |
| `SPARROW_TLS_HOSTS` | Extra names and IPs for the self-signed certificate, comma separated |
| `SPARROW_TLS_CERT`, `SPARROW_TLS_KEY` | Use these certificate files instead of a self-signed one |
| `SPARROW_INSECURE_HTTP` | `true` serves plain HTTP. Lab only; only debug builds of the app accept it |
| `SPARROW_REPORT_RETENTION_DAYS` | Days of reports to keep, default 30 |

Phones pin the server's key from the enrollment link. Keep the data directory (or the key file
you supply): a new key means registering every phone again.

## Develop

```sh
cd proto && buf lint && buf generate        # after changing the .proto files
cd backend
go vet ./...
SPARROW_TEST_DATABASE_URL=postgres://postgres@127.0.0.1:5432/postgres go test -race ./...
```

Tests that need PostgreSQL create and drop their own database and are skipped when
`SPARROW_TEST_DATABASE_URL` is unset. The relay interop test needs `ffmpeg` built with libsrt and
`srt-live-transmit` (Debian/Ubuntu package `srt-tools`), and is skipped without them.

The phone's protocol test can run against a live server: make a link on **Add phone**, then

```sh
SPARROW_IT_LINK='sparrow://enroll?...' ./gradlew :protocol:test --tests '*LiveServerTest*'
```

The device API speaks the Connect protocol, so `curl` works for quick checks:

```sh
curl -k -H 'Content-Type: application/json' -d '{"enrollmentToken":"x"}' \
  https://localhost:8443/sparrow.v1.DeviceService/Register
```

## Milestone 0 results

| Check | Result |
| --- | --- |
| gosrt accepts libsrt callers with AES-128 and AES-256 and per-port passphrases | Passed (`TestLibsrtInterop`, FFmpeg 6.1 / libsrt 1.5.3 publishing) |
| Relay output is byte-exact | Passed: libsrt reader output equals the relay input, for gosrt and libsrt publishers |
| Wrong key, alias, passphrase, mode or stream ID is rejected | Passed (`TestRelayRejects`) |
| Slow reader does not affect others | Passed (`TestSlowSubscriberDoesNotBlockOthers`) |
| Standby keeps camera access for hours with the screen off | **Not run**: needs a phone. Required before milestone 2 |

Note for readers: FFmpeg 6 and `srt-live-transmit` do not URL-decode the `streamid` query
parameter, so it goes into the URL unencoded, for example
`srt://host:8891?streamid=#!::r=cam-01,m=request&passphrase=...`. The phone sets the stream ID
directly and is not affected.
