package app.viora.android.ui.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

private const val DEMO_VIDEO_URL = "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4"

class PlaybackSession(context: Context) {
    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build().apply {
        repeatMode = Player.REPEAT_MODE_OFF
        playWhenReady = true
    }

    var activeTitle by mutableStateOf<String?>(null)
        private set

    var activeSource by mutableStateOf<String?>(null)
        private set

    var isPlaying by mutableStateOf(false)
        private set

    var playbackState by mutableStateOf(Player.STATE_IDLE)
        private set

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
            }

            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
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
    }

    fun release() {
        player.release()
        activeTitle = null
        activeSource = null
    }
}
