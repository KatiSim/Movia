package app.viora.android.domain.model

enum class PlaybackStatus {
    IDLE,
    BUFFERING,
    READY,
    ENDED,
}

data class PlaybackState(
    val mediaId: String = "",
    val displayTitle: String = "",
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val currentPositionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val percentageWatched: Float = 0f,
    val lastUpdatedTimestamp: Long = 0L,
    val audioTrackId: String = "Auto",
    val subtitleTrackId: String? = null,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val status: PlaybackStatus = PlaybackStatus.IDLE,
) {
    val hasMedia: Boolean get() = mediaId.isNotBlank() && displayTitle.isNotBlank()
}
