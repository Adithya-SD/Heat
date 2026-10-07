package app.heat.core.host

import app.heat.core.link.AcceptHandler
import app.heat.core.link.CarrierKind
import app.heat.core.link.ConversationRegistry
import app.heat.core.link.DeviceId
import app.heat.core.link.PeerListener
import app.heat.core.link.SessionConfig
import app.heat.core.link.Stream
import app.heat.core.model.AlertKind
import app.heat.core.model.CameraConfig
import app.heat.core.model.ConfigHost
import app.heat.core.model.Msg
import app.heat.core.model.MsgCodec
import app.heat.core.model.Role
import app.heat.core.model.Tier
import app.heat.core.util.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * What the platform does on the camera's behalf. Implemented by the phone's
 * camera service; faked in tests, which is how the whole viewer/camera
 * conversation is verified without a lens.
 *
 * Contract: every method returns quickly and is safe to call from any thread
 * (implementations post to their own thread). [CameraHost] calls them while
 * holding its lock, so they must never call back into the host synchronously.
 */
interface CameraBackend {
    /** The configuration changed (or is being applied for the first time, [old] null). */
    fun onConfig(new: CameraConfig, old: CameraConfig?)

    /** Detection was armed or disarmed (after any countdown). */
    fun onArmed(armed: Boolean)

    /** What viewers collectively want delivered. Called only when it changes. */
    fun onStreaming(video: Boolean, audio: Boolean, tier: Tier)

    /** A viewer needs a fresh keyframe (attached, or lost sync). Already rate-limited. */
    fun requestKeyframe()

    fun snap()
    fun identify()
}

/**
 * The camera's side of every conversation: owns the configuration, the armed
 * state, and the fan-out of status, alerts and video to whoever is watching.
 *
 * All the *decisions* live here, in plain Kotlin, so they are tested on the
 * JVM; the phone only has to implement [CameraBackend].
 */
