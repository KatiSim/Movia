package app.movia.android.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/** Debug-only, shell-protected audit lease. The registry remains the sole session owner. */
class AgentAuditService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val expire = Runnable { stopSelf() }
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Проверка Movia", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Movia: внутренняя проверка воспроизведения")
            .setContentText("Проверка без открытия экрана приложения")
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(320, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(320, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AgentControlRuntime.start(applicationContext)
        handler.removeCallbacks(expire)
        handler.postDelayed(expire, 60L * 60L * 1000L)
        Log.i("MoviaAudit", "Headless audit lease active; existing registry owns playback")
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        handler.removeCallbacks(expire)
        Log.i("MoviaAudit", "Headless audit lease ended")
        super.onDestroy()
    }
    companion object { private const val CHANNEL = "movia_internal_audit" }
}
