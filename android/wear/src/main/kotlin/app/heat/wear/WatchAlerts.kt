package app.heat.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.heat.core.model.AlertKind

/**
 * The watch's own notification for an alert that arrives while the app is closed. (With the app open there is no
 * notification: the white flash, the buzz and the indicator on the picture are the alert.) The wrist buzz itself
 * comes from the hub, so the channel does not vibrate a second time.
 */
object WatchAlerts {
    private const val CHANNEL = "heat-alerts"
    private const val ID = 31

    fun post(context: Context, camera: String, kind: AlertKind) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(false)
                setSound(null, null)
                description = "Motion and other alerts from your cameras while the Heat watch app is closed"
            }
        )
        val open = PendingIntent.getActivity(
            context, 3, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = when (kind) {
            AlertKind.MOTION -> "Motion"
            AlertKind.TAMPER -> "Camera moved"
            AlertKind.LOW_BATTERY -> "Low battery"
            AlertKind.OVERHEAT -> "Overheating"
            AlertKind.CAMERA_ERROR -> "Camera problem"
        }
        val n = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(text)
            .setContentText(camera)
            .setCategory(android.app.Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setOnlyAlertOnce(false)
            .build()
        runCatching { nm.notify(ID, n) }
    }

    fun clear(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(ID) }
    }
}
