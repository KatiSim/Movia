package app.movia.android.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.view.Surface
import android.util.Log
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.WeakHashMap

fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

object MoviaFullscreenController {
    private const val TAG = "MoviaStreamDebug"
    private val previousOrientations = WeakHashMap<Activity, Int>()

    fun isActuallyLandscape(activity: Activity): Boolean {
        val rotation = activity.display?.rotation
        val rotated = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
        val boundsLandscape = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = activity.windowManager.currentWindowMetrics.bounds
            bounds.width() > bounds.height()
        } else {
            activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        return rotated && boundsLandscape
    }

    fun toggle(activity: Activity): Boolean =
        if (isActuallyLandscape(activity)) exit(activity) else enter(activity)

    fun enter(activity: Activity): Boolean {
        previousOrientations.putIfAbsent(activity, activity.requestedOrientation)
        Log.d(TAG, "Fullscreen: enter deterministic LANDSCAPE")
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setSystemBars(activity, false)
        return true
    }

    fun exit(activity: Activity): Boolean {
        val restoreOrientation = previousOrientations.remove(activity)
            ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        Log.d(TAG, "Fullscreen: exit restoreOrientation=$restoreOrientation")
        activity.requestedOrientation = restoreOrientation
        setSystemBars(activity, true)
        return false
    }

    fun leavePlayer(activity: Activity?) {
        activity?.let {
            val restoreOrientation = previousOrientations.remove(it)
                ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            Log.d(TAG, "Fullscreen: leave player restoreOrientation=$restoreOrientation")
            it.requestedOrientation = restoreOrientation
            setSystemBars(it, true)
        }
    }

    private fun setSystemBars(activity: Activity, visible: Boolean) {
        try {
            val window = activity.window ?: return
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (visible) {
                controller.show(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } catch (_: Exception) {}
    }
}
