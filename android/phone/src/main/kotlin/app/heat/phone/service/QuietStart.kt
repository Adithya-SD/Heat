package app.heat.phone.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import app.heat.phone.MainActivity
import app.heat.phone.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The watch app opened: bring the phone's camera up as a background task and show nothing. The same hub the app's
 * screen uses lives in the service, so there is one camera feed however it was started.
 *
 * If Android refuses a camera service from the background (it can, when nothing of ours has been on screen since the
 * phone started), the screen is still not woken: a quiet, silent notification offers to start it with one tap.
 */
object QuietStart {
    private const val CHANNEL = "heat-start"
    private const val ID = 5

    fun begin(context: Context): Boolean {
        if (HubHolder.hub.value != null) return true
        val app = context.applicationContext
        val started = runCatching { CameraService.start(app) }.isSuccess
        CoroutineScope(Dispatchers.Default).launch {
            delay(2_500)
            if (HubHolder.hub.value == null) offer(app) else cancel(app)
        }
        return started
    }

    private fun offer(context: Context) {
        Log.i("HeatQuietStart", "camera did not start in the background; offering a silent notification")
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Starting the camera", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false); setShowBadge(false)
        })
        val open = PendingIntent.getActivity(context, 4, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            nm.notify(ID, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_heat).setContentTitle("Your watch is waiting")
                .setContentText("Tap to start the Heat camera").setContentIntent(open).setAutoCancel(true)
                .setTimeoutAfter(120_000).build())
        }
    }

    private fun cancel(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(ID) }
    }
}
