# Movia final release report

Final status: **PASS**

## Release
- Package: `app.movia.android`
- Installed version: `0.9.23` (`versionCode 293`)
- Upgrade method: in-place `pm install -r`; no uninstall and no `pm clear`
- Generated APK SHA-256: `0f71e65762de5f3c8826999e051b741927516302be7bab7de1f935c31f756d75`

## Integrated release scope
Movia:
- `app/src/main/java/app/movia/android/ui/player/PlayerScreen.kt`
- `app/src/main/java/app/movia/android/ui/details/DetailsScreen.kt`
- `acceptance/README.md`
- `acceptance/07_final_acceptance.py`

media-parser:
- `balancer_integration.py`
- `catalog_api.py`
- `catalog_freshness_audit.py`
- `database.py`
- `live_catalog_sync.py`
- `playback_variant_audit.py`
- `stream_validation.py`
- `streamer.py`
- `test_catalog_sync.py`
- `test_playback_variants.py`
- `tmdb_client.py`
- `torrent_resolver.py`

Jarvis MCP final cold-bootstrap fix:
- `~/termux-mcp/src/movia-client.ts`: bootstrap command changed from Termux `/usr/bin/am --user current` to `/system/bin/am --user 0` so native MCP cold wake works on this HyperOS device without Shizuku dependency in the ordinary Movia agent path.
- Backup: `~/termux-mcp/.movia-cold-bootstrap-backup-20260829-214311/src/movia-client.ts`

## Backend gates
- Python compilation: PASS
- Targeted tests: **19/19 PASS**
- media-parser restarted using only the exact project process
- `127.0.0.1:8888` health: PASS
- Catalog sync worker: alive
- Last sync: successful, no `last_error`, not overdue
- Catalog rows: 48,598 at audit time
- Duplicate canonical `(media_type, tmdb_id)` groups: 0
- Invalid canonical TMDb IDs: 0

## Playback variant audit
Breaking Bad S01E01:
- 27 structurally playable streams in pre-release audit
- Real voice labels included: `LostFilm`, `Original (с субтитрами)`, `Авторский (Одноголосый)`, `Дубляж`, `Кубик в Кубе`, plus unknown where provider did not label
- Qualities included `1080p` and `720p`

Inception:
- 20 structurally playable streams
- Real labels included `Дубляж`; provider also returned unlabeled entries
- Qualities included `4K`, `1080p`, `720p`

No voice/quality labels were fabricated.

## Local Android build gate
Exact command:
`./gradlew :app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug :app:compileDebugAndroidTestKotlin --no-daemon`

Result: **BUILD SUCCESSFUL**
- Real Room/KAPT executed
- Unit tests executed
- Debug Kotlin compiled
- Debug APK assembled
- Debug Android-test Kotlin compiled
- 56 actionable tasks, 42 executed, 14 up-to-date

The build required only temporary process-scoped linker compatibility under `/tmp`; no Android global/secure/system settings were changed.

## Token/security
- Local bearer token persisted across upgrade
- App-private token hash matched local token hash
- App token mode: `600`
- Bridge bind address: `127.0.0.1`
- Unauthenticated requests: HTTP 401
- Bootstrap broadcast is wake-only and transfers no credential
- Ordinary Movia agent API advertises no UI or Shizuku requirement

## Authoritative acceptance 01–07
- `01_headless_cold`: PASS, 7/7
- `02_smoke`: PASS
- `03_benchmark`: PASS, 3000/3000 requests successful
  - `/health` p50 3.719 ms, p95 7.009 ms, p99 11.237 ms
  - `/snapshot` p50 7.139 ms, p95 11.102 ms, p99 16.458 ms
  - `/actions` p50 11.282 ms, p95 17.592 ms, p99 23.658 ms
- `04_operation`: PASS, `media.play` operation completed
- `05_breaking_bad`: PASS
  - exact S01E01
  - requested quality `720p`
  - requested voice `Кубик в Кубе`
  - active quality remained `720p`
  - active voice remained `Кубик в Кубе`
  - stable stream ID across 8 repeated snapshots
  - no fallback events
  - no reset events
- `06_mcp_inventory`: PASS
  - exactly **29 native `movia_*` tools**
  - no legacy action IDs
  - no token literals/plumbing in tool registration
- `07_final_acceptance`: PASS, **24/24 required checks**
  - custom rotating center buffering spinner confirmed
  - Material3 `ModalBottomSheet` season/episode UI confirmed
  - catalog freshness PASS
  - final Breaking Bad coverage: 26 streams, 5 real voices, 2 qualities
  - time to resolve: 17.107 ms
  - operation completion: 1689.478 ms

## Final real Jarvis MCP cold-state validation
After `am force-stop app.movia.android`, an actual localhost MCP `tools/call` to `movia_snapshot` returned:
- MCP total tools: 77
- native `movia_*` tools: **29**
- `movia_snapshot`: present
- `schemaVersion`: **2**
- `app.screen`: **HEADLESS**
- `app.uiAttached`: **false**
- `app.processAlive`: **true**
- MCP call error: false
- Foreground Activity remained another app (`com.deepseek.chat/.MainActivity`), not Movia

## Residual limitations
- Visual-only screenshot review, fullscreen chrome, and Android PiP presentation checks were intentionally not part of headless acceptance.
- Provider availability remains external and can vary over time; the release reports only labels and streams actually returned by providers.

No Android global/secure/system settings were changed during this release.
