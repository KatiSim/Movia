package app.movia.android

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.movia.android.ui.MoviaApp
import app.movia.android.ui.player.MoviaPiPState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MoviaPiPState.isInPictureInPicture = isInPictureInPictureMode
        enableEdgeToEdge()
        setContent { MoviaApp() }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        MoviaPiPState.isInPictureInPicture = isInPictureInPictureMode
    }
}
