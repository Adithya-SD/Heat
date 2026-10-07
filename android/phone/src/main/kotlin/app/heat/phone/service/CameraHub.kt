package app.heat.phone.service

import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import app.heat.core.host.BitrateGovernor
import app.heat.core.host.CameraBackend
import app.heat.core.host.CameraHost
import app.heat.core.host.StreamPlan
import app.heat.core.host.StreamProfile
import app.heat.core.link.Acceptor
import app.heat.core.link.Beacon
import app.heat.core.link.Session
import app.heat.core.link.TcpListener
import app.heat.core.model.AlertKind
import app.heat.core.model.CameraConfig
import app.heat.core.model.Facing
import app.heat.core.model.ProtoJson
import app.heat.core.model.StreamInfo
import app.heat.core.model.Tier
import app.heat.core.motion.MotionEngine
import app.heat.core.util.Clock
import app.heat.phone.camera.CameraEngine
import app.heat.phone.camera.H264Encoder
import app.heat.platform.DataLayerHooks
import app.heat.platform.GroupSync
import app.heat.platform.MulticastLockHolder
import app.heat.platform.NetworkWatcher
import app.heat.platform.Vault
import app.heat.platform.fingerprint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.util.WeakHashMap

/** What the on-phone UI shows beyond the camera's settings. */
data class HubState(
    val error: String? = null,
    val cameraReady: Boolean = false,
    val encoding: Boolean = false,
    val streamKbps: Int = 0,
    val lanAddress: String? = null,
    val pairingFingerprint: String = "",
    /** Degrees clockwise from the sensor image to the upright picture the on-screen preview shows. */
    val sensorOrientation: Int = 90,
    val zoomMin: Float = 0.5f,
    val zoomMax: Float = 10f,
    val hasTorch: Boolean = false,
    val battery: Int = -1,
    val thermal: Int = 0,
    val lastMotionMs: Long = 0,
    val motionPercent: Float = 0f,
    val calibrating: Boolean = false
)

@Serializable
private class StoredConfig(val rev: Long, val config: CameraConfig)

/**
 * Everything the phone does as a camera, wired together. Lives exactly as long
 * as [CameraService]. The *decisions* (settings, arming, who watches what) are
 * in the JVM-tested [CameraHost]; this class only connects it to real hardware.
 */
