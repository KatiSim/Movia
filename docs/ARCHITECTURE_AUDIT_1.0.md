# Movia 1.0 — Architecture Audit

Date: 2026-09-06
Block: 0 — Architecture / baseline audit
Status: AUDIT COMPLETE; NO PRODUCTION CODE CHANGED IN THIS BLOCK

## 1. Purpose and evidence boundary

This document records the architecture and verified technical state observed before the Movia 1.0 stabilization sequence. It is intentionally evidence-based: PASS is used only for checks executed during this block; observations from source inspection are separated from runtime verification.

Target Definition of Done supplied by the product owner:

- one-click playback for selected movies, series, animation and anime;
- public Android APK;
- APK target size <= 30 MB;
- streaming playback without storing media files locally on the device;
- final acceptance: 100 random movies plus a separate series control sample;
- required playback success rate: 98–100%;
- version 1.0.0 only after 100% acceptance;
- Dark Amber visual language remains fixed; UI changes require product-owner approval.

This block did not start live playback, alter application behavior, restore/delete pre-existing files, or reconcile active-vs-canonical source drift.

## 2. Current verified runtime baseline

### Android

- package: `app.movia.android`
- installed/source version: `0.9.32` / versionCode `302`
- compileSdk: 35
- minSdk: 26
- targetSdk: 35
- player: AndroidX Media3 / ExoPlayer 1.9.3
- explicit playback modules:
  - `media3-exoplayer`
  - `media3-exoplayer-hls`
  - `media3-exoplayer-dash`
  - `media3-ui`
  - `media3-session`
- current active debug APK built during this audit:
  - path: `/data/data/com.termux/files/home/projects/movia/app/build/outputs/apk/debug/app-debug.apk`
  - size: 24,094,734 bytes
  - sha256: `2ca5e01e98bd98346c6cb183d76efee4ed6daad078ed20ac8c1d4bfa2c1b307d`

The current debug APK is below the requested 30 MB size ceiling. This is not evidence that a future public release APK is ready or correctly signed.

### Backend

Current service state observed:

- `movia-media-parser`: RUNNING
- `movia-cache-pruner`: RUNNING
- `movia-stream-enricher`: DOWN, normally up
- `movia-stream-enricher-log`: DOWN, normally up

`GET http://127.0.0.1:8888/health` returned:

```json
{"status":"ok","service":"movia-p2p-streamer-on-demand","port":8888,"security":"isolated-localhost"}
```

### Catalog

Read-only query against the live runtime SSOT `catalog.db` returned:

- movies rows: 67,186
- schema_version: 4
- catalog_revision: 7428
- normalization_version: 1

The row count and revision are runtime values and may continue to change while catalog synchronization runs. The database remains runtime state and is not a Git artifact.

### MCP / native agent

The installed native Movia agent exposes schema/API version 2 on loopback `127.0.0.1:8899` and includes capabilities for:

- hot snapshot;
- domain playback;
- catalog/search/people/details;
- stream selection by stable stream ID, quality and voice;
- next/previous episode;
- settings;
- operation tracking;
- diagnostics/events;
- library and downloads.

No native `movia_acceptance` action is present in the current manifest.

## 3. Current architecture map

### 3.1 Android application layers

```text
Compose UI
  |
  +-- Home / Catalog / Search / Details / Library / Player / Settings
  |
  +-- CatalogRepository (HTTP client)
  |      |
  |      +--> http://127.0.0.1:8888
  |
  +-- PlaybackSession
         |
         +-- PlaybackRequest
         +-- DomainPlaybackResolver
         |      |
         |      +--> http://127.0.0.1:8888/api/movie/{id}/stream
         |      +--> http://127.0.0.1:8888/resolve
         |
         +-- StreamDeduplicator
         +-- StreamRanker
         +-- StreamFailurePolicy / StreamProblemTracker
         +-- StreamRequestProfile
         +-- Media3 ExoPlayer
                |
                +-- HLS
                +-- DASH
                +-- direct HTTP(S)
                +-- local P2P gateway at 127.0.0.1:8888/stream
```

