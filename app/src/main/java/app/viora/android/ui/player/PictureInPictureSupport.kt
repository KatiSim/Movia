package app.viora.android.ui.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.graphics.Rect
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

    sourceRectHint?.takeUnless(Rect::isEmpty)?.let(builder::setSourceRectHint)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        builder
            .setAutoEnterEnabled(autoEnter && isPlaying)
            .setSeamlessResizeEnabled(true)
    }
    return builder.build()
}
