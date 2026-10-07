# Block 05: bounded source reload and real native v2 decoding

Date: 2026-10-07. Parent checkpoint: 3436 / cbacef4feb64c99f270ec325c5c6118e833206e4.

## Result

Implemented a Movia-owned bounded reload guard. A resolved or prepared locator no longer reauthorizes repeated reload of the same failing concrete source. Only actual decoded video, a new playback request, or an explicit source choice rearms the appropriate attempt. A forced remote refresh must produce a changed URL, headers, user agent, or DRM license locator and retain exact source/catalog identity. A changed quality label alone is not a fresh locator. Local/torrent gateways may retain their URL because their server-side state can change. The existing Media3 session remains the owner of playback.

This is an original Movia implementation, with Movia types, state ownership, and regression tests. It introduces no fixed number of voices or qualities and no LazyMedia runtime dependency.

## Validation

- Android: 239 tests passed, zero failures/errors/skips (seven new regressions).
- Offline build, APK assembly, and Android instrumentation Kotlin compilation succeeded. No instrumentation execution is claimed.
- APK installed with data preserved, versionCode 335 / versionName 0.0.1. Built and installed SHA256: d8eccf6ac7bb747bba2b387ef585a247d64d78e03d9e4fd3829bb23ce6ad8e8e.
- Cold headless launch passed, physical UI remained detached. No observed Movia FATAL/ANR.
- All 20 pre-existing unrelated dirty file hashes remained unchanged; they are excluded from this checkpoint.
- Backend health returned HTTP 200. Backend code was not changed in this block; the previous 433-test result was not rerun or relabeled as a new result.

## Actual decoder evidence

Exact catalog mediaId 159, season 1, episode 2. Each native leaf also carries the exact catalog and episode scope in the backend response. The requested native ID equaled the active ID and Media3 media item ID stayed 159 throughout. Frames below are the before/after counters surrounding each selection, not synthetic evidence.

| Native leaf suffix | Voice | Decoded height | Frames | Ready elapsed |
| --- | --- | --- | --- | --- |
| 016cafe2eca821dc1ef9128f | Original (+ subtitles) | 480 | 0 → 15 | 3.27 s |
| a03531516b353e38601ba3eb | Original (+ subtitles) | 240 | 20 → 39 | 3.51 s |
| 0e3cc156dbed91860b68eaa9 | HDrezka in Kubik³ | 360 | 42 → 59 | 12.28 s |

All three selections reached READY/isPlaying, with approximately 48-minute durations. No candidate errors or crash lines appeared in the successful run. Unknown provider quality labels were retained during discovery; the active quality was measured from decoded video. This proves native v2 playback and concrete source switching for this episode, not universal provider or audio-track correctness.

An earlier smoke on the old public ID also recovered: READY, 240p, six frames, 3.84 seconds. It is recorded separately and is not counted as native v2 proof.

The first native QA attempt stopped on a transient agent HTTP 400 during polling. It was cleaned up, the diagnostic polling was made tolerant, and a fresh attempt completed all three selections. No production behavior was changed to hide that diagnostic failure.

## Source health and limits

At the beginning of this block, checked remote HDRezka URLs returned HTTP 403 HTML; the same paths over HTTPS failed normal TLS verification. Media3 recorded three failures of the same old source in roughly 25 seconds. Provider origin probes also returned 403/500/connection errors, while a general HTTPS probe succeeded. Later fresh episode locators returned HTTP 206 MP4 and decoded successfully. These observations show time-dependent source availability. They do not prove that the code fix alone restored network access, or that every provider is now healthy. The reload-loop repair is covered by regression tests; a live sustained 403 run after the fix is still pending.

Interstellar (2014), exact catalog 158: a bounded live refresh completed in 13.72 seconds with zero native direct leaves. A real, top-ranked native torrent probe supplied no file metadata within 20 seconds. The probe entry was initially absent and subsequently expired (GET 404); no shared torrent entry was removed. Reported seeders are not proof of current peer reachability. Movie/torrent decoding is still an open gate.

No signed video locators, authorization token, magnet URLs, or downloaded media bytes are included in published evidence. WARP, DNS, TLS validation, and provider anti-bot behavior were not modified.

## Remaining completion gates

1. Demonstrate bounded fallback under a still-failing real remote source; retain the current tested one-reload guard.
2. Obtain native v2 movie and torrent decoding, including exact file/episode selection and position-preserving switches.
3. Expand runtime verification to more independent films and series, then a fresh 1000-film cohort excluding all saved earlier IDs. No coverage claim is made from this single episode.
4. Verify selectable audio-track identity against actual decoder tracks before expanding a multi-audio torrent into separate voice leaves.
5. Continue natural persisted-row migration and remove legacy paths only after real native parity.
6. Close offline playback, network recovery, collision/remake, background budget, and final release QA gates before declaring project completion.

Rollback: the previous checkpoint and retained block04 APK remain available. No catalog data migration or destructive deletion was performed.
