package app.heat.phone.rooms

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import app.heat.core.crypto.Crypto
import app.heat.core.host.CameraClient
import app.heat.core.host.VideoSink
import app.heat.core.link.Acceptor
import app.heat.core.link.AcceptHandler
import app.heat.core.link.CarrierKind
import app.heat.core.link.DeviceId
import app.heat.core.link.MediaFlags
import app.heat.core.model.Msg
import app.heat.core.model.ProtoJson
import app.heat.core.room.RoomCode
import app.heat.core.util.Clock
import app.heat.platform.PreviewDecoder
import app.heat.rooms.PeerServer
import app.heat.rooms.RoomDialer
import app.heat.rooms.RoomHost
import app.heat.rooms.RoomSlotHost
import app.heat.rooms.RoomNet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/** The state of this phone's own room, for the Heat Link screen. */
data class HostingState(
    /** The code people use to join. Empty until the room has been opened once. */
    val code: String = "",
    val open: Boolean = false,
    val phase: RoomHost.Phase = RoomHost.Phase.CONNECTING,
    /** Which of the room's host slots this phone holds (0 is the first host), or -1 while it has none. */
    val slot: Int = -1
)

@Serializable
private data class StoredRooms(val code: String = "", val hosting: Boolean = false, val recent: List<String> = emptyList())

/**
 * Heat Link on the phone. Two independent jobs:
 *
 *  - **Host**: open this phone's camera to anyone holding a code. Sessions that arrive over the internet are handed to
 *    the very same [CameraHost] the watch uses, so a browser sees exactly what the watch sees and can change the
 *    same settings. The room code stays the same until the user asks for a new one, so an invite link keeps working.
 *  - **Viewer**: join someone else's room ([RoomViewer]).
 */
class RoomManager(
    context: Context,
    private val scope: CoroutineScope,
    private val deviceId: DeviceId,
    private val accepted: AcceptHandler
) {
    private val appContext = context.applicationContext
    private val file = File(File(appContext.filesDir, "heat").apply { mkdirs() }, "rooms.json")
    private val net by lazy { RoomNet(appContext, scope) }

    private var stored: StoredRooms = (runCatching { ProtoJson.decodeFromString(StoredRooms.serializer(), file.readText()) }.getOrNull() ?: StoredRooms())
        .let { s ->
            // Every code is 8 symbols now. A phone that saved the old 12-symbol code gets a fresh short one, once; the old
            // code stops working (typing a 12-symbol code someone else still holds is still accepted when joining).
            if (s.code.isNotEmpty() && s.code.length != RoomCode.LENGTH) s.copy(code = RoomCode.generate()).also { save(it) } else s
        }

    private val _hosting = MutableStateFlow(HostingState(stored.code, false))
    val hosting: StateFlow<HostingState> = _hosting.asStateFlow()

    private val _recent = MutableStateFlow(stored.recent)
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    private var host: RoomSlotHost? = null
    private var watcher: Job? = null

    /** Call once the camera hub is up: reopens the room if it was open when the app last ran. */
    fun restore() {
        if (stored.hosting && stored.code.isNotEmpty()) open()
    }

    /** Opens the room, making a code first if there never was one. */
    fun open() {
        if (host != null) return
        if (stored.code.isEmpty()) stored = stored.copy(code = RoomCode.generate())
        stored = stored.copy(hosting = true)
        save()
        val code = stored.code
        _hosting.value = HostingState(code, true, RoomHost.Phase.CONNECTING)
        watcher = scope.launch(Dispatchers.Default) {
            // Deriving the key is deliberately slow (150,000 rounds); it never blocks the UI thread.
            val key = RoomCode.groupKey(code)
            val rid = RoomCode.roomId(code)
            val h = RoomSlotHost(net, rid, Acceptor(deviceId, key, scope, handler = accepted))
            host = h
            h.start()
            h.state.collect { s -> _hosting.value = _hosting.value.copy(phase = s.phase, slot = s.slot) }
        }
    }

    fun close() {
        watcher?.cancel(); watcher = null
        host?.stop(); host = null
        stored = stored.copy(hosting = false)
        save()
        _hosting.value = _hosting.value.copy(open = false, phase = RoomHost.Phase.CONNECTING)
    }

    /**
     * Hosts this phone's camera in a room that already exists (somebody else's code): it takes the next free host
     * slot there. The code shown for this phone is that room's until [newCode] makes its own.
     */
    fun hostIn(code: String) {
        val c = RoomCode.normalize(code)
        if (!RoomCode.isComplete(c)) return
        close()
        stored = stored.copy(code = c, hosting = true)
        save()
        _hosting.value = HostingState(c, false)
        open()
    }

    /** A new code: everyone who had the old one is locked out. The room reopens under it if it was open. */
    fun newCode() {
        val wasOpen = host != null
        close()
        stored = stored.copy(code = RoomCode.generate())
        _hosting.value = HostingState(stored.code, false)
        save()
        if (wasOpen) open()
    }

    fun shutdown() {
        watcher?.cancel()
        host?.stop()
    }

    /** Remembers a room this phone has joined, newest first. */
    fun remember(code: String) {
        val c = RoomCode.normalize(code)
        stored = stored.copy(recent = (listOf(c) + stored.recent.filter { it != c }).take(8))
        _recent.value = stored.recent
        save()
    }

    fun forget(code: String) {
        stored = stored.copy(recent = stored.recent.filter { it != RoomCode.normalize(code) })
        _recent.value = stored.recent
        save()
    }

    private fun save() = save(stored)

    private fun save(snapshot: StoredRooms) {
        scope.launch(Dispatchers.IO) {
            runCatching { file.writeText(ProtoJson.encodeToString(StoredRooms.serializer(), snapshot)) }
        }
    }

    /** Joins a room as a viewer. Suspends while the key is derived. */
    suspend fun join(code: String, slot: Int = 0): RoomViewer = withContext(Dispatchers.Default) {
        val c = RoomCode.normalize(code)
        val key = RoomCode.groupKey(c)
        val id = RoomCode.slotId(roomIdOf(c), slot)
        RoomViewer(c, id, key, DialerFactory { RoomDialer(net, id) }, deviceId, scope, slot)
    }

    private val roomIds = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun roomIdOf(code: String): String = roomIds.getOrPut(code) { RoomCode.roomId(code) }

    /** Which host slots of a room have somebody in them right now (a quick look: no video, no connection set up). */
    suspend fun scan(code: String): Set<Int> = withContext(Dispatchers.Default) {
        app.heat.rooms.scanRoom(net, roomIdOf(RoomCode.normalize(code)))
    }

    fun interface DialerFactory { fun create(): RoomDialer }

    companion object {
        /** Where invite links point: the Heat page, which opens the same room in a browser. */
        const val SITE = "https://heat-wheat.vercel.app"
        fun inviteLink(code: String) = RoomCode.inviteLink(code, SITE)
        const val TAG = "HeatRoom"
    }
}