The application maintains separate identity spaces for provider-level stream variants (voice/quality/provider) and Media3 internal audio/video track groups. That separation is architecturally correct and should be preserved.

### 3.2 Backend playback path

```text
Android resolver request
  |
  v
streamer.py
  |
  +-- exact catalog identity binding
  +-- cache/revision lookup
  +-- provider fan-out
  |      |
  |      +-- direct/balancer branch
  |      +-- torrent branch
  |
  +-- stream validation / identity binding
  +-- episode scoping
  +-- runtime ranking
  |
  v
sanitized Stream candidates
```

Observed backend behavior includes:

- cache keys tied to catalog revision;
- exact catalog identity checks on HTTP playback routes;
- stable stream IDs and semantic variant identity;
- secret/header sanitization before persistence/transport;
- direct and torrent provider branches executed concurrently;
- runtime health/startup evidence in ranking;
- no unconditional rule that direct HTTP must beat a healthier P2P candidate.

### 3.3 P2P path

```text
magnet candidate
  |
Android PlaybackSession
  |
http://127.0.0.1:8888/stream?...
  |
streamer.py
  |
aria2 RPC
  |
torrent_cache / selected media file / range readiness
  |
HTTP byte stream back to Media3
```

Torrent state, cache leases, selected file metadata and range readiness are handled by the backend rather than Media3 directly.

### 3.4 Automation/control plane

```text
Jarvis MCP
  |
active Termux MCP source
  |
Movia native agent at 127.0.0.1:8899
  |
PlaybackSession / catalog / settings / library / diagnostics
```

The native agent is a test/control surface. It is not the same service as the playback/catalog backend at port 8888.

## 4. Playback architecture findings

### Already implemented

The current source already contains substantial portions of the future stabilization plan:

- stable stream identities;
- provider metadata and headers on stream candidates;
- health/startup/failure fields on candidates;
- stream failure memory;
- candidate ranking with requested voice and quality;
- HLS and DASH Media3 modules;
- fallback/reload logic;
- in-place Media3 track switching when candidate identity allows it;
- series season/episode request fields;
- next/previous episode helpers;
- playback progress model;
- Auto Next setting;
- provider-level voice/quality selection separate from Media3 track identity.

### Quality -> Audio is only partially strict

`StreamSettingsSelection.voiceOptions(streams, quality)` filters voices to the selected quality when matching candidates exist. However, when no candidate matches the requested quality, it falls back to the complete usable stream list:

```text
qualityScoped.ifEmpty { usable }
```

`select()` similarly tries exact voice+quality first, then quality-only, then voice-only.

Therefore the desired strict product contract — "after choosing a quality, display only voices actually available at that quality" — is not yet proven as a strict invariant.

### Default voice behavior

The current `voiceRank` already prefers labels such as dubbing, LostFilm, Red Head Sound, HDrezka and other Russian variants ahead of Original. Preferences persist an `audio` value, whose default is currently `Auto`.

This is useful groundwork but is not yet equivalent to a formally tested product rule "automatically select the best available Russian translation" across the full provider set.

## 5. Series architecture findings

Series support is not starting from zero. Current code includes:

- canonical season/episode fields in playback requests;
- episode-specific backend filtering;
- next/previous episode title logic;
- PlayerScreen episode selection;
- playback progress persistence infrastructure;
- Auto Next preference and UI control.

The current preference default is `autoNextEnabled=true`, but the product requirement is that Auto Next remains governed by the existing user setting, not forced during stabilization.

Full cross-title series acceptance has not been executed in this block.

## 6. Catalog and recommendation findings

The Android catalog repository is an HTTP client backed by the loopback backend, despite its historical `DemoCatalogRepository` name. It supports:

- home feeds;
- paged catalog;
- search;
- people search;
- details;
- similar content;
- sequels/prequels.

Current recommendation logic already uses:

- playback history;
- favorites;
- genre affinity;
- director affinity;
- content type;
- rating;
- popularity;
- deterministic shelf diversification.

Direct actor/cast affinity weighting was not observed in the inspected recommendation scoring path, so the requested actor-based recommendation revision remains open.

