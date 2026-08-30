package app.movia.android

import android.app.Application
import app.movia.android.agent.AgentControlRuntime

class MoviaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AgentControlRuntime.start(this)
    }
}
