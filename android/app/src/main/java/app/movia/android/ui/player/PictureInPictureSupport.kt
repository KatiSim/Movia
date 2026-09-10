package app.movia.android.ui.player

import android.app.PictureInPictureParams
import android.graphics.Rect
import android.os.Build
import android.util.Rational
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object MoviaPiPState {
    var isInPictureInPicture by mutableStateOf(false)
        internal set
}

internal fun buildMoviaPictureInPictureParams(
    sourceRectHint: Rect?,
    autoEnter: Boolean,
): PictureInPictureParams {
    val builder = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(16, 9))

    sourceRectHint?.takeUnless(Rect::isEmpty)?.let(builder::setSourceRectHint)

    // Keep PiP visually clean. Playback transport controls come from the active
    // MediaSession and are shown by Android only while the user interacts with
    // the PiP window; no duplicate Movia title/subtitle/actions are painted here.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        builder
            .setAutoEnterEnabled(autoEnter)
            .setSeamlessResizeEnabled(true)
    }
    return builder.build()
}
