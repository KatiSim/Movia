# Movia

Movia is an Android media catalog and playback client backed by the current
media-parser service and a local agent/MCP control plane.

## Current version

- versionName: 0.9.23
- versionCode: 293
- package: app.movia.android
- status: current baseline/checkpoint; not labelled stable

The installed phone control point is device 24069PC21G. The source of truth used
for this canonicalization is the Termux workspace documented in
reference/CURRENT_PHONE_STATE.json.

## Architecture

- Android — Compose UI, catalog/search, Media3 playback, Room schema, and the
  native Movia Agent runtime. See android/.
- Backend — media-parser catalog/search/discovery, playback resolver, streamer
  and P2P helpers. See backend/.
- Catalog — schema/migration documentation and an external catalog.db manifest.
  The multi-hundred-megabyte DB is not stored in Git history.
- Playback — Android Media3 client plus backend stream/torrent resolution.
- Agent/MCP — native Android agent API and the Termux MCP integration. See
  agent/.

## Build

Requirements are listed in RESTORE.md.

    cd android
    ./gradlew --no-daemon assembleDebug

Generated build directories are ignored. The current checked APK artifact is
described by release/README.md and is uploaded to the baseline GitHub Release
rather than committed to every source commit.

## Install

Install without clearing application data:

    adb install -r release/Movia-0.9.23-code293.apk

Verify the package and version after installation with
bash scripts/health-check.sh --package.

## Services

The current Termux service definitions are under agent/services/. The observed
services are:

- movia-media-parser — currently runs streamer.py;
- movia-stream-enricher — catalog enrichment worker;
- movia-stream-enricher-log — log forwarder;
- movia-cache-pruner — runtime cache pruning;
- Termux MCP — started from agent/mcp/start.sh.

Runtime paths and current health observations are recorded in
PROJECT_STATE.md. The source definitions do not contain runtime credentials.

## Verification

Run:

    bash scripts/verify-project.sh
    bash scripts/restore-check.sh
    bash scripts/health-check.sh

The verifier returns PASS only when every requested check, including live
service checks, succeeds. A current baseline may therefore remain a valid
source checkpoint while verification reports FAIL for a real unavailable
service. No document in this repository treats an unverified playback path as
fixed.

## Restore

Start with RESTORE.md. It is the single recovery entry point and documents
source checkout, Termux setup, catalog recovery, secrets, services, APK
build/install, agent provisioning and verification.
