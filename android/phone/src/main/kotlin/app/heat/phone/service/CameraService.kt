package app.heat.phone.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import app.heat.core.model.CameraConfig
import app.heat.phone.MainActivity
import app.heat.phone.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The running hub, for the UI. Null while the service is not running. */
object HubHolder {
    private val _hub = MutableStateFlow<CameraHub?>(null)
    val hub: StateFlow<CameraHub?> = _hub.asStateFlow()
    internal fun set(h: CameraHub?) { _hub.value = h }
}

/**
 * Keeps the camera alive. A foreground service of type `camera` is the only
 * thing Android lets keep a camera open with the screen off, which is exactly
 * the state a camera watching a room is in.
 */
class CameraService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
        val notification = buildNotification(this, armed = false)
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        val hub = CameraHub(applicationContext)
        HubHolder.set(hub)
        hub.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        HubHolder.hub.value?.close()
        HubHolder.set(null)
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "app.heat.camera.STOP"
        const val CHANNEL_STATUS = "heat_status"
        const val CHANNEL_MOTION = "heat_motion"
        const val CHANNEL_MOTION_SOUND = "heat_motion_sound"

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, CameraService::class.java))

        fun stop(context: Context) {
            context.stopService(Intent(context, CameraService::class.java))
        }

        fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_STATUS, "Camera status", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while the camera is running"
                    setShowBadge(false)
                }
            )
            // High importance matters: it is what makes the notification buzz a paired watch even if the Heat watch app is closed.
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_MOTION, "Motion alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Motion detected by this camera"
                    setSound(null, null)
                    enableVibration(true)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_MOTION_SOUND, "Motion alerts (with sound)", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Motion detected by this camera, with a sound on this phone"
                    enableVibration(true)
                }
            )
        }

        fun buildNotification(context: Context, armed: Boolean): Notification {
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE
            )
            val stop = PendingIntent.getService(
                context, 1, Intent(context, CameraService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(context, CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_stat_heat)
                .setContentTitle(if (armed) "Heat is watching" else "Heat camera ready")
                .setContentText(if (armed) "Armed. You will be alerted on motion." else "Not armed")
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(0, "Stop camera", stop)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }

        fun refreshNotification(context: Context, armed: Boolean) {
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(context, armed))
        }
    }
}

/** Phone-side alert. Posting it also reaches a paired watch through Wear OS notification bridging, even with our watch app closed. */
object AlertNotifier {
    private var lastMs = 0L

    fun motion(context: Context, config: CameraConfig) {
        val now = System.currentTimeMillis()
        if (now - lastMs < 10_000) return
        lastMs = now
        if (inQuietHours(config)) return
        val open = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val channel = if (config.alerts.phoneSound) CameraService.CHANNEL_MOTION_SOUND else CameraService.CHANNEL_MOTION
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_heat)
            .setContentTitle("Motion on ${config.label}")
            .setContentText("Heat detected movement")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(ALERT_ID, n) }
    }

    private fun inQuietHours(c: CameraConfig): Boolean {
        val from = c.alerts.quietFromMin ?: return false
        val to = c.alerts.quietToMin ?: return false
        val cal = java.util.Calendar.getInstance()
        val minutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        return if (from <= to) minutes in from until to else minutes >= from || minutes < to
    }

    private const val ALERT_ID = 2
}
