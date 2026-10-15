package app.heat.platform

import android.content.Context
import android.util.Log
import app.heat.core.link.Acceptor
import app.heat.core.link.ByteDuplex
import app.heat.core.link.CarrierKind
import app.heat.core.link.DeviceId
import app.heat.core.link.Dialer
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Wire names of the Data Layer. Part of the protocol: never change them. */
object DataLayerNames {
    const val LINK_PATH = "/heat/link"
    const val GROUP_PATH = "/heat/group"
    const val PHONE_CAPABILITY = "heat_phone"
    const val WATCH_CAPABILITY = "heat_watch"
}

/**
 * A pair of blocking streams as a [ByteDuplex]. [close] shuts both and runs [onClose],
 * which is what unblocks a read stuck inside the channel.
 */
class StreamDuplex(
    private val input: InputStream,
    private val output: OutputStream,
    /** Names the carrier in the I/O trace. */
    private val label: String = "dl",
    private val onClose: () -> Unit
) : ByteDuplex {
    @Volatile private var closed = false

    // Trace the first few operations of every channel with their timing: link latency is the thing that decides how
    // fast a watch connects, and it is only visible from the inside.
    private val born = android.os.SystemClock.elapsedRealtime()
    private var traced = 0
    private fun trace(what: String, n: Int) {
        if (traced++ < 40) Log.i("HeatDataLayer", "io[$label]: $what $n B at +${android.os.SystemClock.elapsedRealtime() - born} ms")
    }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        withContext(Dispatchers.IO) { input.read(buffer, offset, length).also { if (it > 0) trace("read", it) } }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) =
        withContext(Dispatchers.IO) {
            if (closed) throw IOException("closed")
            output.write(buffer, offset, length)
            output.flush()
            trace("write", length)
        }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { onClose() }
    }
}

/**
 * The Bluetooth carrier, from the viewer's side: opens one Data Layer channel to
 * the paired phone and runs the ordinary session protocol over its streams.
 * Everything above this is identical to Wi-Fi.
 */
class DataLayerDialer(
    context: Context,
    private val capability: String = DataLayerNames.PHONE_CAPABILITY
) : Dialer {
    private companion object {
        const val TAG = "HeatDataLayer"
    }

    override val kind = CarrierKind.BLUETOOTH
    private val appContext = context.applicationContext

    override suspend fun dial(): ByteDuplex {
        val t0 = android.os.SystemClock.elapsedRealtime()
        fun lap(what: String) = Log.i(TAG, "dial: $what at +${android.os.SystemClock.elapsedRealtime() - t0} ms")
        // Experiment: how much of the first call's time is the connection to Play services itself?
        val connected = Wearable.getNodeClient(appContext).connectedNodes.await()
        lap("connected nodes (${connected.size})")
        val capabilityClient = Wearable.getCapabilityClient(appContext)
        val nodes = capabilityClient.getCapability(capability, CapabilityClient.FILTER_REACHABLE).await().nodes
        lap("capability lookup (${nodes.size} nodes)")
        val node = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
            ?: Wearable.getNodeClient(appContext).connectedNodes.await().firstOrNull { it.isNearby }
            ?: throw IOException("no paired device reachable")

        val client = Wearable.getChannelClient(appContext)
        val channel = client.openChannel(node.id, DataLayerNames.LINK_PATH).await()
        lap("channel open")
        return try {
            val input = client.getInputStream(channel).await()
            val output = client.getOutputStream(channel).await()
            lap("streams ready")
            StreamDuplex(input, output) { client.close(channel) }
        } catch (e: Exception) {
            runCatching { client.close(channel) }
            throw IOException("channel streams unavailable: ${e.message}", e)
        }
    }
}

/**
 * Where the (manifest-declared) [HeatDataLayerService] hands incoming channels
 * and the pairing key. The running app sets these; if it is not running the
 * channel is simply closed and the peer's retry loop tries again.
 */
object DataLayerHooks {
    @Volatile var acceptor: Acceptor? = null
    @Volatile var onPairing: ((key: String, hub: DeviceId?, name: String?) -> Unit)? = null
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

/** Receives Data Layer events for both apps (declared in this library's manifest, so it is merged in). */
open class HeatDataLayerService : WearableListenerService() {

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != DataLayerNames.LINK_PATH) return
        val client = Wearable.getChannelClient(applicationContext)
        val acceptor = DataLayerHooks.acceptor
        if (acceptor == null) {
            runCatching { client.close(channel) }
            return
        }
        DataLayerHooks.scope.launch {
            try {
                val input = client.getInputStream(channel).await()
                val output = client.getOutputStream(channel).await()
                acceptor.accept(CarrierKind.BLUETOOTH, StreamDuplex(input, output) { client.close(channel) })
            } catch (e: Exception) {
                Log.w(TAG, "incoming channel failed", e)
                runCatching { client.close(channel) }
            }
        }
    }

    override fun onMessageReceived(event: com.google.android.gms.wearable.MessageEvent) {
        if (event.path == "/heat/open") PeerLauncher.opened(applicationContext)
        else if (event.path == PeerLauncher.CLOSE_PATH) PeerLauncher.closeHere(applicationContext)
    }

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            val item = event.dataItem
            if (item.uri.path != DataLayerNames.GROUP_PATH) continue
            readGroup(DataMapItem.fromDataItem(item).dataMap)?.let { (k, hub, name) ->
                DataLayerHooks.onPairing?.invoke(k, hub, name)
            }
        }
    }

    private companion object {
        const val TAG = "HeatDataLayer"
    }
}

private fun readGroup(map: com.google.android.gms.wearable.DataMap): Triple<String, DeviceId?, String?>? {
    val key = map.getString("k") ?: return null
    return Triple(key, map.getString("hub")?.let { DeviceId.fromHex(it) }, map.getString("name"))
}

/** Phone to watch: the household key, over the already-trusted bonded Bluetooth link. */
object GroupSync {

    /** Phone side. Safe to call repeatedly; the Data Layer only delivers when the content changes. */
    suspend fun publish(context: Context, key: String, hub: DeviceId, name: String) {
        val req = PutDataMapRequest.create(DataLayerNames.GROUP_PATH).apply {
            dataMap.putString("k", key)
            dataMap.putString("hub", hub.hex)
            dataMap.putString("name", name)
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context.applicationContext).putDataItem(req).await()
    }

    /** Watch side: read whatever the phone has published (works even if it was published while we were off). */
    suspend fun fetch(context: Context): Triple<String, DeviceId?, String?>? {
        val items = Wearable.getDataClient(context.applicationContext).dataItems.await()
        return try {
            items.firstNotNullOfOrNull { item ->
                if (item.uri.path == DataLayerNames.GROUP_PATH) readGroup(DataMapItem.fromDataItem(item).dataMap) else null
            }
        } finally {
            items.release()
        }
    }
}
