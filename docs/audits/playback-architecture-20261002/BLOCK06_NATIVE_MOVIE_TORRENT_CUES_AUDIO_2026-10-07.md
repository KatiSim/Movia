# Block 06: native movie torrent playback, real audio switching, and MKV seek

Date: 2026-10-07. Parent checkpoint: 3437 / a9174435b6d2bf108a6786aad1c0f2e704261fbc.

## Delivered behavior

Movia now tries its existing local TorrServer transport for movies as well as exact episodes. Previously the helper rejected every movie request before contacting the sidecar. The new Movia-owned selector uses actual torrent metadata and its engine-local file ID. A movie is eligible only when there is one verified video file; an episode requires one exact S/E match. Duplicate episodes, several movie video files, partial episode coordinates, invalid IDs, and unknown video lengths fail closed. Public file indexes from another engine are not reinterpreted as TorrServer IDs; such requests retain the aria2 path. The response hash must match the selected torrent hash. Sidecar RPC/add/poll attempts use the remaining bounded discovery budget.

Android Media3 1.9.3 no longer globally disables Matroska Cues seeking. No extra PlaybackSession or MediaSession was introduced. This fixes the demonstrated ignored seek for the tested MKV. The original configuration, including the disabled-Cues flag, is retained in the task backup and preceding checkpoint.

No fixed voice/quality counts, invented audio indexes, provider locators, or LazyMedia runtime dependency were added. The existing native ProviderContract / VariantTree leaf ID remains the selected source identity.

## Evidence that changed the diagnosis

The previous Apibay metadata miss did not establish that all torrent transport was unavailable. Two existing exact catalog 158 sources were tested independently. A Rutor source with 45 reported seeders returned one actual file in about four seconds while the Apibay source was still getting metadata. The Rutor file was Interstellar.2014.D.BDRip.AVC.ExKinoRay.mkv (3,405,332,305 bytes, actual TorrServer ID 1).

A bounded byte-range request returned HTTP 206, video/x-matroska, and the real EBML header. After deployment the actual Movia gateway returned HTTP 302 / X-Movia-P2P-Engine=torrserver, and its verified localhost target returned the same real media bytes. The warm gateway check took 0.38 seconds; this is not a cold movie startup measurement.

Independent ffprobe inspection of that same file confirmed H.264 video at 576 pixels, Russian AC3 audio titled DUB (Лицензия), English AC3 audio, and actual duration 10,143.968 seconds. Global ffprobe stream indexes were recorded as evidence only; they were not blindly assigned as provider playback audio indexes.

## Before and after actual Android runtime

The native movie source was explicitly selected by its existing public ID:
provider-item:v2:3f5bcc5ccecdf1324f3d76c1:605e6bcb4e121618cb9c02bf.
Catalog ID and Media3 media item ID remained 158 (Interstellar, 2014). These runs prove playback of that real leaf; automatic selection of the best playable movie candidate is not yet proven.

| Check | Before Cues fix | After Cues fix |
| --- | --- | --- |
| Task absent before play | Yes | Yes |
| Explicit metadata prewarm | None | None |
| READY / decoded movie | PASS, 5.66 s, 13 frames | PASS, 10.93 s, 15 frames |
| Actual dimensions / duration | 576p / 10,143,968 ms | 576p / 10,143,968 ms |
| Seek to 60,000 ms | FAIL: 34,705 ms after 35.41 s | PASS: 60,079 ms after 2.65 s |
| Internal audio switch | Not tested in this run | PASS: Russian DUB → English, 0.61 s |
| Sustained playback after seek | Not run after failed seek | 20.25 s, 37 → 520 frames, no bad states |

The audio switch was verified against actual Media3 selected track IDs: track:1:1:0 (Russian DUB) and track:1:2:0 (English). The native source ID remained unchanged, new video frames arrived, and Media3 stayed READY/isPlaying. This is decoder-backed audio selection, not multiple fabricated voice leaves from a release title. Discovery voice metadata for the base torrent remains unknown where a release classifier did not establish a voice.

Removing an owned torrent task makes these task-cold tests more stringent than the first warm 6.55-second movie smoke. It does not make the entire device, DHT, peer network, or HTTP stack cold. No claim of fully cold network benchmarking is made.

The after-fix 10.93-second run does not meet the requested ≤5-second start gate. Cues reading is needed for correct seeking; the two runs alone do not isolate every contributor to the startup difference. Startup performance remains open.

## Series regression on the new installed APK

Exact catalog 159 / S01E02 passed all three native selections with requested ID equal to active ID, Media3 ID 159, new frames, and approximately 48-minute duration:

| Voice / decoded quality | READY elapsed | Frames |
| --- | --- | --- |
| Original / 480p | 8.31 s | 0 → 9 |
| Original / 240p | 2.70 s | 12 → 22 |
| HDrezka Kubik³ / 360p | 11.36 s | 25 → 40 |

No candidate errors, FATAL, or ANR were observed in the successful movie or series runs. The probes ran headlessly and were stopped afterward; the physical UI remained detached.

## Build, deployment, and preservation

- Backend compilation and full suite: 449/449 PASS, including 16 new selector/transport regressions. An initial timeout-floor regression failed and was corrected before deployment; final tests passed.
- Android unit suite: 239/239 PASS, zero failures/errors/skips.
- Offline APK build and Android instrumentation Kotlin compilation passed. Instrumentation execution is not claimed.
- Installed final APK versionCode 335 / versionName 0.0.1. Built and installed SHA256: 737241b18b94164b774b694232f73c69a67ec21520965f542f53f96765647c03.
- Backend deployed to the active phone runtime; both changed runtime files matched repository hashes at the final check. Parser/enricher/TorrServer were RUNNING and health returned HTTP 200.
- All 20 unrelated pre-existing dirty file hashes were preserved and excluded from this checkpoint.
- App data, WARP, DNS, and TLS validation were preserved. No anti-bot bypass or global Android process policy changes were performed.
- Owned diagnostic torrent entries were removed or verified already expired after playback stopped. Other tasks/downloads were not removed. The temporary invalid-hash route probe was also removed immediately after observation.

Published evidence excludes signed locators, authorization tokens, full magnets, and downloaded media payloads. Tests use explicit mock data and are never represented as live playback sources.

## Remaining work toward project completion

1. Make normal card playback select a verified available movie source without requiring an explicitly pinned Rutor ID; reported seeders alone are insufficient.
2. Reduce real startup to ≤5 seconds while retaining correct MKV Cues, seek, and resume behavior. Measure metadata, cue/tail access, buffering, and actual first frames separately.
3. Verify exact resume after quality/voice/source switches, offline playback, and recovery under sustained real HTTP failures.
4. Add an explicit verified file-path contract for multi-file torrents and engine index translation; do not select the largest/first ambiguous file.
5. Extend decoder-backed audio discovery and prove any provider audio-index mapping before publishing extra selectable leaves.
6. Expand movie/episode parity on independent titles and run a fresh 1000-title cohort excluding all saved previous samples. No coverage percentage is inferred from this single movie and episode.
7. Complete natural row migration, remove redundant legacy paths after parity, and close final collision/remake, performance, background, Android, and release gates before declaring the project complete.

## Primary reference for the Cues behavior

Android Developers, MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES:
https://developer.android.com/reference/androidx/media3/extractor/mkv/MatroskaExtractor
The API explains that this flag can make Matroska unseekable when Cues follow the first cluster. The project uses Media3 1.9.3; the actual same-file before/after runtime evidence above validates the repair here.