A lightweight daily new-release tracker satisfying the 1.0 requirement was not proven. `live_tracker.py` is primarily runtime/aria2/log monitoring, while `rss_monitor.py` contains basic RSS ingestion helpers. These are not sufficient evidence of a once-per-day production release-discovery pipeline with progressive quality upgrades.

## 7. Provider and resolver findings

Observed provider components include:

- Collaps direct HLS resolution;
- Rezka adapter path via legacy compatibility code;
- torrent providers including Rutor, Apibay, YTS and series/anime-specific sources such as EZTV/Nyaa;
- balancer/direct-provider integration.

Provider-health fields (`healthScore`, `startupLatencyMs`, `recentFailureCount`, `providerReliability`) already exist in the Android candidate model and runtime ranking uses them.

What was not proven in this audit is a complete durable feedback loop that:

1. records provider success/failure over time;
2. calculates a provider-level reliability score from actual playback outcomes;
3. automatically disables/re-enables degraded providers according to a documented threshold.

That future block should extend existing infrastructure rather than create a parallel ranking system.

## 8. Existing acceptance infrastructure

The active Android checkout already contains acceptance prototypes beyond what is currently synchronized into canonical Git.

Observed active files include:

- `acceptance/movia_acceptance.py`
- `acceptance/100_playback_stability.py`
- `acceptance/08_playback_coverage_random.py`
- existing 01–07 headless acceptance scripts.

Facts from inspection:

- `movia_acceptance.py` already has Android/backend/series checks, Media3 READY evidence, first-frame evidence, quality and voice switching, JSON summary and a >=98% threshold.
- `100_playback_stability.py` repeats one playback case up to 100 times. It is not the product owner's required sample of 100 random movies.
- `08_playback_coverage_random.py` currently samples 20 movies + 20 series items with a deterministic seed.
- no `movia_acceptance` native MCP action is present in the installed native agent manifest.

Therefore Block 1 should reconcile and harden existing acceptance code rather than build an unrelated framework from zero.

## 9. Source split-brain / drift discovered

This is a release-blocking configuration-management issue.

### 9.1 Canonical repository MCP drift

Canonical Git root:

`/storage/emulated/0/Movia`

At the beginning of this block, `main` matched `origin/main`, but seven tracked MCP source files were already missing from the canonical working tree:

- `agent/mcp/src/android-tools.ts`
- `agent/mcp/src/config.ts`
- `agent/mcp/src/job-manager.ts`
- `agent/mcp/src/movia-client.ts`
- `agent/mcp/src/movia-tools.ts`
- `agent/mcp/src/server.ts`
- `agent/mcp/src/tools.ts`

The active runtime copies under `/data/data/com.termux/files/home/termux-mcp/src/` exist and their SHA-256 hashes exactly match the committed HEAD versions.

No restoration or deletion was performed in Block 0. These canonical working-tree deletions remain intentionally unstaged and unresolved.

### 9.2 Active Android source drift

The active Android development checkout is not identical to the canonical Git snapshot.

Confirmed differences include:

- `AgentControlRuntime.kt`
- `StreamFailurePolicy.kt`
- `PlaybackSession.kt`
- `StreamFailurePolicyTest.kt`
- active-only `Media3PlaybackStabilizationTest.kt`
- active-only recovery `.bak-*` files
- canonical-only `MoviaFullscreenController.kt`

The active `PlaybackSession.kt` contains a newer, uncommitted stabilization candidate with observed changes including:

- startup watchdog reduced to 10s;
- reload timeout reduced to 10s;
- new 10s stall watchdog;
- new 12s resolver timeout wrapper;
- shorter HTTP connect/read timeouts;
- smaller Media3 buffering targets;
- buffering-timeout recovery path;
- user-facing final message `Произошла ошибка: повторите`;
- explicit `retry()` method.

These changes built successfully during this audit, but they are not synchronized into canonical Git and were not created or committed in Block 0.

Before later stabilization blocks alter the same files, this drift must be reconciled deliberately to avoid overwriting valid work.

