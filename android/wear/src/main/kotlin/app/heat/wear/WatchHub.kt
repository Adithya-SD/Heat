package app.heat.wear

import android.content.Context
import android.util.Log
import app.heat.core.host.CameraClient
import app.heat.core.host.VideoSink
import app.heat.core.link.Beacon
import app.heat.core.link.CarrierKind
import app.heat.core.link.DeviceId
import app.heat.core.link.GroupKey
import app.heat.core.link.MediaFlags
import app.heat.core.link.RetryPolicy
import app.heat.core.link.TcpDialer
import app.heat.core.model.HapticProfile
import app.heat.core.model.Msg
import app.heat.core.util.Clock
import app.heat.platform.DataLayerDialer
import app.heat.platform.DataLayerHooks
import app.heat.platform.DataLayerNames
import app.heat.platform.GroupSync
import app.heat.platform.Hints
import app.heat.platform.RfcommDialer
import app.heat.platform.hasBluetoothConnect
import app.heat.platform.KnownCameras
import app.heat.platform.MulticastLockHolder
import app.heat.platform.Pairing
import app.heat.platform.Vault
import app.heat.platform.WifiLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
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
 * One [CameraClient] per camera, each bound to exactly that camera. Every client dials over every carrier it
 * can (Bluetooth if it is the paired phone, Wi-Fi as soon as an address is known) and the link layer picks the
 * best and keeps the other warm. The watch never needs the phone's help to find a camera: it listens for
 * beacons itself and remembers what it has seen.
 *
 * Built to have a picture before anyone asks for one: the hub starts with the process, each camera has its own
 * decoder that collects video as soon as the link is up, and the screen merely attaches a surface to it.
 */