/** One joined room: the client, its decoder, and the pictures that came with alerts. */
class RoomViewer internal constructor(
    val code: String,
    roomId: String,
    key: app.heat.core.link.GroupKey,
    dialers: RoomManager.DialerFactory,
    self: DeviceId,
    private val scope: CoroutineScope,
    /** Which host slot of the room this viewer is looking at. */
    val slot: Int = 0
) {
    // The camera in a room is whoever holds the code; its real id is learned from the handshake. This one only names the
    // conversation locally.
    val client = CameraClient(
        DeviceId.fromBytes(Crypto.sha256(roomId.toByteArray())), "Phone", self, key, scope, Clock.SYSTEM,
        appVersion = "app-1", pin = false
    )

    val decoder = PreviewDecoder { client.needKeyframe() }

    /** The hub forwards this host's encoded video to the watch from here. */
    @Volatile var tap: VideoSink? = null

    /** The raw JPEG that comes with an alert, for the hub to forward. */
    @Volatile var rawThumb: ((ByteArray) -> Unit)? = null

    /** Decode only while this phone's own screen is showing the room: the hub alone has no use for the pixels. */
    @Volatile var decoding = false

    private val _thumbs = MutableStateFlow<List<Bitmap>>(emptyList())

    /** The newest alert pictures, newest first. */
    val thumbnails: StateFlow<List<Bitmap>> = _thumbs.asStateFlow()

    init {
        client.videoSink = VideoSink { flags, pts, data, offset, length ->
            tap?.onFrame(flags, pts, data, offset, length)
            if (decoding) decoder.submit(flags and MediaFlags.KEYFRAME != 0, pts, data, offset, length)
        }
        client.thumbnailSink = { jpeg ->
            rawThumb?.invoke(jpeg)
            runCatching { android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) }.getOrNull()?.let { b ->
                _thumbs.value = (listOf(b) + _thumbs.value).take(12)
            }
        }
        client.link.setDialer(CarrierKind.WEBRTC, dialers.create())
    }

    fun start(video: Boolean = true) {
        client.start()
        client.watch(video = video)
    }

    /** Leaves the room and frees the decoder. */
    fun stop() {
        runCatching { client.watch(video = false) }
        client.stop()
        decoder.release()
        Log.i(RoomManager.TAG, "left room")
    }
}
