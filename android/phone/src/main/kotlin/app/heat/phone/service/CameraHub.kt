package app.heat.phone.service

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.BatteryManager
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import app.heat.core.camera.LensEntry
import app.heat.core.camera.LensStrategy
import app.heat.core.host.BitrateGovernor
import app.heat.core.host.CameraBackend
import app.heat.core.host.CameraHost
import app.heat.core.host.StreamPlan
import app.heat.core.host.StreamProfile
import app.heat.core.link.Acceptor
import app.heat.core.link.Beacon
import app.heat.core.link.CarrierKind
import app.heat.core.link.Session
import app.heat.core.link.TcpListener
import app.heat.core.model.AlertKind
import app.heat.core.model.CameraConfig
import app.heat.core.model.Facing
import app.heat.core.model.LensInfo
import app.heat.core.model.ProtoJson
import app.heat.core.model.StreamInfo
import app.heat.core.model.Tier
import app.heat.core.motion.MotionEngine
import app.heat.core.util.Clock
import app.heat.phone.camera.CameraEngine
import app.heat.phone.camera.H264Encoder
import app.heat.phone.rooms.RoomManager
import app.heat.platform.DataLayerHooks
import app.heat.platform.GroupSync
import app.heat.platform.MulticastLockHolder
import app.heat.platform.NetworkWatcher
import app.heat.platform.RfcommListener
import app.heat.platform.Vault
import app.heat.platform.fingerprint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    /** The zoom actually on screen right now (smoothed), as opposed to the one asked for. */
    val zoomNow: Float = 1f,
    val lenses: List<LensEntry> = emptyList(),
    val lensStrategy: LensStrategy = LensStrategy.LOGICAL,
    val activeLens: LensEntry? = null,
    /** True while the camera is hopping to another lens and no new frames are flowing. */
    val lensSwitching: Boolean = false,
    val cameraDiagnostics: String = "",
    val hasTorch: Boolean = false,
    val manualFocus: Boolean = false,
    val manualExposure: Boolean = false,
    val battery: Int = -1,
    val thermal: Int = 0,
    val lastMotionMs: Long = 0,
    val motionPercent: Float = 0f,
    val calibrating: Boolean = false,
    val carrier: CarrierKind? = null,
    val encoderLabel: String = ""
)