class CameraHub(private val context: Context) : CameraEngine.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = Clock.SYSTEM
    private val appContext = context.applicationContext

    val vault = Vault(appContext)
    val deviceId = vault.deviceId()
    val groupKey = vault.ensureGroupKey()

    private val configFile = File(File(appContext.filesDir, "heat").apply { mkdirs() }, "config.json")
    private val engine = CameraEngine(appContext, this)
    private val motion = MotionEngine()
    private val encoder = H264Encoder(::onEncoded)
    private val governor = BitrateGovernor(StreamPlan.WIFI)

    private val _state = MutableStateFlow(HubState(pairingFingerprint = groupKey.fingerprint()))
    val state: StateFlow<HubState> = _state.asStateFlow()

    private var info: CameraEngine.Info? = null
    private var profile: StreamProfile = StreamPlan.WIFI
    private var streaming = Triple(false, false, Tier.MAX)
    private var deviceRotation = 0
    private var thermal = 0
    private val lastCounters = WeakHashMap<Session, LongArray>()

    private val wake = (appContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "heat:armed")
    private val multicast = MulticastLockHolder(appContext)
    private lateinit var beacon: Beacon
    private lateinit var tcp: TcpListener
    private lateinit var network: NetworkWatcher
    private lateinit var orientation: OrientationEventListener

    // ── the testable brain, with this phone's hardware behind it ─────

    private val backend = object : CameraBackend {
        override fun onConfig(new: CameraConfig, old: CameraConfig?) = applyConfig(new, old)
        override fun onArmed(armed: Boolean) = applyArmed(armed)
        override fun onStreaming(video: Boolean, audio: Boolean, tier: Tier) {
            streaming = Triple(video, audio, tier)
            updateBitrate()
        }
        override fun requestKeyframe() = encoder.requestKeyframe()
        override fun snap() { Log.i(TAG, "snap requested (stills not wired yet)") }
        override fun identify() = this@CameraHub.identify()
    }

    private val stored: StoredConfig = runCatching {
        ProtoJson.decodeFromString(StoredConfig.serializer(), configFile.readText())
    }.getOrNull() ?: StoredConfig(0, CameraConfig())

    val host: CameraHost = CameraHost(
        name = { host.config.value.label },
        scope = scope,
        clock = clock,
        initial = stored.config,
        initialRev = stored.rev,
        backend = backend,
        persist = { c, rev -> scope.launch(Dispatchers.IO) { save(c, rev) } }
    )

    // ── lifecycle ────────────────────────────────────────────────────

    fun start() {
        host.start()
        val acceptor = Acceptor(deviceId, groupKey, scope, handler = host.acceptHandler())
        tcp = TcpListener(scope, acceptor)
        if (!tcp.start()) Log.w(TAG, "TCP port ${TcpListener.DEFAULT_PORT} unavailable: Wi-Fi viewers cannot connect")
        DataLayerHooks.acceptor = acceptor

        multicast.acquire()
        beacon = Beacon(deviceId, groupKey, scope, clock)
        beacon.start()
        advertise()

        network = NetworkWatcher(appContext) {
            advertise()
            _state.value = _state.value.copy(lanAddress = TcpListener.localAddress())
        }
        network.start()
        _state.value = _state.value.copy(lanAddress = TcpListener.localAddress())

        orientation = object : OrientationEventListener(appContext) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                val q = ((degrees + 45) / 90 * 90) % 360
                if (q != deviceRotation) {
                    deviceRotation = q
                    pushStatus()
                }
            }
        }.also { if (it.canDetectOrientation()) it.enable() }

        // Give the watch the household key. Retried until the Data Layer accepts it.
        scope.launch {
            while (true) {
                val ok = runCatching {
                    GroupSync.publish(appContext, groupKey.export(), deviceId, host.config.value.label)
                }.isSuccess
                delay(if (ok) 5 * 60_000L else 20_000L)
            }
        }

        // The encoder runs for the life of the service so a viewer attaching costs a keyframe, not a session rebuild.
        startEncoder(StreamPlan.WIFI)
        engine.start(desiredFrom(host.config.value))

        scope.launch {
            while (true) {
                delay(1_000)
                tick()
            }
        }
    }

    fun close() {
        DataLayerHooks.acceptor = null
        runCatching { network.stop() }
        runCatching { orientation.disable() }
        runCatching { beacon.stop() }
        runCatching { tcp.stop() }
        multicast.release()
        host.close()
        engine.release()
        encoder.release()
        if (wake.isHeld) wake.release()
        scope.cancel()
    }

    // ── UI hooks ─────────────────────────────────────────────────────

    /** Called by the on-screen preview when its surface exists. */
    fun attachPreview(texture: SurfaceTexture) {
        val size = info?.previewSize ?: Size(1280, 960)
        texture.setDefaultBufferSize(size.width, size.height)
        engine.setPreview(Surface(texture), size)
    }

    fun detachPreview() = engine.setPreview(null, null)

    // ── camera engine callbacks ──────────────────────────────────────

    override fun onLuma(y: ByteArray, width: Int, height: Int, rowStride: Int, timestampMs: Long) {
        if (!motion.isEnabled()) return
        val r = motion.analyze(y, width, height, rowStride, 1, System.currentTimeMillis())
        if (r.triggered) {
            host.raiseAlert(AlertKind.MOTION, r.changedPercent, r.escalation)
            _state.value = _state.value.copy(lastMotionMs = System.currentTimeMillis(), motionPercent = r.changedPercent)
            AlertNotifier.motion(appContext, host.config.value)
        }
        if (_state.value.calibrating != r.calibrating) _state.value = _state.value.copy(calibrating = r.calibrating)
    }

    override fun onInfo(info: CameraEngine.Info) {
        this.info = info
        _state.value = _state.value.copy(
            cameraReady = true, sensorOrientation = info.sensorOrientation, zoomMin = info.zoomMin, zoomMax = info.zoomMax, hasTorch = info.hasTorch
        )
        // The reader's size is known only now; the motion engine's grid does not care, but a restart clears learned noise.
        motion.reset()
        pushStatus()
    }

    override fun onError(message: String?) {
        _state.value = _state.value.copy(error = message, cameraReady = message == null && _state.value.cameraReady)
        pushStatus()
    }

    // ── encoder ──────────────────────────────────────────────────────

    private fun startEncoder(p: StreamProfile) {
        profile = p
        val surface = encoder.start(p.width, p.height, p.fps, governor.bps.coerceIn(p.minBps, p.maxBps))
        if (surface == null) {
            _state.value = _state.value.copy(error = "Video encoder unavailable on this phone", encoding = false)
        } else {
            engine.setEncoder(surface)
            _state.value = _state.value.copy(encoding = true)
        }
    }

    private fun onEncoded(flags: Int, ptsUs: Long, data: ByteArray, offset: Int, length: Int) {
        host.sendVideo(flags, ptsUs, data, offset, length)
    }

    private fun ceiling(): Int {
        val c = host.config.value
        return StreamPlan.ceiling(profile, streaming.third, c.stream.quality, c.stream.muddiness, thermal)
    }

    /** A viewer's wishes changed: re-apply the ceiling now rather than waiting for the next second. */
    private fun updateBitrate() {
        if (encoder.running) encoder.setBitrate(governor.sample(accumulatedOffered, accumulatedDropped, ceiling(), clock.nowMs()))
        pushStatus()
    }

    // ── config / arming ──────────────────────────────────────────────

    private fun desiredFrom(c: CameraConfig) = CameraEngine.Desired(
        facing = c.camera.facing,
        zoom = c.camera.zoom,
        exposure = c.camera.exposure,
        torch = c.camera.torch,
        night = c.camera.night,
        lockExposure = c.camera.lockExposure,
        antiFlicker = c.camera.antiFlicker,
        lowPower = !streaming.first && host.armed.value && !screenWanted
    )

    @Volatile var screenWanted = false

    private fun applyConfig(new: CameraConfig, old: CameraConfig?) {
        motion.configure(new.detect, new.camera.night)
        engine.update(desiredFrom(new))
        if (old?.label != new.label) advertise()
        if (old != null && old.camera.facing != new.camera.facing) motion.reset()
        pushStatus()
    }

    private fun applyArmed(armed: Boolean) {
        motion.setEnabled(armed)
        if (armed && !wake.isHeld) wake.acquire()
        if (!armed && wake.isHeld) wake.release()
        engine.update(desiredFrom(host.config.value))
        CameraService.refreshNotification(appContext, armed)
        pushStatus()
    }

    private fun advertise() {
        if (!::beacon.isInitialized) return
        Log.i(TAG, "advertising \"${host.config.value.label}\" on ${TcpListener.localAddress()}:${TcpListener.DEFAULT_PORT}")
        beacon.advertise(TcpListener.DEFAULT_PORT, host.config.value.label)
    }

    private fun identify() {
        scope.launch {
            val v = appContext.getSystemService(Vibrator::class.java)
            repeat(3) {
                v?.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
                host.editLocally { it.copy(camera = it.camera.copy(torch = true)) }
                delay(250)
                host.editLocally { it.copy(camera = it.camera.copy(torch = false)) }
                delay(250)
            }
        }
    }

    // ── status ───────────────────────────────────────────────────────

    private fun tick() {
        val bm = appContext.getSystemService(BatteryManager::class.java)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val pm = appContext.getSystemService(PowerManager::class.java)
        thermal = when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> 0
            PowerManager.THERMAL_STATUS_MODERATE -> 1
            else -> 2
        }

        // Aggregate congestion across every viewer's best session this second.
        var offered = 0L
        var dropped = 0L
        for ((_, conv) in host.registry.all()) {
            val s = conv.sessions.best()?.session ?: continue
            val last = lastCounters.getOrPut(s) { longArrayOf(s.stats.videoOffered.get(), s.stats.videoDropped.get()) }
            val o = s.stats.videoOffered.get()
            val d = s.stats.videoDropped.get()
            offered += o - last[0]
            dropped += d - last[1]
            last[0] = o
            last[1] = d
        }
        // The governor differences cumulative counters, so keep running totals across sessions coming and going.
        accumulatedOffered += offered
        accumulatedDropped += dropped
        val bps = governor.sample(accumulatedOffered, accumulatedDropped, ceiling(), clock.nowMs())
        if (encoder.running) encoder.setBitrate(bps)

        // Battery policy: warn once on the way down, and stop watching rather than die silently in the night.
        val power = host.config.value.power
        if (!charging && level >= 0) {
            if (power.lowBatteryWarnPercent > 0 && level <= power.lowBatteryWarnPercent && !warnedLow) {
                warnedLow = true
                host.raiseAlert(AlertKind.LOW_BATTERY)
            }
            if (power.lowBatteryStopPercent > 0 && level <= power.lowBatteryStopPercent && host.armed.value) {
                host.setArmed(false)
                host.raiseAlert(AlertKind.LOW_BATTERY)
            }
        }
        if (charging || level > (power.lowBatteryWarnPercent + 5)) warnedLow = false

        if (++tickCount % 5 == 0) {
            Log.i(TAG, "alive: encoder frames=${encoder.framesOut} ${encoder.bitrate / 1000}kbps viewers=${host.viewerCount.value} cam=${_state.value.cameraReady} armed=${host.armed.value}")
        }
        _state.value = _state.value.copy(battery = level, thermal = thermal, streamKbps = encoder.bitrate / 1000)
        host.updateStatus { it.copy(battery = level, charging = charging, thermal = thermal) }
        pushStatus()
    }

    private var tickCount = 0
    private var warnedLow = false
    private var accumulatedOffered = 0L
    private var accumulatedDropped = 0L

    private fun pushStatus() {
        val i = info
        val c = host.config.value
        val rotation = if (i == null) 0 else {
            val dev = if (i.facing == Facing.FRONT) -deviceRotation else deviceRotation
            (i.sensorOrientation + dev + 360) % 360
        }
        val digital = c.camera.zoom >= 1.05f
        host.updateStatus {
            it.copy(
                streaming = streaming.first && encoder.running,
                stream = if (encoder.running) StreamInfo(encoder.width, encoder.height, profile.fps, encoder.bitrate, "avc", rotation) else null,
                lenses = emptyList(),
                lensLabel = if (c.camera.zoom < 0.95f) "0.5x" else "1x",
                digitalZoom = digital,
                hasTorch = i?.hasTorch == true,
                error = _state.value.error
            )
        }
    }

    private fun save(c: CameraConfig, rev: Long) {
        runCatching {
            Vault.atomicWrite(configFile, ProtoJson.encodeToString(StoredConfig.serializer(), StoredConfig(rev, c)).toByteArray())
        }.onFailure { Log.w(TAG, "could not save config", it) }
    }

    private companion object {
        const val TAG = "HeatHub"
    }
}
