package app.movia.android.domain.model

enum class PlaybackStatus {
    IDLE,
    BUFFERING,
    READY,
    ENDED,
}

/**
 * Semantic playback-preparation state exposed to presentation.
 *
 * RESOLVING/BUFFERING stay for binary/API compatibility with older agent clients,
 * but new playback flows emit the explicit states below.
 */
enum class PlaybackSwitchState {
    IDLE,
    RESOLVING,
    SEARCHING_SOURCE,
    CONNECTING,
    PREBUFFERING,
    READY,
    RECOVERING,
    SWITCHING_SOURCE,
    BUFFERING,
    FAILED,
}

data class ActiveStreamSelection(
    val requestedStreamId: String? = null,
    val activeStreamId: String? = null,
    val requestedQuality: String? = null,
    val requestedVoice: String? = null,
    val activeQuality: String? = null,
    val activeVoice: String? = null,
    val source: String? = null,
    val fallbackReason: String? = null,
)

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
    val statusMessage: String? = null,
    val switchState: PlaybackSwitchState = PlaybackSwitchState.IDLE,
    val activeStreamSelection: ActiveStreamSelection? = null,
) {
    val hasMedia: Boolean get() = mediaId.isNotBlank() && displayTitle.isNotBlank()
}
