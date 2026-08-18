package app.viora.android.ui.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import app.viora.android.domain.model.PlaybackState
import app.viora.android.domain.model.PlaybackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal object VioraPlaybackRegistry {
    var current: PlaybackSession? = null
        internal set
}

private const val DEMO_VIDEO_URL = "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4"

class PlaybackSession(context: Context) {
    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setSeekBackIncrementMs(10_000L)
        .setSeekForwardIncrementMs(10_000L)
        .build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = true
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeSource: String? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    // Compatibility getters; all values are derived from the single StateFlow store.
    val activeTitle: String? get() = _state.value.displayTitle.takeIf { _state.value.hasMedia }
    val activeSourceUri: String? get() = activeSource
    val isPlaying: Boolean get() = _state.value.isPlaying
    val playWhenReady: Boolean get() = _state.value.playWhenReady
    val playbackState: Int
        get() = when (_state.value.status) {
            PlaybackStatus.IDLE -> Player.STATE_IDLE
            PlaybackStatus.BUFFERING -> Player.STATE_BUFFERING
            PlaybackStatus.READY -> Player.STATE_READY
            PlaybackStatus.ENDED -> Player.STATE_ENDED
        }

    val mediaSession: MediaSession = MediaSession.Builder(context.applicationContext, player).build()

    init {
        VioraPlaybackRegistry.current = this
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = publishSnapshot()
            override fun onPlaybackStateChanged(playbackState: Int) = publishSnapshot()
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = publishSnapshot()
        })
        scope.launch {
            while (isActive) {
                if (_state.value.hasMedia) publishSnapshot()
                delay(250L)
            }
        }
    }

    fun start(
        mediaId: String,
        title: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        sourceUri: String? = null,
        startPositionMs: Long = 0L,
        audioTrackId: String = _state.value.audioTrackId,
        subtitleTrackId: String? = _state.value.subtitleTrackId,
    ) {
        val source = sourceUri ?: DEMO_VIDEO_URL
        val sameMedia = _state.value.displayTitle == title && activeSource == source && player.mediaItemCount > 0
        activeSource = source
        _state.value = PlaybackState(
            mediaId = mediaId,
            displayTitle = title,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            currentPositionMs = startPositionMs.coerceAtLeast(0L),
            bufferedPositionMs = startPositionMs.coerceAtLeast(0L),
            totalDurationMs = 0L,
            percentageWatched = 0f,
            lastUpdatedTimestamp = System.currentTimeMillis(),
            audioTrackId = audioTrackId,
            subtitleTrackId = subtitleTrackId,
            isPlaying = false,
            playWhenReady = true,
            status = PlaybackStatus.BUFFERING,
        )

        if (!sameMedia) {
            val item = MediaItem.Builder()
                .setMediaId(mediaId)
                .setUri(source)
                .build()
            player.setMediaItem(item)
            player.prepare()
            if (startPositionMs > 0L) player.seekTo(startPositionMs)
        }
        player.play()
        publishSnapshot()
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
        publishSnapshot()
    }

    fun seekTo(positionMs: Long) {
        val duration = player.duration.takeIf { it > 0L } ?: _state.value.totalDurationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
        val target = positionMs.coerceIn(0L, duration)
        val percentage = if (duration != Long.MAX_VALUE && duration > 0L) {
            (target.toDouble() / duration.toDouble()).coerceIn(0.0, 1.0).toFloat()
        } else {
            _state.value.percentageWatched
        }
        _state.value = _state.value.copy(
            currentPositionMs = target,
            percentageWatched = percentage,
            lastUpdatedTimestamp = System.currentTimeMillis(),
        )
        player.seekTo(target)
    }

    fun setTrackPreferences(audioTrackId: String, subtitleTrackId: String?) {
        _state.value = _state.value.copy(
            audioTrackId = audioTrackId,
            subtitleTrackId = subtitleTrackId,
            lastUpdatedTimestamp = System.currentTimeMillis(),
        )
    }

    fun stopAndClear() {
        player.stop()
        player.clearMediaItems()
        activeSource = null
        _state.value = PlaybackState()
    }

    private fun publishSnapshot() {
        val current = _state.value
        if (!current.hasMedia) return
        val duration = player.duration.takeIf { it > 0L } ?: current.totalDurationMs
        val position = player.currentPosition.coerceAtLeast(0L)
        val percentage = if (duration > 0L) {
            (position.toDouble() / duration.toDouble()).coerceIn(0.0, 1.0).toFloat()
        } else {
            0f
        }
        _state.value = current.copy(
            currentPositionMs = position,
            bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0L),
            totalDurationMs = duration.coerceAtLeast(0L),
            percentageWatched = percentage,
            lastUpdatedTimestamp = System.currentTimeMillis(),
            isPlaying = player.isPlaying,
            playWhenReady = player.playWhenReady,
            status = when (player.playbackState) {
                Player.STATE_BUFFERING -> PlaybackStatus.BUFFERING
                Player.STATE_READY -> PlaybackStatus.READY
                Player.STATE_ENDED -> PlaybackStatus.ENDED
                else -> PlaybackStatus.IDLE
            },
        )
    }

    fun release() {
        if (VioraPlaybackRegistry.current === this) {
            VioraPlaybackRegistry.current = null
        }
        scope.cancel()
        mediaSession.release()
        player.release()
        activeSource = null
        _state.value = PlaybackState()
    }
}
