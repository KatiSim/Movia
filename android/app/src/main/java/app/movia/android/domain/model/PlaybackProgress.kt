package app.movia.android.domain.model

data class PlaybackProgress(
    val title: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val contentId: String? = null,
    val updatedAt: Long = 0L,
) {
    val fraction: Float
        get() = if (durationMs > 0L) {
            (positionMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0).toFloat()
        } else {
            0f
        }
}
