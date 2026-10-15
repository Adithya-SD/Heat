package app.heat.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Starting either app starts the other. The app that was opened sends `/heat/open` to the paired device; there the
 * Data Layer wakes this library's listener service, which opens the app (directly where Android allows it, and
 * through a full-screen notification, which Android always allows, where it does not).
 *
 * No ping-pong: the app opened by a ping carries [EXTRA_FROM_PEER] and says nothing back, an app already on screen
 * ignores pings, and a ping is not repeated within [QUIET_MS].
 */
object PeerLauncher {
    const val EXTRA_FROM_PEER = "heat.fromPeer"
    private const val PATH = "/heat/open"
    private const val CHANNEL = "heat-open"
    private const val QUIET_MS = 8_000L
    private const val TAG = "HeatLauncher"

    /** Set by each app's activity while it is on screen. */
    @Volatile var onScreen = false
    @Volatile private var lastPingMs = 0L

    /** Call from the main activity's onCreate. */
    fun announce(context: Context, launchedByPeer: Boolean) {
        if (launchedByPeer) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPingMs < QUIET_MS) return
        lastPingMs = now
        val app = context.applicationContext
        DataLayerHooks.scope.launch {
            runCatching {
                for (n in Wearable.getNodeClient(app).connectedNodes.await()) {
                    Wearable.getMessageClient(app).sendMessage(n.id, PATH, ByteArray(0)).await()
                }
            }.onFailure { Log.w(TAG, "could not tell the other device: ${it.message}") }
        }
    }

    internal fun opened(context: Context) {
        if (onScreen) return
        val app = context.applicationContext
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_FROM_PEER, true)
        } ?: return
        runCatching { app.startActivity(launch) }
        // Where a background start is refused, a full-screen notification opens the app all the same.
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Opening Heat", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(app, 7, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("Heat").setContentText("Opening")
            .setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(pi, true).setContentIntent(pi).setAutoCancel(true).setTimeoutAfter(4_000).build()
        runCatching { nm.notify(7, n) }
    }
}
