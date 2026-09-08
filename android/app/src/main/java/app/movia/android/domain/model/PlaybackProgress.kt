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

    val isCompleted: Boolean
        get() = durationMs > 0L && positionMs.coerceAtLeast(0L).toDouble() / durationMs.toDouble() >= COMPLETION_FRACTION

    val isResumable: Boolean
        get() = title.isNotBlank() && positionMs > 0L && durationMs > 0L && !isCompleted

    val resumePositionMs: Long
        get() = if (isResumable) positionMs.coerceAtMost(durationMs) else 0L

    companion object {
        const val COMPLETION_FRACTION = 0.98
    }
}
