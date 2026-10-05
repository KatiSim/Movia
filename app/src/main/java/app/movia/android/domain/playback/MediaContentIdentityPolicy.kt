package app.movia.android.domain.playback

import app.movia.android.domain.model.ContentType

/** A known movie runtime can reject a placeholder, never establish an episode runtime. */
object MediaContentIdentityPolicy {
    fun durationMismatch(request: PlaybackRequest, actualDurationMs: Long): Boolean {
        val expected = request.expectedDurationMs ?: return false
        if (request.isTrailer || request.mediaType != ContentType.MOVIE || request.isSeries ||
            expected <= 0L || actualDurationMs <= 0L) return false
        return actualDurationMs.toDouble() < expected * 0.7 ||
            actualDurationMs.toDouble() > expected * 1.4
    }
}