## 10. Critical blockers for the stated public-APK target

### P0-1 — current APK depends on a separate local Termux backend

The Android source hardcodes `http://127.0.0.1:8888` in at least:

- `CatalogRepository.kt`
- `DomainPlaybackResolver.kt`
- the torrent gateway path in `PlaybackSession.kt`.

The Android manifest contains no bundled backend service corresponding to the Python/aria2 backend, and no inspected Android assets/JNI payload showed that backend packaged inside the APK.

Therefore the current APK is not architecturally standalone: on the present phone it depends on the separately running Termux backend.

If "public Android APK" means installable on an ordinary Android device without Termux or a companion local backend, deployment architecture must change before 1.0 acceptance can be meaningful. This is an owner decision and a release blocker.

### P0-2 — active source and canonical Git are split

Production stabilization cannot safely proceed file-by-file while the active Android implementation and canonical Git snapshot disagree on core playback files.

### P0-3 — streaming-only target conflicts with the current offline-download subsystem

Current source includes a complete download path:

- `DownloadScheduler`
- `OfflineDownloadWorker`
- WorkManager dependency
- Room `downloads` table
- Download UI/settings/library state
- native agent download actions.

The 1.0 product target explicitly states streaming playback without local media file saving. The offline-download subsystem therefore requires a deliberate product decision: remove/disable it for 1.0 or revise the target. No removal was done in this block.

### P0-4 — release signing is not public-release ready

The current `release` build type uses the Android debug keystore (`debug.keystore`, alias `androiddebugkey`) and hard-coded debug signing values. That is not an appropriate production signing setup for a public 1.0 release.

## 11. High-priority technical debt

### P1

1. Strict Quality -> Audio invariant is not yet enforced in every fallback path.
2. Active timeout/error-handling improvements are outside canonical Git and require reconciliation plus acceptance testing.
3. Provider reliability data fields exist, but a durable provider scoring/automatic-disable loop was not proven.
4. Acceptance prototypes exist but are not yet the requested canonical 100-random-movie + series gate or native `movia_acceptance` MCP action.
5. `movia-stream-enricher` is down and its current architectural value is unproven.
6. Backend selected tests pass but emitted `ResourceWarning` messages for unclosed SQLite connections; this is technical debt even though the tests passed.
7. Actor/cast affinity was not observed in the current recommendation score.
8. A production once-daily new-release tracker with progressive quality/voice updates was not proven.

### P2

1. Active Android source still contains multiple `.bak-*`, diagnostic and recovery artifacts.
2. Android manifest currently allows cleartext traffic, consistent with the localhost HTTP architecture but undesirable as a broad public-release default.
3. `release` minification is disabled.
4. Offline-download dependencies contribute code and product surface that conflict with the stated streaming-only goal.
5. Canonical/active source ownership and synchronization process needs a single explicit SSOT before further refactoring.

## 12. Dependency map

### Runtime-critical for current architecture

- AndroidX Media3 ExoPlayer/HLS/DASH/UI/Session
- loopback backend at port 8888
- runtime `catalog.db`
- Python backend resolver/provider modules
- aria2 RPC for P2P/torrent path
- torrent cache + cache pruner for P2P operation
- DataStore for playback preferences
- Room for local library/progress/download state

### Automation/test dependencies

- Movia native agent at port 8899
- Termux MCP source/runtime
- Jarvis MCP adapter

The native agent/MCP stack is not required as the conceptual playback engine, but it is required for the current automated acceptance workflow.

### Candidate for later removal only after evidence

- Stream Enricher service
- legacy Zona compatibility/adapters
- stale backup/recovery source copies
- offline-download subsystem if streaming-only scope is confirmed
- unused provider adapters/dependencies

No component in this list should be removed solely because its name looks legacy. Removal requires usage search, tests and runtime acceptance.

## 13. Validation executed during Block 0

### Android active checkout

Command:

```sh
./gradlew testDebugUnitTest compileDebugKotlin assembleDebug --no-daemon
```

Result:

- PASS
- `BUILD SUCCESSFUL in 40s`
- 46 tasks reported up-to-date

