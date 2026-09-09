# Movia project state — canonical 0.9.32 baseline

## Identity

- package: `app.movia.android`
- versionName: `0.9.32`
- versionCode: `302`
- exact installed APK SHA-256: `25e9c2a3a49e4649376b871f469bec3df39160c7ef86743d317a41855f23f49b`
- APK source commit: `35d1d9f82396eab7641359633740a642586eb254`
- recovery artifact: `release/Movia-0.9.32-code302.apk`
- canonical GitHub repository: `KatiSim/Movia`

## Android — PASS

Full gate before this recovery publication:

- `:app:testDebugUnitTest`
- `:app:compileDebugKotlin`
- `:app:assembleDebug`
- `:app:compileDebugAndroidTestKotlin`
- Gradle result: `BUILD SUCCESSFUL`, 58 actionable tasks

Installed package was updated via Shizuku/rish after local/remote APK hashes matched.

Current major UI/runtime features include stable catalog return position, mediaId-based Details identity, person filmographies, unified glass BottomBar, gold/slate design system, Media3 media notification artwork and correct notification PendingIntent, exact series routing, quality/voice selection, player/season-sheet gesture fixes and one-buffering-spinner UI.

## Backend — PASS

- runtime: `$HOME/projects/media-parser`
- service: `movia-media-parser`
- health: `http://127.0.0.1:8888/health` → HTTP 200
- full backend regression: `198 tests`, `OK`
- Person API live validation: Russian/English Bryan Cranston and Bong Joon-ho names resolved profile images and Movia project lists.

## P2P runtime — PASS

- TorrServer: `MatriX.144.1`, loopback `127.0.0.1:18090`, `/echo` HTTP 200
- TorrServer SHA-256: `bb7e9b4d0dc894f8da3e32496e7487be93b8f8b04ada549396a7ab4dc85ea63b`
- aria2: `1.37.0`, loopback RPC `127.0.0.1:6800`
- architecture: direct source preferred → TorrServer bounded local P2P → aria2 fallback/metadata

## Catalog

At recovery observation time:

- runtime SSOT: `$HOME/projects/media-parser/catalog.db`
- rows: 71,899
- schema: 4
- revision: 9718
- normalization: 1
- quick_check: ok
- observed DB SHA-256: `5c688c34c899f8d3cc3319db8714a34a2d9a5e1325298c1e8b298d2e943a97a9`

The DB changes continuously and is not committed to Git. Export/import helpers are tracked under `scripts/`.

## MCP / agent

- native agent schema: 2
- source-registered Movia MCP tools: 30
- local MCP health observed: `127.0.0.1:8940/healthz` HTTP 200

## Design SSOT

- `android/.../ui/theme/ColorTokens.kt` — executable token source
- `docs/DESIGN_SYSTEM_0.9.32.md` — human-readable design reconstruction
- `docs/INTERACTION_LOGIC_0.9.32.md` — behavior/button reconstruction

## Recovery policy

GitHub stores source, exact APK, schemas, design/behavior specs, tests, runit templates and setup scripts. It excludes secrets, keystore, caches/logs and mutable DB payloads. Android private user data is not recoverable after uninstall unless separately backed up.
