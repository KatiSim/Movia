package app.movia.android

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.movia.android.agent.AgentControlRuntime
import app.movia.android.data.catalog.DemoCatalogRepository
import app.movia.android.ui.MoviaApp
import app.movia.android.ui.player.MoviaPiPState
import app.movia.android.ui.player.MoviaPlaybackRegistry

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AgentControlRuntime.start(this)
        DemoCatalogRepository.init(this)
        MoviaPiPState.isInPictureInPicture = isInPictureInPictureMode

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        enableEdgeToEdge()
        setContent { MoviaApp() }
    }

    override fun onResume() {
        super.onResume()
        AgentControlRuntime.updateForeground(true)
    }

    override fun onPause() {
        AgentControlRuntime.updateForeground(false)
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    override fun onStop() {
        // Home/app switch/background must never leave audio or video running.
        // Manual PiP is treated as an intentional viewing surface; auto-PiP is disabled
        // in PlayerScreen so a normal Home press reaches this branch and stops playback.
        if (!isInPictureInPictureMode) {
            MoviaPlaybackRegistry.current?.stopAndClear()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations) {
            MoviaPlaybackRegistry.releaseCurrent()
        }
        super.onDestroy()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        MoviaPiPState.isInPictureInPicture = isInPictureInPictureMode
    }
}
