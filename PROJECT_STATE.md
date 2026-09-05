# Movia project state

This file records the verified phone/project baseline. Claims marked PASS are based on checks executed on the current device/source state; unresolved UI scope is kept separate from playback/runtime verification.

## Current version

- package: `app.movia.android`
- versionName: `0.9.32`
- versionCode: `302`
- canonical source root: `/storage/emulated/0/Movia`
- Android working source: `/data/data/com.termux/files/home/projects/movia`
- backend working source: `/data/data/com.termux/files/home/projects/media-parser`
- MCP working source: `/data/data/com.termux/files/home/termux-mcp`
- code baseline commit: `37b7f0524f0dc7990d21e4262302e186ed942ce4`

## Android

Status: PASS for the audited build gate.

The current source contains the Compose UI, catalog/search stack, Room state, native agent runtime, and AndroidX Media3/ExoPlayer playback. HLS and DASH are explicit Media3 dependencies. The verified command set completed successfully:

- `testDebugUnitTest`
- `compileDebugKotlin`
- `assembleDebug`

The resulting debug APK was installed in-place and the installed package remained `0.9.32` / code `302`.

Generated APK/build outputs are verification artifacts only and are excluded from Git.

## Backend

Status: PASS for the audited runtime and selected test suite.

- service: `movia-media-parser`
- active health endpoint: `http://127.0.0.1:8888/health`
- observed health result: HTTP 200
- legacy endpoint `127.0.0.1:5001`: retired/unreachable
- selected playback/catalog/backend suite: 71/71 PASS after contract-alignment fixes
- `movia-cache-pruner`: running during audit
- `movia-stream-enricher`: down during audit; not required for the verified direct-HLS path

Runtime databases, caches, logs, backups and `.env` are excluded from Git.

## Catalog

- runtime SSOT: `/data/data/com.termux/files/home/projects/media-parser/catalog.db`
- rows in `movies`: 65,337 at audit time
- schema version: 4
- catalog revision: 6578
- normalization version: 1
- policy: runtime SSOT remains outside Git; source/schema/migration logic is versioned

## Playback

Status: PASS for the explicitly tested movie/HLS path; this is not a universal provider/title claim.

Verified on the installed application:

1. A real catalog title (`Сплит`) resolved to a concrete Collaps HLS candidate.
2. Media3 reached `READY`.
3. `isPlaying=true` was observed.
4. Playback position advanced across observations.
5. The player was paused.
6. Voice selection was changed to another concrete candidate/track.
7. The requested voice became the active voice and the native agent operation completed while the player remained paused.

The paused-selection completion policy was fixed during the audit: paused track/stream changes no longer require fabricated timeline movement, while actively playing changes retain the stricter READY + playing + position-movement evidence gate.

## MCP / agent

- native Android agent schema version: 2
- Termux MCP source: synchronized into `agent/mcp/`
- TypeScript package: 5.9.3
- direct TypeScript typecheck: PASS (`node node_modules/typescript/bin/tsc -p tsconfig.json`)
- direct TypeScript build: PASS (`node node_modules/typescript/bin/tsc -p tsconfig.build.json`)
- note: the `npm run` wrapper did not resolve `tsc` in its PATH on this Termux environment, but the installed compiler itself executed both configurations successfully

## Repository policy

Git contains reproducible source, tests, ADRs, scripts and baseline metadata. It excludes:

- Android/Gradle build products and APKs
- live catalog databases and sidecars
- stream/torrent caches
- backend backups and runtime state
- logs and diagnostics
- `.bak-*`, `.trashed-*`, `.orig` and scratch projects
- MCP `node_modules`/`dist`
- secrets, tokens, keys and `.env`

## Remaining active UI specification

The separate UI specification is stored at `docs/TZ_UI_PLAYBACK_LAYOUT_2026-09-03.md`.

Source inspection shows partial implementation already exists:

- the home hero card body opens Details and its central Play surface invokes playback;
- Details already contains conditional Cast and Director sections;
- the Details top app bar uses `WindowInsets.statusBars`.

However the full specification is **not marked PASS** because the current source inspection does not establish all requirements, notably the explicit extra 12.dp top clearance and the complete Crew/technical-details block, and the full UI acceptance matrix has not been re-run on-device in this audit.

## Last verified baseline

- date: 2026-09-05
- branch: `main`
- code baseline commit: `37b7f0524f0dc7990d21e4262302e186ed942ce4`
- Android build: PASS
- selected backend suite: PASS (71/71)
- backend runtime health: PASS on port 8888
- basic real HLS playback: PASS
- paused voice switch: PASS after fix
- MCP direct TypeScript typecheck/build: PASS
- full provider/title/series coverage: not claimed
- separate UI specification: partially implemented, acceptance pending
