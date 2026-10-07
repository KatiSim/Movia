# Block 07: native Source Truth and concrete source selection — 2026-10-07

The active CatalogStreamService bypassed the former stream handler, so Source Truth source IDs did not reach native Media3 candidates. Native first frames could therefore decode without being recorded. Cached/discovered candidates sharing a logical source could also overwrite different concrete leaf IDs, and an exact requested leaf arriving after cached startup did not trigger a handover.

## Final changes

- CatalogStreamService overlays Source Truth only after exact identity scoping, validation and native torrent rewriting. Cached GET remains read-only and does not start provider work.
- The discovery worker registers nonempty scoped leaves before persistence, allowing subsequent native first-frame feedback to address a real indexed source.
- Discovery keeps distinct native concrete IDs even when they share a logical source. A cached flat ID is upgraded to the authoritative native ID; same-leaf URL reload preserves its selected ID.
- A late exact user-selected source now replaces the cached fallback. Catalog/episode validation and failed-source memory still apply. Automatic updates do not restart already decoded playback merely because another source arrives.
- All code is Movia-owned; the device reports movia-provider-registry and referenceRuntimeLoaded=false. No reference runtime or copied implementation was introduced.

## Verification

- Backend: 454/454 unit tests passed, including five new active-boundary/discovery regressions.
- Android: 246/246 unit tests passed, including seven source-selection/identity regressions. APK assembly and Android instrumentation-code compilation passed; instrumentation execution is not claimed.
- An intermediate Gradle invocation reached the 600-second job timeout while compiling instrumentation code. Its wrapper was confirmed stopped before the final serial build; final build exit code was zero.
- Version 335 / 0.0.1 installed with pm install -r -d; app data preserved. Built and installed SHA256: `388b95aa2f1b52fef6538ed8ee17366a5d06a4425f84922421ade055b70fd96c`.
- Cold headless launch passed. Physical display 0 was not used. No FATAL/ANR in the final app-process checks.

## Actual native movie proof

Catalog/MediaItem 158, Interstellar (2014), selected and active native ID `provider-item:v2:3f5bcc5ccecdf1324f3d76c1:605e6bcb4e121618cb9c02bf`.

- A real Rutor file decoded at 576p for 10,143,968 ms, with two decoder audio tracks: DUB (Лицензия), ru; English, en.
- The matching Source Truth source `src:d3dd427046000bba92ca60e09d813f29` became VERIFIED / MEDIA3_SUCCESS and the active API returned actualQuality=576p plus both actualAudioTracks. Provider labels and indexes were not guessed or expanded.
- Explicit metadata warmup took 11.77 s and read one 4 KiB EBML head (HTTP 206). The following pinned player startup took 10.83 s. This is a warmed smoke, not a cold-start performance success.
- Actual English track selected in 4.31 s, retaining the concrete native ID; actual seek to 60 seconds passed in 4.13 s. A 20.1 s soak advanced 85→557 decoded frames without bad states.
- Before the selection fixes, two pinned smokes played the same catalog movie through a different cached stream ID; those cases remain recorded as failures, not successes.

## Actual native series proof

Exact catalog 159 / S01E02 / MediaItem159:

| Requested native ID suffix | Actual voice | Actual height | Seconds | Result |
|---|---|---:|---:|---|
| 016cafe2eca821dc1ef9128f | Original | 480 | 8.72 | PASS |
| a03531516b353e38601ba3eb | Original | 240 | 13.14 | PASS |
| 0e3cc156dbed91860b68eaa9 | HDrezka Kubik³ | 360 | 3.98 | PASS |

Each requested ID matched its active ID, duration was about 2,884,904 ms and decoded frames advanced. The first series attempt decoded the 480p leaf but the next agent command received HTTP400; its partial artifact is retained. The complete repeat passed all three leaves and had no rejected actions or candidate errors.

## Performance and remaining work

- Unpinned Auto decoded the correct movie at 720p in 16.35 s; the initial baseline was 21.26 s. These are individual time-dependent samples, not a controlled performance gain or a coverage audit.
- Auto still used a cached `stream:` ID; universal automatic native selection is not yet proved.
- After Gradle finished, API reads measured 2.066 / 2.130 / 2.104 s. Measurements taken during compilation were slower and are stored separately.
- Startup <=5 s is not met, including the warmed native movie. Next priorities: feed fresh decoder evidence into automatic selection, investigate API/metadata/Cues/buffer latency separately, and verify true cold P2P startup without breaking seeking or playback stability.
- Continue real audio-track mapping and dynamic provider variants; there is no fixed 3 voices ×3 qualities target. No fresh 1000-film cohort was claimed in this runtime block.
- A prior Auto candidate failed HTTP status and successfully fell back; its log line appears in the later process-wide movie log. It was not a new failure of the pinned native source.

## Cleanup and preservation

Player IDLE, probe disabled, UI detached; parser, enricher and TorrServer running, health HTTP200. Only the Rutor sidecar task proven absent before this block's warmup was removed. All 20 pre-existing dirty file hashes were preserved. Backend module matches the deployed file. WARP/DNS were not changed. Download contains one current Movia APK.

GitHub rejected the previous checkpoint push with Internal Server Error; current publication is checked after the local commit. The project is not declared complete.
