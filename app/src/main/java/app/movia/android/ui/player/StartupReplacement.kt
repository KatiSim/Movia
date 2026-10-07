package app.movia.android.ui.player

import app.movia.android.domain.playback.DomainPlaybackResolver
import app.movia.android.domain.playback.PlaybackRequest
import app.movia.android.domain.playback.StreamCandidate
import app.movia.android.domain.playback.StreamRanker
import app.movia.android.domain.playback.StreamRankingContext

/** A discovered locator is a startup hint, not proof that a CDN will respond. */
internal fun selectStartupReplacement(
    request: PlaybackRequest,
    active: StreamCandidate,
    incoming: List<StreamCandidate>,
    context: StreamRankingContext = StreamRankingContext(),
): StreamCandidate? {
    val compatible = DomainPlaybackResolver.cachedStartupCandidates(request, incoming).filter {
        it.transportMetadata["legacy_web_player"] != "true" &&
            (it.url != active.url || it.headers != active.headers || it.userAgent != active.userAgent) &&
            (request.requestedVoice.isNullOrBlank() || request.requestedVoice.equals("Auto", true) ||
                it.voice.equals(request.requestedVoice, true)) &&
            (request.requestedQuality.isNullOrBlank() || request.requestedQuality.equals("Auto", true) ||
                it.quality.equals(request.requestedQuality, true))
    }
    return StreamRanker.selectBest(
        compatible, requestedVoice = request.requestedVoice, requestedQuality = request.requestedQuality, context = context)
}

/** Auto starts need not initialize a cold torrent when a validated HTTP locator is ready. */
internal fun selectReadyHttpStartup(
    request: PlaybackRequest,
    candidates: List<StreamCandidate>,
    context: StreamRankingContext = StreamRankingContext(),
): StreamCandidate? {
    if (!request.requestedStreamId.isNullOrBlank() ||
        (!request.requestedVoice.isNullOrBlank() && !request.requestedVoice.equals("Auto", true)) ||
        (!request.requestedQuality.isNullOrBlank() && !request.requestedQuality.equals("Auto", true))) return null
    val http = DomainPlaybackResolver.cachedStartupCandidates(request, candidates).filter {
        it.transportMetadata["legacy_web_player"] != "true" && it.stableStreamId !in context.failedStreamIds
    }
    return StreamRanker.selectBest(http, requestedVoice = request.requestedVoice, requestedQuality = request.requestedQuality, context = context)
}

/** An exact user choice can arrive after a cached fallback has already started. */
internal fun shouldHonorRequestedStream(
    request: PlaybackRequest,
    active: StreamCandidate?,
    desired: StreamCandidate,
    context: StreamRankingContext,
): Boolean {
    val requested = request.requestedStreamId?.trim()?.takeIf { it.isNotBlank() } ?: return false
    return desired.stableStreamId == requested && active?.stableStreamId != requested &&
        !desired.isProblematic && requested !in context.failedStreamIds &&
        DomainPlaybackResolver.validatedCandidates(request, listOf(desired)).isNotEmpty()
}
