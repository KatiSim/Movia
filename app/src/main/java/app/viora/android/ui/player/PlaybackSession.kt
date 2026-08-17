package app.viora.android.ui.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession

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

    var activeTitle by mutableStateOf<String?>(null)
        private set

    var activeSource by mutableStateOf<String?>(null)
        private set

    var isPlaying by mutableStateOf(false)
        private set

    var playbackState by mutableIntStateOf(Player.STATE_IDLE)
        private set

    var playWhenReady by mutableStateOf(player.playWhenReady)
        private set

    val mediaSession: MediaSession = MediaSession.Builder(context.applicationContext, player).build()

    init {
        VioraPlaybackRegistry.current = this
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
            }

            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
            }

            override fun onPlayWhenReadyChanged(value: Boolean, reason: Int) {
                playWhenReady = value
            }
        })
    }

    fun start(
        title: String,
        sourceUri: String? = null,
        startPositionMs: Long = 0L,
    ) {
        val source = sourceUri ?: DEMO_VIDEO_URL
        val sameMedia = activeTitle == title && activeSource == source && player.mediaItemCount > 0
        activeTitle = title
        activeSource = source

        if (!sameMedia) {
            val item = MediaItem.Builder()
                .setMediaId(title)
                .setUri(source)
                .build()
            player.setMediaItem(item)
            player.prepare()
            if (startPositionMs > 0L) player.seekTo(startPositionMs)
        }
        player.play()
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun stopAndClear() {
        player.stop()
        player.clearMediaItems()
        activeTitle = null
        activeSource = null
        isPlaying = false
        playbackState = Player.STATE_IDLE
        playWhenReady = false
    }

    fun release() {
        if (VioraPlaybackRegistry.current === this) {
            VioraPlaybackRegistry.current = null
        }
        mediaSession.release()
        player.release()
        activeTitle = null
        activeSource = null
    }
}
