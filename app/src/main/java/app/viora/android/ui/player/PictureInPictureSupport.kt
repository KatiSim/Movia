package app.viora.android.ui.player

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

private const val ACTION_PIP_REWIND = "app.viora.android.action.PIP_REWIND_10"
private const val ACTION_PIP_TOGGLE = "app.viora.android.action.PIP_TOGGLE"
private const val ACTION_PIP_FORWARD = "app.viora.android.action.PIP_FORWARD_10"

object VioraPiPState {
    var isInPictureInPicture by mutableStateOf(false)
        internal set
}

internal fun buildVioraPictureInPictureParams(
    context: Context,
    activity: Activity,
    sourceRectHint: Rect?,
    isPlaying: Boolean,
    title: String,
    autoEnter: Boolean,
): PictureInPictureParams {
    val builder = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(16, 9))
        .setTitle(title)
        .setSubtitle("Viora")
        .setActions(buildPiPActions(context, isPlaying).take(activity.maxNumPictureInPictureActions))

    sourceRectHint?.takeUnless(Rect::isEmpty)?.let(builder::setSourceRectHint)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        builder
            .setAutoEnterEnabled(autoEnter)
            .setSeamlessResizeEnabled(true)
    }
    return builder.build()
}

internal fun registerPiPActionReceiver(
    context: Context,
    session: PlaybackSession,
): BroadcastReceiver {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PIP_REWIND -> session.player.seekBack()
                ACTION_PIP_TOGGLE -> session.togglePlayPause()
                ACTION_PIP_FORWARD -> session.player.seekForward()
            }
        }
    }
    val filter = IntentFilter().apply {
        addAction(ACTION_PIP_REWIND)
        addAction(ACTION_PIP_TOGGLE)
        addAction(ACTION_PIP_FORWARD)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        @Suppress("DEPRECATION")
        context.registerReceiver(receiver, filter)
    }
    return receiver
}

private fun buildPiPActions(context: Context, isPlaying: Boolean): List<RemoteAction> = listOf(
    remoteAction(
        context = context,
        action = ACTION_PIP_REWIND,
        requestCode = 101,
        iconRes = android.R.drawable.ic_media_rew,
        title = "Назад на 10 секунд",
    ),
    remoteAction(
        context = context,
        action = ACTION_PIP_TOGGLE,
        requestCode = 102,
        iconRes = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
        title = if (isPlaying) "Пауза" else "Воспроизвести",
    ),
    remoteAction(
        context = context,
        action = ACTION_PIP_FORWARD,
        requestCode = 103,
        iconRes = android.R.drawable.ic_media_ff,
        title = "Вперёд на 10 секунд",
    ),
)

private fun remoteAction(
    context: Context,
    action: String,
    requestCode: Int,
    iconRes: Int,
    title: String,
): RemoteAction {
    val intent = Intent(action).setPackage(context.packageName)
    val pendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return RemoteAction(
        Icon.createWithResource(context, iconRes),
        title,
        title,
        pendingIntent,
    )
}
