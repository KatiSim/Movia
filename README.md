# Movia

Movia is an Android media catalog/player with a phone-local control plane, direct-stream discovery and bounded local P2P fallback.

## Canonical recovery baseline

- package: `app.movia.android`
- version: `0.9.32` / code `302`
- exact installed APK: `release/Movia-0.9.32-code302.apk`
- APK SHA-256: `25e9c2a3a49e4649376b871f469bec3df39160c7ef86743d317a41855f23f49b`
- APK source commit: `35d1d9f82396eab7641359633740a642586eb254`
- canonical branch: `main` (the recovery publication also keeps `integration` at the same head)

The repository intentionally contains the exact APK in addition to source so an app-only recovery does not require rebuilding.

## Recovery

Start with [`RESTORE.md`](RESTORE.md).

For a phone where only the Android app was deleted:

```bash
git clone https://github.com/KatiSim/Movia.git
cd Movia
bash scripts/install.sh
```

For a clean Termux/device recovery, follow the full procedure in `RESTORE.md` and `docs/RECOVERY_BLUEPRINT_0.9.32.md`.

## Documentation map

- [`docs/DESIGN_SYSTEM_0.9.32.md`](docs/DESIGN_SYSTEM_0.9.32.md) — exact visual system: logo, palette, gold glow/outlines, geometry, cards, Details, player, notification and bottom bar.
- [`docs/INTERACTION_LOGIC_0.9.32.md`](docs/INTERACTION_LOGIC_0.9.32.md) — button, navigation, playback, gesture, quality/voice, series and persistence logic.
- [`docs/RECOVERY_BLUEPRINT_0.9.32.md`](docs/RECOVERY_BLUEPRINT_0.9.32.md) — architecture, versions, services, runtime and rebuild identity.
- [`PROJECT_STATE.md`](PROJECT_STATE.md) — verified baseline snapshot.
- [`CURRENT_BASELINE.json`](CURRENT_BASELINE.json) — machine-readable baseline.
- [`SECRETS_SETUP.md`](SECRETS_SETUP.md) — private configuration names and rules; no secrets are stored in Git.

## Architecture

### Android

Jetpack Compose Material 3 UI, Media3/ExoPlayer, MediaSessionService, Room, DataStore, WorkManager and a native loopback control agent. Default backend URL is `http://127.0.0.1:8888`.

### Local backend

`backend/streamer.py` is the phone-local HTTP control plane. It serves catalog/details/person APIs, direct stream resolution and bounded local P2P fallback. `catalog.db` is mutable runtime data and is not committed to Git.

### P2P

Direct HTTP/HLS/DASH remains preferred. TorrServer MatriX.144.1 is the low-latency localhost torrent streaming sidecar; aria2 1.37.0 is localhost metadata/fallback infrastructure. Neither is exposed publicly.

### Cloud

The architecture is cloud-first/direct-only when cloud mode is enabled. Cloud mode explicitly disables P2P media proxying; provider/CDN URLs go directly to Android.

## Build

```bash
pkg install git python nodejs openjdk-21 curl aria2 termux-services
# Android SDK platform 35 and compatible build-tools are required.
bash scripts/bootstrap-debug-keystore.sh
bash scripts/build.sh
```

The normal build script does **not** overwrite the canonical recovery APK in `release/`.

## Verification

```bash
bash scripts/restore-check.sh
bash scripts/verify-project.sh
bash scripts/health-check.sh --full --package
```

Baseline gates before publication:

- backend: `198 tests`, `OK`
- Android: `testDebugUnitTest + compileDebugKotlin + assembleDebug + compileDebugAndroidTestKotlin`, `BUILD SUCCESSFUL`, 58 tasks
- live `127.0.0.1:8888/health`: HTTP 200
- live TorrServer `/echo`: HTTP 200

## Data boundary

Source, design, APK, service definitions and recovery tooling are recoverable from GitHub. Android private user data (history, My List, app preferences and local download records) is deleted by Android uninstall unless exported separately beforehand. The continuously changing 757 MiB runtime catalog is documented and can be exported with `scripts/export-runtime-catalog.sh`, but is intentionally not part of ordinary Git history.
