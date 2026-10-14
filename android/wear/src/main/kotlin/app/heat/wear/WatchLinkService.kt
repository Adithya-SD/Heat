package app.heat.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/**
 * Keeps the watch app's process, and with it the link to the camera, alive for a while after the screen is left.
 *
 * That is the single biggest thing that makes the next open instant: a process that is still running has its
 * connection up, its decoder built and its settings loaded, so opening the app is a message and a keyframe
 * away from a picture rather than a cold start. Without this, Wear OS is free to kill the process within moments
 * of the app leaving the screen.
 *
 * It does not run forever: it stays for [KEEP_WARM_MS] after the app was last in front, then lets go, so a watch
 * that has not been used for a while goes back to costing nothing. Opening the app starts it again.
 */
class WatchLinkService : Service() {
    private val handler = Handler(Looper.getMainLooper())

    private val idleCheck = object : Runnable {
        override fun run() {
            val hub = (application as HeatWearApp).hub
            val idleFor = SystemClock.elapsedRealtime() - hub.lastForegroundMs
            if (!hub.isForeground && idleFor > KEEP_WARM_MS) {
                stopSelf()
            } else {
                handler.postDelayed(this, CHECK_MS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Camera link", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps the connection to your camera ready so it opens instantly."
                setShowBadge(false)
            }
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Heat")
            .setContentText("Camera link ready")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        handler.removeCallbacks(idleCheck)
        handler.postDelayed(idleCheck, CHECK_MS)
        // Not sticky: if the system takes it away, the next time the app opens it comes back.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(idleCheck)
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "link"
        const val NOTIFICATION_ID = 41
        const val CHECK_MS = 30_000L
        const val KEEP_WARM_MS = 10 * 60_000L
    }
}
