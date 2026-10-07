package app.heat.wear

import android.content.Context
import android.util.Log
import app.heat.core.host.CameraClient
import app.heat.core.link.Beacon
import app.heat.core.link.CarrierKind
import app.heat.core.link.DeviceId
import app.heat.core.link.RetryPolicy
import app.heat.core.link.TcpDialer
import app.heat.core.util.Clock
import app.heat.platform.DataLayerDialer
import app.heat.platform.DataLayerHooks
import app.heat.platform.DataLayerNames
import app.heat.platform.GroupSync
import app.heat.platform.KnownCameras
import app.heat.platform.MulticastLockHolder
import app.heat.platform.NetworkWatcher
import app.heat.platform.Pairing
import app.heat.platform.Vault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The watch's connection to every camera it knows.
 *
 * One [CameraClient] per camera, each bound to exactly that camera. Every
 * client dials over every carrier it can (Bluetooth if it is the paired phone,
 * Wi-Fi as soon as an address is known) and the link layer picks the best and
 * keeps the other warm. The watch never needs the phone's help to find a
 * camera: it listens for beacons itself and remembers what it has seen.
 */
class WatchHub(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val appContext = context.applicationContext
    private val clock = Clock.SYSTEM

    val vault = Vault(appContext)
    val deviceId: DeviceId = vault.deviceId()
    private val known = KnownCameras(appContext)
    private val multicast = MulticastLockHolder(appContext)
    private lateinit var beacon: Beacon
    private lateinit var network: NetworkWatcher
    private val selectionFile = File(File(appContext.filesDir, "heat").apply { mkdirs() }, "selected.txt")

    private val _pairing = MutableStateFlow<Pairing?>(vault.pairing())
    val pairing: StateFlow<Pairing?> = _pairing.asStateFlow()

    private val clients = ConcurrentHashMap<DeviceId, CameraClient>()
    private val _all = MutableStateFlow<List<CameraClient>>(emptyList())

    /** Every camera we know, in the order first seen. */
    val cameras: StateFlow<List<CameraClient>> = _all.asStateFlow()

    private val _selected = MutableStateFlow<DeviceId?>(runCatching { DeviceId.fromHex(selectionFile.readText().trim()) }.getOrNull())
    val selectedId: StateFlow<DeviceId?> = _selected.asStateFlow()

    val selected: CameraClient? get() = _selected.value?.let { clients[it] } ?: _all.value.firstOrNull()

    private val haptics = WatchHaptics(appContext)

    /** The most recent alert from any camera, for a banner. Alerts also buzz the wrist from here, screen on or off. */
    data class AlertEvent(val camera: DeviceId, val alert: app.heat.core.model.Msg.Alert)
    private val _lastAlert = MutableStateFlow<AlertEvent?>(null)
    val lastAlert: StateFlow<AlertEvent?> = _lastAlert.asStateFlow()

    private val bluetoothPolicy = RetryPolicy(dialTimeoutMs = 8_000, maxMs = 8_000)

    fun start() {
        DataLayerHooks.onPairing = { key, hub, name -> adopt(key, hub, name) }
        multicast.acquire()
        beacon = Beacon(deviceId, pairing.value?.key ?: app.heat.core.link.GroupKey.generate(), scope, clock)

        // Wi-Fi coming or going is the moment a LAN connection becomes newly possible.
        network = NetworkWatcher(appContext) { onNetworkChange() }
        network.start()

        pairing.value?.let { bringUp(it) }
        if (pairing.value == null) fetchPairingLoop()
    }

    fun stop() {
        runCatching { network.stop() }
        if (::beacon.isInitialized) runCatching { beacon.stop() }
        multicast.release()
        clients.values.forEach { it.stop() }
        DataLayerHooks.onPairing = null
    }

    fun select(id: DeviceId) {
        _selected.value = id
        runCatching { selectionFile.writeText(id.hex) }
    }

    // ── pairing ──────────────────────────────────────────────────────

    private fun fetchPairingLoop() {
        scope.launch {
            while (pairing.value == null) {
                runCatching { GroupSync.fetch(appContext) }.getOrNull()?.let { (k, hub, name) -> adopt(k, hub, name) }
                delay(8_000)
            }
        }
    }

    private fun adopt(keyText: String, hub: DeviceId?, name: String?) {
        val key = app.heat.core.link.GroupKey.parse(keyText) ?: return
        val current = _pairing.value
        if (current != null && current.key == key && current.hub == hub) return
        vault.setPairing(key, hub, name)
        val p = Pairing(key, hub, name)
        _pairing.value = p
        clients.values.forEach { it.stop() }
        clients.clear()
        _all.value = emptyList()
        bringUp(p)
    }

    private fun bringUp(p: Pairing) {
        if (::beacon.isInitialized) beacon.stop()
        beacon = Beacon(deviceId, p.key, scope, clock)
        beacon.start()

        // The paired phone first: reachable over Bluetooth with no network at all.
        p.hub?.let { hubId -> ensureClient(hubId, p.hubName ?: "Phone", p).apply { addBluetooth(this) } }
        // Anything seen before, dialed immediately: a stale address costs one failed dial, a beacon corrects it.
        for (k in known.load()) ensureClient(k.id, k.name, p).also { setLan(it, k.host, k.tcpPort) }

        scope.launch {
            beacon.peers.collect { peers ->
                for ((id, peer) in peers) {
                    val c = ensureClient(id, peer.name, p)
                    setLan(c, peer.host, peer.tcpPort)
                    known.remember(peer)
                }
            }
        }
        beacon.probe()
    }

    private fun ensureClient(id: DeviceId, name: String, p: Pairing): CameraClient =
        clients.getOrPut(id) {
            CameraClient(id, "Watch", deviceId, p.key, scope, clock).also { c ->
                c.start()
                scope.launch {
                    c.alerts.collect { a ->
                        val profile = c.config.value?.alerts?.watchHaptics ?: app.heat.core.model.HapticProfile.NORMAL
                        haptics.alert(profile, a)
                        _lastAlert.value = AlertEvent(c.cameraId, a)
                    }
                }
                _all.value = _all.value + c
                if (_selected.value == null) _selected.value = id
                Log.i(TAG, "camera known: $name ${id.short}")
            }
        }

    private fun addBluetooth(c: CameraClient) {
        if (!c.link.hasDialer(CarrierKind.BLUETOOTH)) {
            c.link.setDialer(CarrierKind.BLUETOOTH, DataLayerDialer(appContext, DataLayerNames.PHONE_CAPABILITY), bluetoothPolicy)
        }
    }

    private val lanEndpoints = ConcurrentHashMap<DeviceId, String>()

    private fun setLan(c: CameraClient, host: String, port: Int) {
        val ep = "$host:$port"
        if (lanEndpoints.put(c.cameraId, ep) == ep) return
        c.link.setDialer(CarrierKind.LAN, TcpDialer(host, port))
        c.link.nudge()
    }

    private fun onNetworkChange() {
        if (::beacon.isInitialized) beacon.probe()
        clients.values.forEach { it.link.nudge() }
    }

    /** The app came to the foreground: find cameras right now. */
    fun refresh() {
        onNetworkChange()
    }

    private companion object {
        const val TAG = "HeatWatchHub"
    }
}
