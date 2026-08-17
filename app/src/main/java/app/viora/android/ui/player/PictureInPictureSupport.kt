package app.viora.android.ui.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object VioraPiPState {
    var isInPictureInPicture by mutableStateOf(false)
        internal set
}

internal fun buildVioraPictureInPictureParams(
    context: Context,
    sourceRectHint: Rect?,
    isPlaying: Boolean,
    title: String,
    autoEnter: Boolean,
): PictureInPictureParams {
    val builder = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(16, 9))

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        builder
            .setTitle(title)
            .setSubtitle("Viora")
    }

    sourceRectHint?.takeUnless(Rect::isEmpty)?.let(builder::setSourceRectHint)

    builder.setActions(buildVioraPictureInPictureActions(context, isPlaying))

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        builder
            .setAutoEnterEnabled(autoEnter && isPlaying)
            .setSeamlessResizeEnabled(true)
    }
    return builder.build()
}

private fun buildVioraPictureInPictureActions(
    context: Context,
    isPlaying: Boolean,
): List<RemoteAction> {
    fun action(
        requestCode: Int,
        intentAction: String,
        iconRes: Int,
        title: String,
    ): RemoteAction {
        val intent = Intent(context, VioraPiPActionReceiver::class.java)
            .setAction(intentAction)
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

    return listOf(
        action(1, ACTION_PIP_REWIND, android.R.drawable.ic_media_rew, "Назад на 10 секунд"),
        action(
            2,
            ACTION_PIP_TOGGLE,
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (isPlaying) "Пауза" else "Воспроизвести",
        ),
        action(3, ACTION_PIP_FORWARD, android.R.drawable.ic_media_ff, "Вперёд на 10 секунд"),
    )
}
