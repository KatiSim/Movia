package app.viora.android.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.media3.common.C
import kotlin.math.max

internal const val ACTION_PIP_REWIND = "app.viora.android.action.PIP_REWIND_10"
internal const val ACTION_PIP_TOGGLE = "app.viora.android.action.PIP_TOGGLE_PLAY_PAUSE"
internal const val ACTION_PIP_FORWARD = "app.viora.android.action.PIP_FORWARD_10"

class VioraPiPActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val session = VioraPlaybackRegistry.current ?: return
        val player = session.player

        when (intent?.action) {
            ACTION_PIP_REWIND -> {
                player.seekTo(max(0L, player.currentPosition - 10_000L))
            }

            ACTION_PIP_TOGGLE -> session.togglePlayPause()

            ACTION_PIP_FORWARD -> {
                val target = player.currentPosition + 10_000L
                val duration = player.duration
                player.seekTo(
                    if (duration != C.TIME_UNSET && duration > 0L) {
                        target.coerceAtMost(duration)
                    } else {
                        target
                    },
                )
            }
        }
    }
}
