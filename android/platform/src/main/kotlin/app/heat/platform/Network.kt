package app.heat.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import app.heat.core.link.DeviceId
import app.heat.core.link.DiscoveredPeer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Calls [onChange] whenever Wi-Fi comes or goes or its address changes: the moment a LAN connection becomes newly possible. */
class NetworkWatcher(context: Context, private val onChange: () -> Unit) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onChange()
        override fun onLost(network: Network) = onChange()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) = onChange()
    }

    fun start() {
        if (registered) return
        registered = runCatching { cm.registerNetworkCallback(request, callback) }.isSuccess
    }

    fun stop() {
        if (!registered) return
        runCatching { cm.unregisterNetworkCallback(callback) }
        registered = false
    }
}

/**
 * Holds the Wi-Fi multicast lock for as long as discovery runs. Without it,
 * power save drops inbound broadcasts the moment the screen goes off.
 */
class MulticastLockHolder(context: Context) {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var lock: WifiManager.MulticastLock? = null

    fun acquire() {
        if (lock?.isHeld == true) return
        lock = runCatching {
            wifi.createMulticastLock("heat-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    fun release() {
        runCatching { lock?.release() }
        lock = null
    }
}

@Serializable
private data class KnownCamera(val id: String, val name: String, val host: String, val port: Int)

/**
 * Cameras seen before, so a viewer can dial them the instant it starts instead of
 * waiting for a beacon. A stale address costs one failed dial; a fresh beacon corrects it.
 */
class KnownCameras(context: Context) {
    private val file = File(File(context.filesDir, "heat").apply { mkdirs() }, "cameras.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<DiscoveredPeer> = runCatching {
        json.decodeFromString<List<KnownCamera>>(file.readText()).mapNotNull { k ->
            DeviceId.fromHex(k.id)?.let { DiscoveredPeer(it, k.host, k.port, k.name, 0L) }
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun remember(peer: DiscoveredPeer) {
        val all = load().filterNot { it.id == peer.id } + peer
        val data = json.encodeToString(all.takeLast(MAX).map { KnownCamera(it.id.hex, it.name, it.host, it.tcpPort) })
        Vault.atomicWrite(file, data.toByteArray())
    }

    private companion object {
        const val MAX = 16
    }
}
