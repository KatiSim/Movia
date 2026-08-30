package app.movia.android.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Headless wake signal only. This receiver never accepts credentials and never
 * changes bridge authorization. The bearer token is provisioned separately in
 * app-private storage; waking the process alone does not grant API access.
 */
class AgentBootstrapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_BOOTSTRAP) return
        AgentControlRuntime.start(context.applicationContext)
        Log.i(TAG, "Agent bridge process awakened headlessly")
    }

    companion object {
        const val ACTION_BOOTSTRAP = "app.movia.android.agent.BOOTSTRAP"
        private const val TAG = "MoviaAgent"
    }
}