This verifies that the current active, drifted Android checkout compiles and its current JVM unit-test task passes. It does not validate UI pixels or random-catalog playback coverage.

### Backend selected deterministic suite

Command:

```sh
python3 -m unittest \
  test_playback_variants.py \
  test_streamer_torrent_gid.py \
  test_catalog_sync.py \
  test_collaps_provider.py \
  test_lampa_compat.py \
  test_backend_stream_pipeline.py \
  test_torrent_playback_unit.py
```

Result:

- PASS
- 71 tests
- 0 failures
- 0 errors
- multiple `ResourceWarning` messages for unclosed SQLite connections were emitted.

### MCP TypeScript

Commands:

```sh
node node_modules/typescript/bin/tsc -p tsconfig.json
node node_modules/typescript/bin/tsc -p tsconfig.build.json
```

Result: PASS.

### Not executed in Block 0

- no 100-random-movie playback acceptance;
- no series control-sample acceptance;
- no live playback start;
- no UI screenshot acceptance;
- no release APK signing validation;
- no standalone-device test without Termux/backend.

## 14. Recommended execution dependencies for Blocks 1–12

The product sequence remains useful, but the following gates should be respected:

### Before modifying core playback files

1. Reconcile active Android stabilization changes with canonical Git.
2. Restore or intentionally resolve the seven missing canonical MCP files.
3. Decide whether the public APK must be standalone without Termux/local backend.
4. Confirm whether offline downloads are out of 1.0 scope as the written DoD states.

### Block 1 — Acceptance framework

Do not create a duplicate system. Audit and promote the existing active prototypes:

- `movia_acceptance.py`
- `100_playback_stability.py`
- `08_playback_coverage_random.py`

Then evolve them toward:

- one-command MCP/Jarvis execution;
- 100 random movies;
- separate series sample;
- reproducible seed/report;
- Media3 READY + advancing-position evidence;
- quality/voice switching;
- machine-readable failure classes;
- >=98% interim stabilization threshold;
- 100% required before version 1.0.0.

### Block 2 — Media3 stabilization

Start from the active uncommitted stabilization candidate rather than overwriting it. Validate startup, rebuffer, retry, timeout and final error state empirically.

### Block 3 — Quality -> Audio

Make the existing selection helper enforce strict quality-scoped voice lists and explicit fallback semantics. Preserve provider-level versus Media3-track identity separation.

### Block 4 — Provider reliability

Extend existing candidate health/reliability fields and runtime ranking with measured persistent provider evidence; avoid creating a competing ranker.

### Block 5 — Error handling

Unify user-facing error state, resolver/provider/player timeout classes and retry behavior. Reconcile with the already-active uncommitted error-handling work first.

### Block 6 — Series

Use existing episode/progress/Auto Next infrastructure and expand acceptance across multiple series, seasons and episode transitions.

### Block 7 — Media Details UI

Requires explicit UI approval before visual changes. Implement only approved Dark Amber changes.

### Blocks 8–10 — Metadata / release tracker / recommendations

Proceed only after playback acceptance infrastructure is stable enough to detect regressions. Metadata and catalog growth must not obscure playback reliability.

### Block 11 — Cleanup

Remove only components proven unused after full tests and runtime coverage. This is the correct point to prune legacy adapters and recovery debris.

### Block 12 — Release

Do not assign 1.0.0 until all release gates pass, including standalone deployment architecture, production signing, APK size and 100% final acceptance.

## 15. Owner decisions required before continuing past Block 0

1. Must the final public APK work on a normal Android device **without Termux or a separately installed/running localhost backend**? The current architecture cannot do that as-is.
2. Are the unsynchronized active Android stabilization changes in `PlaybackSession.kt`, `AgentControlRuntime.kt`, `StreamFailurePolicy.kt` and related tests intentional work that should be preserved and reconciled into canonical Git?
3. Confirm the written streaming-only target: should the existing offline-download feature be removed/disabled before 1.0, or should it remain as an optional feature?

These decisions affect architecture and should be resolved before core stabilization work is merged.
