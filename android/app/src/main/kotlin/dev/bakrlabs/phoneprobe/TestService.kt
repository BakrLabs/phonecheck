package dev.bakrlabs.phoneprobe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

class TestService : Service() {
    private var lock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "Running tests", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra("text").orEmpty()
        val pct = intent?.getIntExtra("pct", -1) ?: -1
        when (intent?.action) {
            "begin" -> {
                val n = build(text, pct)
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(ID, n)
                }
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonecheck:test").apply {
                    acquire(6 * 60 * 60 * 1000L)
                }
            }
            "update" -> getSystemService(NotificationManager::class.java).notify(ID, build(text, pct))
            else -> {
                lock?.let { if (it.isHeld) it.release() }
                lock = null
                stopForeground(true)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        lock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun build(text: String, pct: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        b.setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("PhoneCheck")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
        if (pct >= 0) b.setProgress(100, pct, false) else b.setProgress(0, 0, true)
        return b.build()
    }

    companion object {
        private const val CHANNEL = "tests"
        private const val ID = 7

        fun begin(ctx: Context, text: String) = send(ctx, "begin", text, -1, true)
        fun update(ctx: Context, text: String, pct: Int) = send(ctx, "update", text, pct, false)
        fun end(ctx: Context) = send(ctx, "end", "", -1, false)

        private fun send(ctx: Context, action: String, text: String, pct: Int, foreground: Boolean) {
            val i = Intent(ctx, TestService::class.java)
                .setAction(action)
                .putExtra("text", text)
                .putExtra("pct", pct)
            runCatching {
                if (foreground && Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            }
        }
    }
}
