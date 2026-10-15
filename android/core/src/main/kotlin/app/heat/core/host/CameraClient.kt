package app.heat.core.host

import app.heat.core.link.CarrierKind
import app.heat.core.link.DeviceId
import app.heat.core.link.GroupKey
import app.heat.core.link.LinkState
import app.heat.core.link.PeerLink
import app.heat.core.link.PeerListener
import app.heat.core.link.SessionConfig
import app.heat.core.link.Stream
import app.heat.core.model.CameraConfig
import app.heat.core.model.ConfigSync
import app.heat.core.model.Msg
import app.heat.core.model.MsgCodec
import app.heat.core.model.Role
import app.heat.core.model.Tier
import app.heat.core.util.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Receives encoded video on the session's reader thread. [data] is borrowed: consume it before returning. */
fun interface VideoSink {
    fun onFrame(flags: Int, ptsUs: Long, data: ByteArray, offset: Int, length: Int)
}

/**
 * The viewer's side of ONE camera: everything a screen needs, as state.
 *
 * Bound to a single camera by construction: it owns the link to that camera,
 * the settings mirror for that camera, and nothing that can address another.
 * That is what makes "I changed camera B's setting but it landed on camera A"
 * impossible, not merely unlikely.
 */
class CameraClient(
    val cameraId: DeviceId,
    selfName: String,
    private val self: DeviceId,
    group: GroupKey,
    private val scope: CoroutineScope,
    clock: Clock,
    sessionConfig: SessionConfig = SessionConfig(),
    private val role: Role = Role.VIEWER,
    private val appVersion: String = "1",
    /** Hold the peer to [cameraId]. Off for a room, where the camera is whoever holds the code and its id is not known beforehand. */
    pin: Boolean = true
) : PeerListener {

    private val name = selfName
    private val sync = ConfigSync()

    val link: PeerLink = PeerLink(if (pin) cameraId else null, self, group, scope, clock, this, sessionConfig)

    private val _config = MutableStateFlow<CameraConfig?>(null)

    /** What to render: the camera's settings with this viewer's unacknowledged edits laid over them. */
    val config: StateFlow<CameraConfig?> = _config.asStateFlow()

    private val _status = MutableStateFlow<Msg.Status?>(null)
    val status: StateFlow<Msg.Status?> = _status.asStateFlow()

    private val _hello = MutableStateFlow<Msg.Hello?>(null)

    /** Who the camera says it is (its name). */
    val hello: StateFlow<Msg.Hello?> = _hello.asStateFlow()

    private val _alerts = MutableSharedFlow<Msg.Alert>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Each alert once, in order, including ones raised while this viewer was briefly disconnected. */
    val alerts: SharedFlow<Msg.Alert> = _alerts.asSharedFlow()

    val linkState: StateFlow<LinkState> get() = link.state

    @Volatile var videoSink: VideoSink? = null
    @Volatile var thumbnailSink: ((ByteArray) -> Unit)? = null

    /** The most recent [watch] request; resent whenever a new session comes up so the camera never forgets. */
    @Volatile private var wanted: Msg.Watch = Msg.Watch(video = false)

    fun start() = link.start()
    fun stop() = link.stop()

    // ── commands ─────────────────────────────────────────────────────

    /** Edit this camera's settings. Shows instantly; the camera's echo confirms or corrects. */
    fun edit(transform: (CameraConfig) -> CameraConfig) {
        sync.edit(transform)?.let { link.send(MsgCodec.encode(it)) }
        _config.value = sync.effective
    }

    /** The messages a [app.heat.core.control.BezelController] (or anything else that edits) should send. */
    fun configSync(): ConfigSync = sync

    fun send(msg: Msg, reliable: Boolean = true) = link.send(MsgCodec.encode(msg), reliable)

    /** Raw config edits made through [configSync] (e.g. by the bezel) must refresh [config] afterwards. */
    fun refresh() {
        _config.value = sync.effective
    }

    fun arm(armed: Boolean, delaySec: Int? = null) = send(Msg.Arm(armed, delaySec))

    /** Say what we want delivered. Call with video = true while the picture is on screen, false when it is not. */
    fun watch(video: Boolean, audio: Boolean = false, tier: Tier = Tier.MAX) {
        wanted = Msg.Watch(video, audio, tier)
        send(wanted)
    }

    fun needKeyframe() = send(Msg.NeedKey, reliable = false)
    fun snap() = send(Msg.Snap)
    fun identify() = send(Msg.Identify)

    // ── from the camera ──────────────────────────────────────────────

    override fun onUp(kind: CarrierKind) {
        link.send(MsgCodec.encode(Msg.Hello(name, role, appVersion)))
        // The camera has forgotten what we were watching if it restarted; the reliable layer
        // delivered the old request exactly once, but a restart needs it said again.
        if (wanted.video || wanted.audio) link.send(MsgCodec.encode(wanted))
    }

    override fun onMessage(data: ByteArray, offset: Int, length: Int) {
        when (val msg = MsgCodec.decode(data, offset, length) ?: return) {
            is Msg.ConfigState -> {
                sync.onRemote(msg)
                _config.value = sync.effective
            }

            is Msg.Status -> _status.value = msg
            is Msg.Hello -> _hello.value = msg
            is Msg.Alert -> _alerts.tryEmit(msg)
            else -> Unit
        }
    }

    override fun onMedia(
        kind: CarrierKind, stream: Int, flags: Int, ptsUs: Long, data: ByteArray, offset: Int, length: Int
    ) {
        when (stream) {
            Stream.VIDEO -> videoSink?.onFrame(flags, ptsUs, data, offset, length)
            Stream.THUMB -> thumbnailSink?.invoke(data.copyOfRange(offset, offset + length))
        }
    }

    /** A new route (or a new session) means the decoder has no reference frames: ask for a keyframe. */
    override fun onMediaDropped(stream: Int) {
        if (stream == Stream.VIDEO && wanted.video) needKeyframe()
    }
}
