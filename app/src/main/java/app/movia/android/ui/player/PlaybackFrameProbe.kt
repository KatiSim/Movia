package app.movia.android.ui.player

import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer

/** Debug-only offscreen decoder sink: no Activity, screen taps or user media history. */
internal class PlaybackFrameProbe(private val player: ExoPlayer) : AutoCloseable {
    // Hardware decoders choose an opaque vendor buffer format. PRIVATE accepts
    // it without requiring a CPU-readable RGBA layout or copying pixels.
    private val reader = if (Build.VERSION.SDK_INT >= 29) {
        ImageReader.newInstance(320, 180, ImageFormat.PRIVATE, 3, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
    } else ImageReader.newInstance(320, 180, ImageFormat.PRIVATE, 3)
    @Volatile var frames: Long = 0L
        private set
    @Volatile var lastTimestampNs: Long = 0L
        private set
    @Volatile private var closed = false
    private val previousVolume = player.volume
    init {
        reader.setOnImageAvailableListener({ source ->
            if (!closed) try {
                source.acquireLatestImage()?.use { image -> frames++; lastTimestampNs = image.timestamp }
            } catch (error: RuntimeException) {
                // Never count a failed acquisition as a decoded frame.
                if (!closed) Log.w("MoviaFrameProbe", "Could not acquire decoder output", error)
            }
        }, Handler(Looper.getMainLooper()))
        player.volume = 0f
        player.setVideoSurface(reader.surface)
    }
    override fun close() {
        if (closed) return
        closed = true
        reader.setOnImageAvailableListener(null, null)
        player.clearVideoSurface(reader.surface)
        player.volume = previousVolume
        reader.close()
    }
}
