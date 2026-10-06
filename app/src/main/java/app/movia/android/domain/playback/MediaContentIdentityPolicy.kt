package app.movia.android.domain.playback

import app.movia.android.domain.model.ContentType

/** Compare real runtime evidence for this movie or this exact episode. */
object MediaContentIdentityPolicy {
    fun episodeDuration(request: PlaybackRequest, candidates: List<StreamCandidate>): Long? {
        if (!request.isSeries) return null
        val runtimes=candidates.filter {
            it.catalogMediaId == request.mediaId && it.seasonNumber == request.seasonNumber &&
                it.episodeNumber == request.episodeNumber &&
                it.transportMetadata["hdrezka_episode_verified"] == "true"
        }.mapNotNull { it.transportMetadata["expected_episode_duration_ms"]?.toLongOrNull()?.takeIf { ms -> ms > 0 } }
        if (runtimes.isEmpty()) return null
        val first=runtimes.first()
        if (runtimes.any { kotlin.math.abs(it.toDouble()-first) > first*.02 }) return null
        return first
    }

    fun durationMismatch(request: PlaybackRequest, actualDurationMs: Long, expectedEpisodeDurationMs: Long? = null): Boolean {
        if (request.isTrailer || actualDurationMs <= 0L) return false
        val expected = when {
            request.isSeries -> expectedEpisodeDurationMs
            request.mediaType == ContentType.MOVIE -> request.expectedDurationMs
            else -> null
        } ?: return false
        if (expected <= 0L) return false
        return actualDurationMs.toDouble() < expected * 0.7 ||
            actualDurationMs.toDouble() > expected * 1.4
    }
}
