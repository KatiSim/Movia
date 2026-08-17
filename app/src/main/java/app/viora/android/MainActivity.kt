package app.viora.android

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.viora.android.ui.VioraApp
import app.viora.android.ui.player.VioraPiPState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VioraPiPState.isInPictureInPicture = isInPictureInPictureMode
        enableEdgeToEdge()
        setContent { VioraApp() }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        VioraPiPState.isInPictureInPicture = isInPictureInPictureMode
    }
}
