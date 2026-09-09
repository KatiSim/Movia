# Movia 0.9.32 — recovery blueprint

This is the canonical engineering recovery specification for the phone build installed on 2026-09-09.

## Baseline identity

- GitHub: `https://github.com/KatiSim/Movia`
- default branch after recovery publication: `main`
- source-code commit used to build the installed APK: `35d1d9f82396eab7641359633740a642586eb254`
- package: `app.movia.android`
- versionName: `0.9.32`
- versionCode: `302`
- APK: `release/Movia-0.9.32-code302.apk`
- APK size: `24,321,643` bytes
- APK SHA-256: `25e9c2a3a49e4649376b871f469bec3df39160c7ef86743d317a41855f23f49b`

## What GitHub can restore

The repository contains or documents:

- complete Android source and Room schemas;
- the exact installed APK artifact;
- UI/design specification and interaction contracts;
- backend source/tests;
- cloud direct-only runtime;
- Termux MCP source;
- runit service definitions;
- aria2 configuration template;
- TorrServer pinned version/download/checksum;
- build/install/verify scripts;
- secret variable names without values;
- runtime catalog manifest and rebuild procedure.

## What uninstall destroys

Android uninstall deletes the app's private data. Therefore personal Room/DataStore state such as viewing history, My List, app preferences and locally managed download records is not recoverable from source Git unless it was exported/backed up before uninstall. The code and product behavior remain fully recoverable.

## Phone-local backend architecture

Android talks to `http://127.0.0.1:8888` by default.

`movia-media-parser` (`backend/streamer.py`) provides:

- health and catalog/details APIs;
- exact movie/series stream discovery;
- person/filmography API;
- direct-stream validation/ranking;
- bounded local P2P fallback;
- adjacent episode prewarm.

P2P runtime:

- TorrServer `MatriX.144.1`, loopback `127.0.0.1:18090`, primary local torrent stream engine;
- aria2 `1.37.0`, loopback RPC `127.0.0.1:6800`, metadata/fallback infrastructure;
- torrent cache defaults to ~0.5 GiB policy and is periodically pruned.

Cloud runtime under `cloud/` is deliberately direct-only: `MOVIA_CLOUD_MODE=1` forces P2P off and disables `/stream` media proxy routes.

## Toolchain captured from the working phone

- Python `3.14.6`
- Node `24.18.0`
- npm `11.19.1`
- OpenJDK runtime `21.0.12` (Android source compatibility/JVM target is 17)
- Gradle wrapper `8.11.1`
- Kotlin `2.1.0`
- compileSdk/targetSdk `35`, minSdk `26`
- Android platform installed: 35 and 36
- build-tools installed: 34.0.0 and 36.0.0
- Compose BOM `2025.01.01`
- Media3 `1.9.3`
- Room `2.8.4`
- WorkManager `2.11.0`
- DataStore `1.2.1`

The Gradle wrapper is authoritative for Gradle itself. Java 17+ is required by the project; the captured phone happens to run JDK 21.

## Runtime catalog snapshot manifest

At recovery capture time:

- path: `$HOME/projects/media-parser/catalog.db`
- size: ~757 MiB
- rows in `movies`: `71,899`
- schema version: `4`
- catalog revision: `9718`
- normalization version: `1`
- SHA-256 at observation: `5c688c34c899f8d3cc3319db8714a34a2d9a5e1325298c1e8b298d2e943a97a9`
- `PRAGMA quick_check`: `ok`

The DB is live mutable runtime data and is intentionally not committed into Git history. It can be rebuilt from backend/catalog tooling when provider/TMDB credentials are supplied privately. If a byte-identical runtime DB is required, export a SQLite backup before device loss and store it as a separate GitHub Release asset or private backup; source Git alone cannot recreate the same continuously changing revision byte-for-byte.

## Service graph

Expected runit services:

- `movia-media-parser`
- `movia-stream-enricher`
- `movia-stream-enricher-log`
- `movia-cache-pruner`
- `movia-torrserver`

`movia-media-parser/run` also ensures the localhost aria2 RPC daemon is available before starting `streamer.py`.

## TorrServer pin

- upstream: `YouROK/TorrServer`
- release: `MatriX.144.1`
- Android ARM64 asset: `TorrServer-android-arm64`
- URL: `https://github.com/YouROK/TorrServer/releases/download/MatriX.144.1/TorrServer-android-arm64`
- expected size: `61,890,416` bytes
- SHA-256: `bb7e9b4d0dc894f8da3e32496e7487be93b8f8b04ada549396a7ab4dc85ea63b`
- license: GPL-3.0 upstream; the binary is not embedded in the Movia APK/repository.

## Android architecture

UI: Jetpack Compose Material 3.

Playback: one Media3/ExoPlayer session owned by `MoviaPlaybackRegistry`/`MoviaPlaybackService` and consumed by full player, mini-player, notification and agent API.

Persistence:

- Room `MoviaDatabase` schema versions 1 and 2 are committed under `android/app/schemas/`;
- DataStore `movia_preferences` stores app/playback preferences;
- WorkManager manages download jobs.

The native loopback agent allows headless catalog/playback/diagnostic operations without opening UI.

## Backend architecture

Primary source files:

- `backend/streamer.py` — HTTP control plane and local stream gateway;
- `backend/catalog_api.py` — catalog/details/person APIs;
- `backend/tmdb_client.py` — TMDB metadata/person client;
- `backend/collaps_provider.py` and provider adapters — direct discovery;
- `backend/torrent_resolver.py` — torrent discovery/classification;
- `backend/cache_pruner.py` — bounded local cache;
- `backend/background_network_budget.py` — mobile/Wi-Fi budget policy.

Direct media is preferred. Local P2P is bounded temporary fallback. The cloud staging architecture never proxies P2P/video through the VPS.

## Build identity

`android/app/build.gradle.kts` defines:

- applicationId `app.movia.android`
- version `0.9.32 (302)`
- default control plane `http://127.0.0.1:8888`
- release/debug signing both point to `$HOME/.android/debug.keystore` in this development baseline.

The private keystore itself is intentionally not stored in Git. After a full uninstall, a newly generated debug keystore can install the package. To update an already-installed APK without uninstalling, Android requires a key matching the currently installed signature.

## Verification gates captured before publication

- backend full regression: `198 tests`, `OK`;
- Android full gate: `testDebugUnitTest + compileDebugKotlin + assembleDebug + compileDebugAndroidTestKotlin`, `BUILD SUCCESSFUL`, 58 tasks;
- live backend health: HTTP 200 on `127.0.0.1:8888/health`;
- live Person API: Russian/English Bryan Cranston and Bong Joon-ho queries returned real profile images and catalog projects;
- APK copied to Android through Shizuku/rish with matching local/remote SHA before install;
- installed package reported `0.9.32 / 302`.

## Recovery entry points

- fastest app-only restore: `RESTORE.md` section “App-only restore”;
- environment setup: `scripts/setup-local-runtime.sh`;
- build: `scripts/build.sh`;
- install: `scripts/install.sh`;
- verification: `scripts/verify-project.sh` and `scripts/health-check.sh`;
- visual reconstruction: `docs/DESIGN_SYSTEM_0.9.32.md`;
- behavior reconstruction: `docs/INTERACTION_LOGIC_0.9.32.md`.