@Serializable
private data class StoredConfig(val rev: Long, val config: CameraConfig)

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
    private val governor = BitrateGovernor(StreamPlan.GOVERNOR_RANGE)

    private val _state = MutableStateFlow(HubState(pairingFingerprint = groupKey.fingerprint()))
    val state: StateFlow<HubState> = _state.asStateFlow()

    private val startedAt = android.os.SystemClock.elapsedRealtime()
    private val marks = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Logs how long after the hub was created each milestone happened, once. This is how start-up speed is measured. */
    private fun mark(what: String) {
        if (marks.putIfAbsent(what, 1L) != null) return
        Log.i(TAG, "timeline: $what at +${android.os.SystemClock.elapsedRealtime() - startedAt} ms")
    }

    private var watchRequestedAt = 0L
    private var info: CameraEngine.Info? = null
    // Starts small: the first viewer is often a watch on Bluetooth, and a picture the link cannot carry costs seconds of
    // freezing before the encoder is restarted smaller. A Wi-Fi viewer lifts it to the wide profile the moment it connects.
    private var profile: StreamProfile = StreamPlan.BLUETOOTH

    // The frame rate asked of the camera: 60 when the road is clear, fewer frames as it narrows (see FrameRateGovernor).
    private val fpsGov = app.heat.core.host.FrameRateGovernor()
    @Volatile private var targetFps = 30
    private var fpsCapFromCamera = 60
    private var slowFrames = 0
    private var streaming = Triple(false, false, Tier.MAX)
    private var deviceRotation = 0
    private var thermal = 0
    private val lastCounters = WeakHashMap<Session, LongArray>()
    private var slowestCarrier: CarrierKind? = null
    private var btOnlySinceMs = 0L

    private val wake = (appContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "heat:armed")
    private val multicast = MulticastLockHolder(appContext)
    private lateinit var beacon: Beacon
    private lateinit var tcp: TcpListener
    private lateinit var rfcomm: RfcommListener
    private lateinit var network: NetworkWatcher
    private lateinit var orientation: OrientationEventListener

    // ── the testable brain, with this phone's hardware behind it ─────

    private val backend = object : CameraBackend {
        override fun onConfig(new: CameraConfig, old: CameraConfig?) = applyConfig(new, old)
        override fun onArmed(armed: Boolean) = applyArmed(armed)
        override fun onStreaming(video: Boolean, audio: Boolean, tier: Tier) {
            if (video && !streaming.first) {
                watchRequestedAt = android.os.SystemClock.elapsedRealtime()
                Log.i(TAG, "timeline: a viewer asked for video at +${watchRequestedAt - startedAt} ms")
            }
            streaming = Triple(video, audio, tier)
            updateBitrate()
        }
        override fun requestKeyframe() = encoder.requestKeyframe()
        override fun snap() { Log.i(TAG, "snap requested (stills not wired yet)") }
        override fun identify() = this@CameraHub.identify()
    }

    private val stored: StoredConfig = runCatching {
        ProtoJson.decodeFromString(StoredConfig.serializer(), configFile.readText())
    }.getOrNull()?.let { s ->
        // Every setting is kept, except where the zoom was left: the camera always opens at 1x on the main lens.
        s.copy(config = s.config.copy(camera = s.config.camera.copy(zoom = 1f)))
    } ?: StoredConfig(0, CameraConfig())

    // A slider drag or a bezel spin changes the config dozens of times a second; the file only needs the last.
    private var saveJob: Job? = null
    @Volatile private var latest: StoredConfig = stored

    val host: CameraHost = CameraHost(
        name = { host.config.value.label },
        scope = scope,
        clock = clock,
        initial = stored.config,
        initialRev = stored.rev,
        backend = backend,
        persist = { c, rev ->
            latest = StoredConfig(rev, c)
            saveJob?.cancel()
            saveJob = scope.launch(Dispatchers.IO) {
                delay(SAVE_DEBOUNCE_MS)
                save(latest)
            }
        }
    )

    /** Heat Link: this phone's own room, and the rooms it joins. Sessions that arrive over the internet land in [host]. */
    val rooms = RoomManager(appContext, scope, deviceId, host.acceptHandler())

    // ── lifecycle ────────────────────────────────────────────────────

    fun start() {
        host.start()
        val acceptor = Acceptor(deviceId, groupKey, scope, handler = host.acceptHandler())
        tcp = TcpListener(scope, acceptor)
        if (!tcp.start()) Log.w(TAG, "TCP port ${TcpListener.DEFAULT_PORT} unavailable: Wi-Fi viewers cannot connect")
        DataLayerHooks.acceptor = acceptor
        rfcomm = RfcommListener(appContext, scope, acceptor).also { it.start() }
        mark("accepting viewers (Wi-Fi and Bluetooth)")
        rooms.restore()

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

        host.onViewerPatch = { who, patch -> Log.i(TAG, "settings from $who: $patch") }
        orientation = object : OrientationEventListener(appContext) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                val q = ((degrees + 45) / 90 * 90) % 360
                if (q != deviceRotation) {
                    deviceRotation = q
                    pushStatus()
                }
            }
        }.also {
            // The phone's own screen stays portrait, but the stream carries how the phone is held, so the watch and
            // other viewers turn the picture upright when the phone is turned (the viewer rotates, the app does not).
            if (it.canDetectOrientation()) it.enable()
        }

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
        governor.reset((StreamPlan.BLUETOOTH.maxBps * 0.7).toInt())
        startEncoder(StreamPlan.BLUETOOTH)
        engine.analysisIntervalMs = 100L
        engine.analysisEnabled = motion.isEnabled()
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
        runCatching { rooms.shutdown() }
        runCatching { network.stop() }
        runCatching { orientation.disable() }
        runCatching { beacon.stop() }
        runCatching { tcp.stop() }
        runCatching { rfcomm.stop() }
        multicast.release()
        host.close()
        engine.release()
        encoder.release()
        if (wake.isHeld) wake.release()
        saveJob?.cancel()
        runCatching { save(latest) }
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
            AlertNotifier.motion(appContext, host.config.value, host.viewerCount.value)
        }
        if (_state.value.calibrating != r.calibrating) _state.value = _state.value.copy(calibrating = r.calibrating)
    }

    override fun onInfo(info: CameraEngine.Info) {
        mark("camera open")
        this.info = info
        _state.value = _state.value.copy(
            cameraReady = true, sensorOrientation = info.sensorOrientation, zoomMin = info.zoomMin, zoomMax = info.zoomMax,
            hasTorch = info.hasTorch, manualFocus = info.manualFocus, manualExposure = info.manualExposure,
            lenses = info.lenses, lensStrategy = info.strategy, cameraDiagnostics = info.diagnostics,
            activeLens = _state.value.activeLens ?: info.lenses.firstOrNull { it.ratio >= 0.99f }
        )
        // The reader's size is known only now; the motion engine's grid does not care, but a restart clears learned noise.
        motion.reset()
        pushStatus()
    }

    override fun onZoom(zoom: Float, lens: LensEntry?) {
        _state.value = _state.value.copy(zoomNow = zoom, activeLens = lens ?: _state.value.activeLens)
        pushStatus()
    }

    override fun onLensSwitch(switching: Boolean) {
        _state.value = _state.value.copy(lensSwitching = switching)
        // A different lens is a different picture: the next frame must not be a delta against the old one.
        if (!switching) encoder.requestKeyframe()
    }

    override fun onError(message: String?) {
        _state.value = _state.value.copy(error = message, cameraReady = message == null && _state.value.cameraReady)
        pushStatus()
    }

    // ── encoder ──────────────────────────────────────────────────────

    private fun startEncoder(p: StreamProfile) {
        profile = p
        val surface = encoder.start(p.width, p.height, p.fps, governor.bps.coerceIn(p.minBps, p.maxBps), p.gopSec)
        if (surface == null) {
            _state.value = _state.value.copy(error = "Video encoder unavailable on this phone", encoding = false)
        } else {
            engine.setEncoder(surface)
            _state.value = _state.value.copy(encoding = true, encoderLabel = "${p.width}x${p.height}@${p.fps}")
        }
    }

    // ── pictures and clips ───────────────────────────────────────────

    /** What this phone has captured: pictures from the preview, clips written from the encoder's own stream. */
    val gallery = app.heat.platform.Gallery(appContext, "heat/phone-gallery")
    @Volatile private var clipSink: app.heat.platform.Gallery.ClipSink? = null

    /** How the phone is held, as the stream reports it: what makes a viewer (or a saved clip) turn the picture upright. */
    private fun streamRotation(): Int {
        val i = info ?: return 0
        val dev = if (i.facing == Facing.FRONT) -deviceRotation else deviceRotation
        return (i.sensorOrientation + dev + 360) % 360
    }

    /**
     * Records a clip from the encoder's stream, which is already running for viewers: no second encode and no extra
     * camera use. It starts at the next keyframe (asked for now), ends by itself after the clip length in settings,
     * or when stopped. [thumb] is the picture shown for it in the gallery.
     */
    fun startClip(thumb: android.graphics.Bitmap?): Boolean {
        if (!encoder.running) return false
        val sink = gallery.startClip(encoder.width, encoder.height, streamRotation(), host.config.value.label, emptyList(), thumb) { clipSink = null }
            ?: return true
        clipSink = sink
        encoder.requestKeyframe()
        return true
    }

    fun stopClip() = gallery.stopClip()

    private fun onEncoded(flags: Int, ptsUs: Long, data: ByteArray, offset: Int, length: Int) {
        clipSink?.write(flags and app.heat.core.link.MediaFlags.KEYFRAME != 0, ptsUs, data, offset, length)
        mark("first encoded frame")
        val asked = watchRequestedAt
        if (asked != 0L && flags and app.heat.core.link.MediaFlags.KEYFRAME != 0) {
            watchRequestedAt = 0
            Log.i(TAG, "timeline: first keyframe (${length} bytes) out ${android.os.SystemClock.elapsedRealtime() - asked} ms after the viewer asked")
        }
        host.sendVideo(flags, ptsUs, data, offset, length)
    }

    private fun ceiling(): Int {
        val c = host.config.value
        return StreamPlan.ceiling(profile, streaming.third, c.stream.quality, c.stream.muddiness, thermal, slowestCarrier)
    }

    /** A viewer's wishes changed: re-apply the ceiling now rather than waiting for the next second. */
    private fun updateBitrate() {
        if (encoder.running) encoder.setBitrate(governor.sample(accumulatedOffered, accumulatedDropped, ceiling(), clock.nowMs()))
        pushStatus()
    }

    /**
     * Follow the viewers' road. While anyone is on Wi-Fi the wide profile stays. If every viewer has been on
     * Bluetooth for a good while, the encoder drops to the small profile (a keyframe on a slow link is the thing
     * that makes pictures stall, and it scales with resolution). The wait matters: a watch that connects over
     * Bluetooth first and finds Wi-Fi a second later must not make the picture restart twice.
     */
    private fun followCarriers(carriers: List<CarrierKind>, now: Long) {
        slowestCarrier = carriers.minByOrNull { StreamPlan.carrierCap(it) }
        if (carriers.isEmpty()) {
            btOnlySinceMs = 0
            return
        }
        val btOnly = carriers.all { it.isBluetooth }
        if (!btOnly) {
            btOnlySinceMs = 0
            if (profile != StreamPlan.WIFI) switchProfile(StreamPlan.WIFI, startBps = 2_000_000)
            return
        }
        if (btOnlySinceMs == 0L) btOnlySinceMs = now
        if (now - btOnlySinceMs >= BT_SETTLE_MS && profile != StreamPlan.BLUETOOTH) {
            switchProfile(StreamPlan.BLUETOOTH, startBps = (StreamPlan.BLUETOOTH.maxBps * 0.7).toInt())
        }
    }

    private fun switchProfile(p: StreamProfile, startBps: Int) {
        Log.i(TAG, "encoder profile -> ${p.width}x${p.height}@${p.fps}")
        governor.reset(startBps)
        fpsGov.reset(30)
        targetFps = 30
        fpsCapFromCamera = 60
        startEncoder(p)
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
        focus = c.camera.focus,
        whiteBalance = c.camera.whiteBalance,
        iso = c.camera.iso,
        shutterDenom = c.camera.shutterDenom,
        blendLenses = c.camera.blendLenses,
        lowPower = !streaming.first && host.armed.value && !screenWanted,
        fps = targetFps
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
        // With nothing to detect, the analysis stream costs a copy and a pass per frame for no reason.
        engine.analysisEnabled = armed
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

        followCarriers(host.videoCarriers(), clock.nowMs())

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
        adaptFrameRate(bps)

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
            Log.i(TAG, "ceiling ${ceiling()} bps: tier=${streaming.third} quality=${host.config.value.stream.quality} muddiness=${host.config.value.stream.muddiness} thermal=$thermal carrier=$slowestCarrier profile=${profile.width}x${profile.height}")
            Log.i(TAG, "alive: sent=${accumulatedOffered} dropped=${accumulatedDropped} encoder frames=${encoder.framesOut} ${encoder.bitrate / 1000}kbps ${_state.value.encoderLabel} viewers=${host.viewerCount.value} via=$slowestCarrier cam=${_state.value.cameraReady} armed=${host.armed.value}")
        }
        _state.value = _state.value.copy(battery = level, thermal = thermal, streamKbps = encoder.bitrate / 1000, carrier = slowestCarrier)
        host.updateStatus { it.copy(battery = level, charging = charging, thermal = thermal) }
        pushStatus()
    }

    /**
     * Fewer, better frames as the road narrows; up to 60 when it is clear. The camera is asked for the rate directly
     * (no encoder restart), and if it cannot really deliver what was asked (some phones ignore a 60 fps range when
     * three outputs are open) the cap drops to what it does deliver, so no rate is ever claimed that is not real.
     */
    private fun adaptFrameRate(bps: Int) {
        if (!encoder.running) return
        val camMax = minOf(profile.fps, info?.maxFps ?: 30)
        val measured = engine.measuredFps
        if (measured in 1..(targetFps * 7 / 10) && targetFps >= 45) {
            if (++slowFrames >= 3) { fpsCapFromCamera = if (measured >= 30) 30 else 15; slowFrames = 0 }
        } else slowFrames = 0
        val next = fpsGov.update(bps, encoder.width * encoder.height, minOf(camMax, fpsCapFromCamera), clock.nowMs())
        if (next != targetFps) {
            Log.i(TAG, "frame rate $targetFps -> $next (bitrate ${bps / 1000} kbps, camera delivers $measured)")
            targetFps = next
            engine.update(desiredFrom(host.config.value))
            pushStatus()
        }
    }

    private var tickCount = 0
    private var warnedLow = false
    private var accumulatedOffered = 0L
    private var accumulatedDropped = 0L

    private fun pushStatus() {
        val i = info
        val c = host.config.value
        val s = _state.value
        val rotation = streamRotation()
        val lens = s.activeLens
        val digital = lens != null && s.zoomNow >= lens.ratio * 1.05f
        host.updateStatus {
            it.copy(
                streaming = streaming.first && encoder.running,
                stream = if (encoder.running) StreamInfo(encoder.width, encoder.height, targetFps, encoder.bitrate, "avc", rotation) else null,
                lenses = s.lenses.map { l -> LensInfo(l.id, l.label, l.ratio) },
                activeLens = lens?.id,
                lensLabel = lens?.let { l -> "${l.label}x" } ?: if (c.camera.zoom < 0.95f) "0.5x" else "1x",
                digitalZoom = digital,
                hasTorch = i?.hasTorch == true,
                manualFocus = i?.manualFocus == true,
                manualExposure = i?.manualExposure == true,
                error = s.error
            )
        }
    }

    private fun save(c: StoredConfig) {
        runCatching {
            Vault.atomicWrite(configFile, ProtoJson.encodeToString(StoredConfig.serializer(), c).toByteArray())
        }.onFailure { Log.w(TAG, "could not save config", it) }
    }

    private companion object {
        const val TAG = "HeatHub"
        const val SAVE_DEBOUNCE_MS = 400L
        const val BT_SETTLE_MS = 3_000L
    }
}
