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
