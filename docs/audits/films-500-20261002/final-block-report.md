# Playback integration block: 2 October 2026

All changes in this block apply to playback contracts and track selection globally. No film-specific exception or title/ID switch was added.

Build 321 fixes logical audio track ordinals when Media3 advertises duplicate codec renditions, retains the prepared adaptive source when choosing a voice at a selected quality, and reapplies video preferences after in-place audio changes. Interaction animation regression tests pass. Verification: 147 Android JVM tests and 10 native motion/adaptive playback regressions passed. The single application playback session was retained.

The owned backend now retains original provider audio labels, adds the actual audio ordinal to movie and episode stream IDs, disambiguates genuinely identical labels using their track ordinal, and preserves source labels through balancer normalization and API sanitization. All 132 backend tests pass, including 6 new identity regressions. This last backend source change is committed here; the active local parser process was not restarted or changed, so the fix is not yet active in its cached catalog responses.

The embedded LazyMedia Deluxe 3.466 engine already supplies original provider parsing and playlist resolution to Movia. Its complete playback bridge is not yet verified: active coverage remains 12 providers out of the original 34 registry entries. The original provider playlist contracts must continue to be traced and tested globally. Calling this a complete transfer would be incorrect.

## Real-content evidence and remaining work

See final-block-results.json for final native matrix counts. 263 distinct movies yielded decoded video frames and audio samples in the internal source/component audit. This component evidence does not mean that all voice/quality combinations or all downloads passed. 500 complete movie matrices have NOT been achieved. Existing technical rendition labels do not independently identify studios. Native matrix failures exposed the shared provider-label collision fixed in the owned backend source; these failures remain recorded until the updated source is active and retested.

Fresh source discovery degraded after 11:13:50 UTC: the main Collaps embed endpoint returned 422 and alternative mirrors returned 404 INVALID_ROUTE, including titles whose sources had previously decoded in this block. These are provider failures, not evidence of unavailable individual films. No CAPTCHA, access control or VPN setting was changed.

## Parser location

The active lightweight parser/API remains in ~/projects/media-parser on the phone, using catalog.db. Heavy content, metadata and torrent background enrichers were already stopped. The owned server implementation is in backend/runtime in Movia and in the movia-owned-backend-20261001 branch. An external hosting instance is not running. Local API services remain necessary for the current app. Copying server sources did not move the running database/API off the phone.

No phone UI interactions were used. Native testing used a headless diagnostic surface and the existing Media3 session. The native audit checked preservation of user library and settings; the temporary foreground audit service was stopped at the end. Signed source addresses, tokens and user library contents are omitted from public reports.