class CameraHost(
    private val name: () -> String,
    private val scope: CoroutineScope,
    private val clock: Clock,
    initial: CameraConfig,
    initialRev: Long,
    private val backend: CameraBackend,
    private val persist: (CameraConfig, Long) -> Unit = { _, _ -> },
    sessionConfig: SessionConfig = SessionConfig(),
    private val appVersion: String = "1"
) {
    private class Viewer(val id: DeviceId) : PeerListener {
        lateinit var host: CameraHost

        /** Seq of the last SetConfig received from this viewer; echoed so it can retire its pending edits. */
        @Volatile var lastSeq = 0
        @Volatile var watch: Msg.Watch? = null
        @Volatile var lastKeyMs = 0L

        override fun onUp(kind: CarrierKind) = host.viewerUp(this)
        override fun onMessage(data: ByteArray, offset: Int, length: Int) = host.onMessage(this, data, offset, length)
        override fun onMediaDropped(stream: Int) = if (stream == Stream.VIDEO) host.keyframeFor(this) else Unit
        override fun onBestChanged(kind: CarrierKind?) = host.reevaluateInterest()
        override fun onError(error: Throwable) = host.onError(error)
    }

    private val lock = Any()
    private val configHost = ConfigHost(initial, initialRev)
    private val viewers = ConcurrentHashMap<DeviceId, Viewer>()
    private val alertIds = AtomicLong(System.currentTimeMillis())

    private val _config = MutableStateFlow(configHost.config)
    val config: StateFlow<CameraConfig> = _config.asStateFlow()

    private val _armed = MutableStateFlow(false)
    val armed: StateFlow<Boolean> = _armed.asStateFlow()

    private val _armingDeadline = MutableStateFlow(0L)

    /** When the arming countdown ends, on [Clock.SYSTEM]'s timeline, or 0 when none is running. */
    val armingDeadline: StateFlow<Long> = _armingDeadline.asStateFlow()

    private val _viewerCount = MutableStateFlow(0)
    val viewerCount: StateFlow<Int> = _viewerCount.asStateFlow()

    private var base = Msg.Status(armed = false)
    private var armingJob: Job? = null
    private var armingEndsMs = 0L
    private var streamingNow: Triple<Boolean, Boolean, Tier>? = null
    private var lastGlobalKeyMs = 0L
    private var configDirty = false
    private var flushJob: Job? = null

    /** Errors thrown by application callbacks; never fatal. Collected for diagnostics. */
    @Volatile var lastError: Throwable? = null
        private set

    val registry = ConversationRegistry(clock, scope, sessionConfig) { id ->
        viewers.computeIfAbsent(id) { Viewer(it).also { v -> v.host = this } }
    }

    /** Hand this to the platform's listeners (TCP, Data Layer) via an Acceptor. */
    fun acceptHandler(): AcceptHandler = registry.acceptHandler()

    private val ticker = scope.launch {
        while (true) {
            delay(1_000)
            reevaluateInterest()
            _viewerCount.value = viewers.values.count { registry.existing(it.id)?.isUp == true }
            if (_viewerCount.value > 0) broadcastStatus(reliable = false)
            registry.reap()
        }
    }

    /** Applies the stored configuration to the platform. Separate from construction so the owner can finish wiring first. */
    fun start() {
        synchronized(lock) { backend.onConfig(configHost.config, null) }
    }

    // ── platform inputs ──────────────────────────────────────────────

    /** The platform pushes everything it knows (battery, thermal, stream info, lenses...). Armed state is the host's own. */
    fun updateStatus(transform: (Msg.Status) -> Msg.Status) {
        synchronized(lock) { base = transform(base) }
    }

    /** Local UI on the camera itself edits settings here, exactly like a viewer would. */
    fun editLocally(transform: (CameraConfig) -> CameraConfig) {
        synchronized(lock) {
            val old = configHost.config
            configHost.replace(transform(old))
            if (configHost.config != old) afterConfigChange(old)
        }
    }

    fun setArmed(armed: Boolean, delaySec: Int? = null) {
        synchronized(lock) { applyArm(armed, delaySec) }
    }

    /**
     * Raises an alert to every connected viewer, reliably: one that is briefly
     * disconnected still gets it, exactly once, when its link returns.
     */
    fun raiseAlert(kind: AlertKind, changedPct: Float = 0f, escalation: Int = 0) {
        val alert = Msg.Alert(alertIds.incrementAndGet(), kind, System.currentTimeMillis(), changedPct, escalation)
        val bytes = MsgCodec.encode(alert)
        for (v in viewers.values) registry.existing(v.id)?.send(bytes, reliable = true)
    }

    /**
     * Delivers one encoded video frame to every viewer that asked for video.
     * Returns how many accepted it. Never blocks.
     */
    fun sendVideo(flags: Int, ptsUs: Long, data: ByteArray, offset: Int, length: Int): Int {
        var sent = 0
        for (v in viewers.values) {
            if (v.watch?.video != true) continue
            val conv = registry.existing(v.id) ?: continue
            if (conv.sendMedia(Stream.VIDEO, flags, ptsUs, data, offset, length)) sent++
        }
        return sent
    }

    fun sendAudio(ptsUs: Long, data: ByteArray, offset: Int, length: Int) {
        for (v in viewers.values) {
            if (v.watch?.audio != true) continue
            registry.existing(v.id)?.sendMedia(Stream.AUDIO, 0, ptsUs, data, offset, length)
        }
    }

    /** Alert thumbnail: latest-wins still, sent to every connected viewer. */
    fun sendThumbnail(jpeg: ByteArray) {
        for (v in viewers.values) registry.existing(v.id)?.sendMedia(Stream.THUMB, 0, 0, jpeg, 0, jpeg.size)
    }

    fun close() {
        ticker.cancel()
        flushJob?.cancel()
        armingJob?.cancel()
        registry.closeAll()
    }

    // ── from viewers ─────────────────────────────────────────────────

    private fun viewerUp(v: Viewer) {
        val conv = registry.existing(v.id) ?: return
        conv.send(MsgCodec.encode(Msg.Hello(name(), Role.CAMERA, appVersion, listOf("h264", "cfg-patch", "alerts"))))
        synchronized(lock) {
            conv.send(MsgCodec.encode(configHost.state(v.lastSeq)))
            conv.send(MsgCodec.encode(currentStatus()))
        }
        _viewerCount.value = viewers.values.count { registry.existing(it.id)?.isUp == true }
        reevaluateInterest()
    }

    private fun onMessage(v: Viewer, data: ByteArray, offset: Int, length: Int) {
        val msg = MsgCodec.decode(data, offset, length) ?: return
        synchronized(lock) {
            when (msg) {
                is Msg.SetConfig -> {
                    v.lastSeq = msg.seq
                    val old = configHost.config
                    configHost.apply(msg.patch)
                    if (configHost.config != old) afterConfigChange(old) else markConfigDirty()
                }

                is Msg.Arm -> applyArm(msg.armed, msg.delaySec)

                is Msg.Watch -> {
                    val before = v.watch
                    v.watch = msg
                    if (msg.video && before?.video != true) requestKey(force = true)
                    reevaluateLocked()
                }

                is Msg.NeedKey -> requestKey(force = false)
                is Msg.Snap -> backend.snap()
                is Msg.Identify -> backend.identify()
                else -> Unit // camera->viewer messages and anything newer: ignore
            }
        }
        _viewerCount.value = viewers.values.count { registry.existing(it.id)?.isUp == true }
    }

    // ── internals (call with lock held unless noted) ─────────────────

    private fun afterConfigChange(old: CameraConfig) {
        _config.value = configHost.config
        persist(configHost.config, configHost.rev)
        backend.onConfig(configHost.config, old)
        markConfigDirty()
    }

    /** Coalesces bursts (a slider drag is dozens of patches a second) into one echo per ~80 ms. */
    private fun markConfigDirty() {
        configDirty = true
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(CONFIG_FLUSH_MS)
            flushConfig()
        }
    }

    private fun flushConfig() {
        val toSend: List<Pair<DeviceId, ByteArray>> = synchronized(lock) {
            if (!configDirty) return
            configDirty = false
            viewers.values.map { it.id to MsgCodec.encode(configHost.state(it.lastSeq)) }
        }
        for ((id, bytes) in toSend) registry.existing(id)?.send(bytes, reliable = true)
    }

    private fun applyArm(armed: Boolean, delaySec: Int?) {
        armingJob?.cancel()
        armingEndsMs = 0
        _armingDeadline.value = 0
        if (!armed) {
            if (_armed.value) {
                _armed.value = false
                backend.onArmed(false)
            }
            broadcastStatusLocked(reliable = true)
            return
        }
        val delay = (delaySec ?: configHost.config.detect.armDelaySec).coerceAtLeast(0)
        if (delay == 0) {
            if (!_armed.value) {
                _armed.value = true
                backend.onArmed(true)
            }
            broadcastStatusLocked(reliable = true)
            return
        }
        armingEndsMs = clock.nowMs() + delay * 1000L
        _armingDeadline.value = armingEndsMs
        broadcastStatusLocked(reliable = true)
        armingJob = scope.launch {
            delay(delay * 1000L)
            synchronized(lock) {
                armingEndsMs = 0
                _armingDeadline.value = 0
                _armed.value = true
                backend.onArmed(true)
                broadcastStatusLocked(reliable = true)
            }
        }
    }

    private fun currentStatus(): Msg.Status {
        val arming = if (armingEndsMs > 0) ((armingEndsMs - clock.nowMs() + 999) / 1000).toInt().coerceAtLeast(0) else 0
        return base.copy(armed = _armed.value, armingIn = arming)
    }

    private fun broadcastStatus(reliable: Boolean) = synchronized(lock) { broadcastStatusLocked(reliable) }

    private fun broadcastStatusLocked(reliable: Boolean) {
        val bytes = MsgCodec.encode(currentStatus())
        for (v in viewers.values) registry.existing(v.id)?.send(bytes, reliable)
    }

    private fun reevaluateInterest() = synchronized(lock) { reevaluateLocked() }

    private fun reevaluateLocked() {
        var video = false
        var audio = false
        var tier: Tier? = null
        for (v in viewers.values) {
            val w = v.watch ?: continue
            if (registry.existing(v.id)?.isUp != true) continue
            if (w.video) {
                video = true
                tier = if (tier == null) w.tier else minOf(tier, w.tier)
            }
            if (w.audio && configHost.config.stream.allowAudio) audio = true
        }
        val next = Triple(video, audio, tier ?: Tier.MAX)
        if (next != streamingNow) {
            streamingNow = next
            backend.onStreaming(next.first, next.second, next.third)
        }
    }

    private fun keyframeFor(v: Viewer) = synchronized(lock) { requestKey(force = false) }

    /** Rate-limited: a burst of drops from several viewers must produce one keyframe, not a keyframe storm. */
    private fun requestKey(force: Boolean) {
        val now = clock.nowMs()
        if (!force && now - lastGlobalKeyMs < KEYFRAME_MIN_GAP_MS) return
        lastGlobalKeyMs = now
        backend.requestKeyframe()
    }

    private fun onError(e: Throwable) {
        lastError = e
    }

    private companion object {
        const val CONFIG_FLUSH_MS = 80L
        const val KEYFRAME_MIN_GAP_MS = 300L
    }
}
