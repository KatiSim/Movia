package app.movia.android.domain.model

data class PlaybackProgress(
    val title: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val contentId: String? = null,
    val updatedAt: Long = 0L,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
) {
    val mediaRef: MediaRef?
        get() = contentId?.takeIf { it.isNotBlank() }?.let { id ->
            runCatching { MediaRef(id, seasonNumber, episodeNumber) }.getOrNull()
        }

    val fraction: Float
        get() = if (durationMs > 0L) {
            (positionMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0).toFloat()
        } else {
            0f
        }
}
