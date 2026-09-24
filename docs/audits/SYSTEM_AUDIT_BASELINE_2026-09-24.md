# Movia system audit baseline — 2026-09-24

Captured before the first patch of this audit. Existing dirty/untracked work is treated as CURRENT and was not reverted.

## Source/build
- Branch: `ui-overhaul-0.10.0`
- Commit: `5fffa5df3b310c541a77140c491b806f61942ea4`
- Package: `app.movia.android`
- Source `versionName`: `0.0.1`; installed `versionName`: `0.0.1`; versionCode `307`.
- Build types: debug, release, benchmark. Current release has R8 + resource shrinking enabled; benchmark derives from release.
- Current tree already contains in-progress architecture/performance work (ViewModels, Paging 3, Coil 3, Macrobenchmark/Baseline Profile, Room schema v4, domain use cases). This audit preserves it.

## Runtime baseline
- Backend endpoint configured: `http://127.0.0.1:8888`.
- Direct `/api/home` probe returned HTTP 200; observed wall time 145 ms. Response-size capture failed because the temporary output path was unavailable; no byte count is claimed.
- Cold launch samples (`am force-stop` + `am start -W`): 2136 ms and 1959 ms TotalTime.
- Home content was visibly present without visiting another tab after the cold-launch observation window (4 s). Exact first-meaningful-content timestamp was not instrumented yet.
- Required tab tour executed: Home → Catalog → Home → My → Home. No FATAL EXCEPTION / app ANR / StrictMode network violation was found in the captured log slice.

## Memory
Cold launch + 4 s observation:
- TOTAL PSS: 228084 KB
- TOTAL RSS: 364676 KB
- Native Heap PSS: 19764 KB
- Graphics PSS: 74612 KB
- Activities: 1

After required tab tour (device was under substantial swap pressure, so compare cautiously):
- TOTAL PSS: 126868 KB
- TOTAL RSS: 155196 KB
- Native Heap PSS: 2036 KB
- Graphics PSS: 6064 KB
- TOTAL SWAP PSS: 102068 KB
- Activities: 1

These are diagnostic snapshots, not a stable benchmark. Full two-tour stabilized memory acceptance remains required.

## Persistence/runtime facilities
- Room DB: `databases/movia.db` 102400 bytes; WAL 436752 bytes; SHM 32768 bytes.
- DataStore: `files/datastore/movia_preferences.preferences_pb` 1979 bytes.
- WorkManager is present; downloads use unique work + `ExistingWorkPolicy.KEEP` in CURRENT source.
- Installed Baseline Profile marker exists (`files/profileInstalled`).

## CURRENT architecture observations
- Home already exposes `StateFlow<HomeFeedSnapshot>` and `HomeViewModel` consumes it.
- Home recommendation and animation fallback are dispatched off Main in `HomeViewModel`.
- Catalog already has a screen ViewModel, Paging 3, 300 ms debounced search, generation/latest-request protection, and a `CatalogPage(items,total)` API.
- Artwork CURRENT already uses Coil 3 `AsyncImage` with one application `ImageLoader`, 32 MiB memory cache and 128 MiB disk cache.
- Debug CURRENT already enables StrictMode `detectNetwork().penaltyLog()`.
- Details repository CURRENT already has a bundled details cache/API.
- Lifecycle Compose, Paging, Room, WorkManager, Coil, Media3, ProfileInstaller and Baseline Profile dependencies are present.

## Confirmed residual risks before patching
- `MoviaApp` still calls `DemoCatalogRepository.init()` and `AgentControlRuntime.start()` from composition-side app setup; Agent runtime also calls repository init. Startup ownership remains mixed.
- `MoviaApp` still contains a `findFullByTitle` fallback path; title identity remains in compatibility paths.
- Agent service/runtime contains `runBlocking`; calls inspected by grep use IO for network/database work, but full action-contract audit is still required.
- Home state distinguishes refresh/error fields but does not yet model the requested complete load-state vocabulary as a normalized app error model.
- Full action matrix, two-tour memory, offline/failure injection, process recreation, player instance count and network request counts still require dedicated QA.

## Phase-0 reproduction result
The historical “Home only updates after switching tabs” symptom did **not** reproduce on the currently installed/current source state: Home displayed content after cold launch without tab warming. This means CURRENT already includes changes beyond the older problem description; subsequent patches must target remaining measured defects rather than reimplementing old assumptions.
