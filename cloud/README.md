# Movia cloud staging runtime

This directory is the first deployable implementation of ADR-288. It runs the Movia control plane in **direct-only cloud mode**.

## Safety invariants

- `MOVIA_CLOUD_MODE=1` forces P2P discovery off even if `MOVIA_P2P_ENABLED=1` is supplied accidentally.
- `/stream` and `/stream/*` media-proxy routes return `404 media_proxy_disabled` in cloud mode.
- cloud playback responses expose only public `http/https` provider/CDN URLs; magnet and localhost/private-IP stream locators are removed.
- `catalog.db`, stream cache, logs and secrets are runtime data and are not baked into the image.
- Android NetworkStats budget logic is phone-only and is bypassed by cloud workers.
- the API process does not start the five-minute catalog-sync loop in cloud mode; catalog refresh is an explicit job.
- background torrent lookup is disabled in cloud mode and cannot be re-enabled by the normal bulk override.

## VPS layout

Recommended staging layout:

```text
/opt/movia/
  repo/                  # git checkout
  repo/cloud/runtime/
    catalog.db           # provisioned separately; not in git/image
    stream_cache/
    logs/
```

The Compose port is bound to `127.0.0.1` on the VPS. Expose it to Android only through a TLS reverse proxy (Caddy/Nginx or equivalent). Do not publish port 8080 directly to the Internet. The API and metadata job run `cloud_runtime_preflight.py` before starting and fail closed if the mounted catalog is missing, corrupt, empty, or lacks required schema columns.

## Prepare runtime data

From the VPS checkout:

```bash
cd cloud
mkdir -p runtime/stream_cache runtime/logs
# Copy a SQLite-consistent catalog snapshot to runtime/catalog.db.
# Do not copy a live WAL pair piecemeal.
sqlite3 runtime/catalog.db 'PRAGMA quick_check;'
MOVIA_CATALOG_DB="$PWD/runtime/catalog.db" python ../backend/cloud_runtime_preflight.py
```

The container itself mounts the snapshot at `/app/backend/catalog.db`, so no path override is needed inside Compose.

For the first migration, create the snapshot on the source host with SQLite's backup API or `VACUUM INTO`, transfer the completed snapshot, then verify `PRAGMA quick_check` on the VPS.

## Build and start staging API

```bash
cd cloud
docker compose build api
docker compose up -d api
docker compose ps
curl -fsS http://127.0.0.1:8080/health
```

Expected health fields include:

```json
{"status":"ok","service":"movia-cloud-control-plane","cloudMode":true,"p2pEnabled":false,"security":"cloud-direct-only"}
```

Verify the media-proxy kill switch:

```bash
curl -i 'http://127.0.0.1:8080/stream/test'
curl -i 'http://127.0.0.1:8080/stream?url=https%3A%2F%2Fexample.invalid%2Fvideo.m3u8'
```

Both requests must return HTTP 404 with `media_proxy_disabled`.

## Build an Android staging client

The Android control-plane endpoint is a build-time value. It must be an **HTTPS origin only** (scheme + host and optional port, with no path, query, fragment or credentials). Local development keeps the default `http://127.0.0.1:8888`; the local `/stream` P2P gateway remains a separate rollback/fallback path.

```bash
cd android
ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" \
  ./gradlew :app:assembleDebug \
  -PMOVIA_CONTROL_PLANE_URL=https://staging.movia.example
```

After installing that APK, read the headless agent snapshot/diagnostics and verify `network.controlPlaneBaseUrl` / `backend.controlPlaneBaseUrl` equals the expected HTTPS origin before any playback acceptance run. A normal build without `MOVIA_CONTROL_PLANE_URL` is the rollback build and resolves back to `http://127.0.0.1:8888`.

## Metadata job

The Compose file includes a bounded one-shot metadata job; it is not an always-running loop:

```bash
docker compose --profile jobs run --rm metadata-job
```

Schedule it only after staging parity checks. The job uses existing metadata freshness rules and a bounded 200-row batch. Direct-stream bulk scanning is intentionally **not** added here: the next implementation must use a priority/delta queue rather than reintroducing full catalog rescans.

## Pre-cutover parity gate

Before changing Android's endpoint, verify at minimum:

1. `PRAGMA quick_check` and catalog row/schema parity.
2. `/health` is direct-only (`p2pEnabled=false`).
3. `/stream*` proxy endpoints are disabled.
4. control-title direct resolver results contain no magnets or local proxy URLs.
5. RU/UK quality/audio contract remains unchanged.
6. signed URL expiry behavior remains unchanged.
7. provider reliability/circuit tests pass.
8. control playback remains within the existing <=10 second READY budget when Android consumes the cloud URL directly.

Do not disable the phone rollback path until this gate and the later 100-movie + series acceptance gate pass.
