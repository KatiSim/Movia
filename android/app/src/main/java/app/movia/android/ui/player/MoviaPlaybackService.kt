package app.movia.android.ui.player

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Foreground media service for Android system media controls.
 * PlaybackSession remains the single owner of the Player/MediaSession; this
 * service publishes that session so Media3 can create/update the MediaStyle
 * notification and keep playback foreground-safe when the UI is not visible.
 */
class MoviaPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val shared = MoviaPlaybackRegistry.obtain(this).mediaSession
        session = shared
        if (!isSessionAdded(shared)) addSession(shared)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    companion object {
        private const val TAG = "MoviaPlaybackService"

        fun ensureStarted(context: Context) {
            val appContext = context.applicationContext
            try {
                appContext.startForegroundService(Intent(appContext, MoviaPlaybackService::class.java))
            } catch (throwable: Throwable) {
                // Playback must not crash if a vendor ROM temporarily rejects a
                // background FGS start. The active MediaSession remains valid.
                Log.w(TAG, "Unable to start media notification service: ${throwable.javaClass.simpleName}")
            }
        }
    }
}
