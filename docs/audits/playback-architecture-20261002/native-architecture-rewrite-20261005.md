# Movia playback rewrite — 2026-10-05

The goal is to show every verified provider variant. Three voices and three qualities are an inventory diagnostic, never a menu limit, discovery completion rule or mandatory matrix.

## Owned Android architecture

MoviaProviderRegistry and MoviaBackendProviderAdapter implement exact catalog articles, independent provider work, hierarchical folders, deferred loaders and concrete StreamCandidate leaves. Identity validation includes media ID, year/type and explicit episode coordinates. Publication enters the existing single PlaybackSession and Media3 player.

The production LegacyProviderEngine.java, embedded lazy-playback-engine.apk and its runtime-loading instrumentation suite were deleted. The compatibility LegacyPlaybackResolver now calls Movia's own registry. No reference application classes or bytecode are loaded. The generic web and HTTP helpers remain Movia code.

Forced refresh returns usable exact-identity candidates while optional backend enrichment continues. A refreshed locator replaces the same concrete logical leaf while retaining selection identity; different providers, transports, file selections and audio selections remain separate. Episode coordinates are read from the response, never synthesized from the request.

## Backend

ProviderContract preserves explicit file/audio/video selectors, including zero. Torrent identity uses provider and BTIH plus concrete selection data. Rich HTTP/transport/release metadata survives tree conversion. Provider union uses independent bounded work and exact-request late-result caching.

Background enrichment no longer stops or prioritizes by a fabricated 3×3 target. Coverage separates marginal counts from a genuinely shared rectangle. These counts do not prove decode readiness.

The default 512-leaf truncation and HDRezka's 512-row/30-search-row slicing were removed. Resource limits now fail explicitly instead of returning silently incomplete inventory. HDRezka requires search year evidence when a year is known and rejects movie/series collisions; native leaves support source refresh.

All nine changed runtime modules match their deployed phone counterparts. Parser and enricher are running; health is HTTP 200. Native torrent and HDRezka flags remain on.

## Live evidence and correction

Build 330 was installed with package-session installation and retained application data. Its 189 Android unit tests passed. The APK contains no embedded reference engine. Build 331 adds logical-locator replacement and explicit episode parsing and is under final validation.

On build 329, Interstellar (catalog 158, 2014) played a full 10143.947-second HLS source: Media3 1280×720, real audio, 239 observed offscreen frames and first-frame latency 2037 ms. Native registry resolved 123 candidates with referenceRuntimeLoaded=false.

On build 330, switching Interstellar to 480p resumed from 120000 ms and played the same 10143.948-second film with Media3 854×480 and 1909 observed frames. Quality selection could initially encounter stale locators; this motivated build 331.

A real persisted Collaps HLS manifest returned HTTP 200 with seven audio entries in each mirror group and video variants 1280×720 and 640×360. This supports seven tracks and two heights; it does not establish studio-name-to-player-index mapping by itself. The new Collaps article endpoint still returns 422/404 and remains gated off.

Zona.mobi's article and video API initially returned nonzero URLs, but playback inspection exposed 60-second placeholders instead of full films. The temporary six-card/15-leaf insertion was quarantined transactionally, preserving unrelated providers, and its cache entries were removed. These rows are not coverage gains. The native adapter now requires measured duration compatible with provider article runtime and measured video height; unknown/unverified content fails closed. Its flag remains off.

Some HDRezka direct MP4 references also returned a 60-second 1080p clip while claiming another quality; other MP4 and HLS references contained the full feature. Metadata inventory must not be reported as fully verified playback coverage. This remains a content-validation issue, independent of removal of the reference runtime.

## Fresh cohort R

Seed 3428, 1000 movie IDs, zero overlap with recorded B–Q IDs and conservative cohort-A exclusion. Before/after within the same saved cohort: any source 449→449; at least three concrete voices 24→33; at least three canonical qualities 91→91; marginal threshold 21→30. The genuinely shared 3×3 rectangle count after enrichment was 1. These are inventory statistics, not a fixed product requirement or full-content decode proof.

## Validation and remaining limits

Backend: 370 tests passed, including >512-leaf preservation, explicit budget failure, exact identity, parallel provider union and torrent selection boundaries. Existing SQLite ResourceWarnings remain non-failures.

Build-time memory pressure caused HyperOS LOW_MEMORY termination of a headless Movia process. No crash was inferred from that termination; playback verification is performed separately from Gradle compilation.

Private Shower display acquisition returned virtual_display_unavailable; no physical display was used. Runtime decode was measured using the headless Media3 offscreen probe.

Remaining work includes general full-content rejection of placeholder media, more verified provider availability, studio-name-to-actual-audio-track mapping, and broader live series/voice switching evidence. The architecture rewrite and a successful feature playback do not establish universal provider parity or an ideal final result.

## Full-content guard added after build 331

The native MP4 probe reads at most 512 KiB and inspects a local sample with a bounded ffprobe process. It compares measured duration with exact movie catalog runtime and uses measured video height. Unknown probe results remain explicitly unmeasured. Series card runtime is never treated as episode runtime.

Live native HDRezka lookup for Interstellar rejected a 60.074667-second placeholder against catalog runtime 10140 seconds and returned nine remaining leaves. The complete lookup took 7.54 seconds, within the 12-second foreground registry budget; background discovery may finish later and populate the exact-request cache.

Build 332 additionally guards Media3 READY against a gross mismatch with known movie runtime, including old cached source URLs. Such a source is treated as a non-network content failure and is skipped without retrying the same content as a transient URL issue. Both normal UI and headless agent playback carry known catalog runtime; unknown durations, genuine short films, series and trailers retain their own semantics.