class WatchHub(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val appContext = context.applicationContext
    private val clock = Clock.SYSTEM

    val vault = Vault(appContext)
    val deviceId: DeviceId = vault.deviceId()
    private val known = KnownCameras(appContext)
    val hints = Hints(appContext)
    private val multicast = MulticastLockHolder(appContext)
    private val selectionFile = File(File(appContext.filesDir, "heat").apply { mkdirs() }, "selected.txt")

    // Network events arrive in bursts (a link-properties update per route change); one pass per burst is plenty.
    private val networkEvents = Channel<Unit>(Channel.CONFLATED)
    private val wifi = WifiLink(appContext) { networkEvents.trySend(Unit) }

    private val beaconLock = Any()
    private var beacon: Beacon? = null
    private var beaconJob: Job? = null
    private var beaconOnWifi: Boolean? = null
    private var wifiReleaseJob: Job? = null

    private val _pairing = MutableStateFlow<Pairing?>(vault.pairing())
    val pairing: StateFlow<Pairing?> = _pairing.asStateFlow()

    private val clients = ConcurrentHashMap<DeviceId, CameraClient>()
    private val decoders = ConcurrentHashMap<DeviceId, PreviewDecoder>()
    private val watching = ConcurrentHashMap<DeviceId, Boolean>()
    private val _all = MutableStateFlow<List<CameraClient>>(emptyList())

    /** Every camera we know, in the order first seen. */
    val cameras: StateFlow<List<CameraClient>> = _all.asStateFlow()

    private val _selected = MutableStateFlow<DeviceId?>(runCatching { DeviceId.fromHex(selectionFile.readText().trim()) }.getOrNull())
    val selectedId: StateFlow<DeviceId?> = _selected.asStateFlow()

    val selected: CameraClient? get() = _selected.value?.let { clients[it] } ?: _all.value.firstOrNull()

    private val haptics = WatchHaptics(appContext)

    /** The most recent alert from any camera, for a banner. Alerts also buzz the wrist from here, screen on or off. */
    data class AlertEvent(val camera: DeviceId, val alert: Msg.Alert)
    private val _lastAlert = MutableStateFlow<AlertEvent?>(null)
    val lastAlert: StateFlow<AlertEvent?> = _lastAlert.asStateFlow()

    private val bluetoothPolicy = RetryPolicy(dialTimeoutMs = 8_000, maxMs = 8_000)

    @Volatile private var foreground = false

    /** Whether someone is looking at the watch now, and when they last were: the warm link service reads these. */
    val isForeground: Boolean get() = foreground
    @Volatile var lastForegroundMs = android.os.SystemClock.elapsedRealtime()
        private set

    /** Whether the Wi-Fi network we asked for is currently ours; for the diagnostics page. */
    val onWifi: Boolean get() = wifi.connected

    fun start() {
        DataLayerHooks.onPairing = { key, hub, name -> adopt(key, hub, name) }
        multicast.acquire()
        scope.launch {
            for (e in networkEvents) {
                delay(250)                          // let a burst settle, then react once
                onNetworkChange()
            }
        }
        pairing.value?.let { bringUp(it) }
        if (pairing.value == null) fetchPairingLoop()
    }

    fun stop() {
        wifi.release()
        synchronized(beaconLock) { beacon?.stop(); beacon = null; beaconJob?.cancel() }
        multicast.release()
        clients.values.forEach { it.stop() }
        decoders.values.forEach { it.release() }
        decoders.clear()
        DataLayerHooks.onPairing = null
    }

    fun select(id: DeviceId) {
        _selected.value = id
        runCatching { selectionFile.writeText(id.hex) }
        applyInterest()
    }

    /** The decoder collecting this camera's video. The screen attaches its surface to it. */
    fun decoder(client: CameraClient): PreviewDecoder = decoders.getValue(client.cameraId)

    /**
     * Someone is (or is no longer) looking at the watch. In front: ask the selected camera for video, hold a
     * Wi-Fi network so the picture can take the fast road, and find cameras now. Out of sight: stop the video
     * (the camera stops encoding for us) and, after a grace period, let Wi-Fi go so the watch can sleep.
     */
    fun setForeground(visible: Boolean) {
        if (foreground == visible) return
        foreground = visible
        lastForegroundMs = android.os.SystemClock.elapsedRealtime()
        if (visible) {
            wifiReleaseJob?.cancel()
            wifi.acquire()
            onNetworkChange()
        } else {
            wifiReleaseJob?.cancel()
            wifiReleaseJob = scope.launch {
                delay(WIFI_GRACE_MS)
                wifi.release()
                networkEvents.trySend(Unit)
            }
        }
        applyInterest()
    }

    /** The app came to the foreground: find cameras right now. */
    fun refresh() {
        onNetworkChange()
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
        val key = GroupKey.parse(keyText) ?: return
        val p = synchronized(clientsLock) {
            val current = _pairing.value
            if (current != null && current.key == key && current.hub == hub) return
            vault.setPairing(key, hub, name)
            val next = Pairing(key, hub, name)
            _pairing.value = next
            clients.values.forEach { it.stop() }
            clients.clear()
            decoders.values.forEach { it.release() }
            decoders.clear()
            watching.clear()
            lanEndpoints.clear()
            _all.value = emptyList()
            next
        }
        bringUp(p)
    }

    private fun bringUp(p: Pairing) {
        // The paired phone first: reachable over Bluetooth with no network at all.
        p.hub?.let { hubId -> ensureClient(hubId, p.hubName ?: "Phone", p).apply { addBluetooth(this) } }
        // Anything seen before, dialed immediately: a stale address costs one failed dial, a beacon corrects it.
        for (k in known.load()) ensureClient(k.id, k.name, p).also { setLan(it, k.host, k.tcpPort) }
        restartBeacon(p)
    }

    // ── discovery ────────────────────────────────────────────────────

    /**
     * (Re)starts discovery. A beacon's socket is bound to the network it was created on, so it must be rebuilt
     * whenever Wi-Fi is gained or lost; one created before Wi-Fi was ours would shout into the wrong network.
     */
    private fun restartBeacon(pairingNow: Pairing? = null) {
        val p = pairingNow ?: _pairing.value ?: return
        synchronized(beaconLock) {
            beaconJob?.cancel()
            beacon?.stop()
            val b = Beacon(deviceId, p.key, scope, clock, binder = wifi)
            beacon = b
            beaconOnWifi = wifi.connected
            b.start()
            beaconJob = scope.launch {
                var last: Map<DeviceId, String> = emptyMap()
                b.peers.collect { peers ->
                    for ((id, peer) in peers) {
                        val c = ensureClient(id, peer.name, p)
                        setLan(c, peer.host, peer.tcpPort)
                        // Only write to flash when something actually moved, not once per beacon.
                        val ep = "${peer.host}:${peer.tcpPort}/${peer.name}"
                        if (last[id] != ep) known.remember(peer)
                    }
                    last = peers.mapValues { (_, peer) -> "${peer.host}:${peer.tcpPort}/${peer.name}" }
                }
            }
            b.probe()
        }
    }

    private fun onNetworkChange() {
        val p = _pairing.value
        if (p != null && beaconOnWifi != wifi.connected) restartBeacon(p) else synchronized(beaconLock) { beacon?.probe() }
        clients.values.forEach { it.link.nudge() }
    }

    // ── cameras ──────────────────────────────────────────────────────

    private fun ensureClient(id: DeviceId, name: String, p: Pairing): CameraClient {
        val c = synchronized(clientsLock) { clients[id] ?: createClient(id, name, p).also { clients[id] = it } }
        applyInterest()
        return c
    }

    private val clientsLock = Any()

    private fun createClient(id: DeviceId, name: String, p: Pairing): CameraClient =
            CameraClient(id, "Watch", deviceId, p.key, scope, clock).also { c ->
                val decoder = PreviewDecoder { c.needKeyframe() }
                decoders[id] = decoder
                c.videoSink = VideoSink { flags, pts, data, off, len ->
                    decoder.submit(flags and MediaFlags.KEYFRAME != 0, pts, data, off, len)
                }
                c.start()
                val born = android.os.SystemClock.elapsedRealtime()
                scope.launch {
                    // Timeline of this camera's link, for working out where connection time goes.
                    c.linkState.collect { ls ->
                        Log.i(TAG, "link ${id.short}: ${ls.phase} via ${ls.carrier} standby=${ls.standby} rtt=${ls.rttMs} at +${android.os.SystemClock.elapsedRealtime() - born} ms")
                    }
                }
                scope.launch {
                    // Round trip while connected, every few seconds: the number that explains how the link feels.
                    while (true) {
                        delay(4_000)
                        val ls = c.linkState.value
                        if (ls.phase == app.heat.core.link.LinkState.Phase.UP) Log.i(TAG, "rtt ${id.short}: ${ls.rttMs} ms via ${ls.carrier}")
                    }
                }
                scope.launch {
                    // What the picture looks like is remembered so the next start can build its decoder before the
                    // camera has said anything, instead of waiting for the first status message.
                    c.status.collect { st ->
                        val s = st?.stream ?: return@collect
                        if (s.width > 0 && s.height > 0) hints.setGeometry(id.hex, Hints.Geometry(s.width, s.height, s.rotation))
                    }
                }
                scope.launch {
                    decoder.rendered.collect { n ->
                        if (n == 1L) Log.i(TAG, "first picture on screen at +${android.os.SystemClock.elapsedRealtime() - born} ms after the camera became known")
                    }
                }
                scope.launch {
                    c.alerts.collect { a ->
                        val profile = c.config.value?.alerts?.watchHaptics ?: HapticProfile.NORMAL
                        haptics.alert(profile, a)
                        _lastAlert.value = AlertEvent(c.cameraId, a)
                    }
                }
                _all.value = _all.value + c
                if (_selected.value == null) _selected.value = id
                Log.i(TAG, "camera known: $name ${id.short}")
            }

    /** Video only from the camera on screen, only while the screen is showing. Everything else costs nothing. */
    private fun applyInterest() {
        val sel = selected
        for (c in clients.values) {
            val want = foreground && c === sel
            if (watching.put(c.cameraId, want) != want) c.watch(video = want)
        }
    }

    /**
     * Two ways over Bluetooth, tried in waves. The direct socket goes at once: it needs nothing but the bonded
     * phone's address, which was learned last time. The Data Layer is the fallback, started only if the direct
     * socket has not made it within a moment, and let go again once the direct one is up: a spare that pings
     * all day for nothing is just battery.
     */
    private fun addBluetooth(c: CameraClient) {
        scope.launch {
            var provenAt = 0L
            while (true) {
                val permitted = hasBluetoothConnect(appContext)
                if (permitted && !c.link.hasDialer(CarrierKind.BT_DIRECT)) {
                    c.link.setDialer(CarrierKind.BT_DIRECT, RfcommDialer(appContext, hints), directPolicy)
                }
                val ls = c.linkState.value
                val directUp = ls.carrier == CarrierKind.BT_DIRECT || CarrierKind.BT_DIRECT in ls.standby
                if (directUp) {
                    // Proven. Give it a few seconds to be sure it is not a flash in the pan, then drop the spare.
                    if (c.link.hasDialer(CarrierKind.BLUETOOTH)) {
                        if (provenAt == 0L) provenAt = clock.nowMs()
                        if (clock.nowMs() - provenAt > 4_000) c.link.dropCarrier(CarrierKind.BLUETOOTH)
                    }
                } else {
                    provenAt = 0
                    if (!c.link.hasDialer(CarrierKind.BLUETOOTH)) {
                        // Wave two: only after the direct socket has had its chance (or at once if it cannot be tried).
                        if (permitted) delay(1_200)
                        val still = c.linkState.value
                        val directNow = still.carrier == CarrierKind.BT_DIRECT || CarrierKind.BT_DIRECT in still.standby
                        if (!directNow && !c.link.hasDialer(CarrierKind.BLUETOOTH)) {
                            c.link.setDialer(CarrierKind.BLUETOOTH, DataLayerDialer(appContext, DataLayerNames.PHONE_CAPABILITY), bluetoothPolicy)
                        }
                    }
                }
                delay(1_000)
            }
        }
    }

    private val directPolicy = RetryPolicy(minMs = 400, maxMs = 6_000, dialTimeoutMs = 6_000)

    /** The user just allowed Bluetooth: start using the direct route now instead of at the next launch. */
    fun bluetoothPermissionGranted() {
        _pairing.value?.hub?.let { clients[it] }?.let { c ->
            if (!c.link.hasDialer(CarrierKind.BT_DIRECT) && hasBluetoothConnect(appContext)) {
                c.link.setDialer(CarrierKind.BT_DIRECT, RfcommDialer(appContext, hints), directPolicy)
            }
        }
    }

    private val lanEndpoints = ConcurrentHashMap<DeviceId, String>()

    private fun setLan(c: CameraClient, host: String, port: Int) {
        val ep = "$host:$port"
        if (lanEndpoints.put(c.cameraId, ep) == ep) return
        c.link.setDialer(CarrierKind.LAN, TcpDialer(host, port, binder = wifi))
        c.link.nudge()
    }

    private companion object {
        const val TAG = "HeatWatchHub"
        const val WIFI_GRACE_MS = 20_000L
    }
}
