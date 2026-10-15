package app.heat.rooms

import android.content.Context
import app.heat.core.link.ByteDuplex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** STUN finds a direct path; TURN relays when none exists (mobile data, carrier NAT, strict routers). Same list as the page. */
object Ice {
    class Server(val urls: List<String>, val username: String? = null, val credential: String? = null)

    val DEFAULT: List<Server> = listOf(
        Server(listOf("stun:stun.l.google.com:19302", "stun:global.stun.twilio.com:3478")),
        Server(listOf("turn:eu-0.turn.peerjs.com:3478", "turn:us-0.turn.peerjs.com:3478"), "peerjs", "peerjsp"),
        Server(
            listOf("turn:openrelay.metered.ca:80", "turn:openrelay.metered.ca:443?transport=tcp", "turns:openrelay.metered.ca:443?transport=tcp"),
            "openrelayproject", "openrelayproject"
        )
    )

    /**
     * The site may hand out fresh TURN credentials (api/ice); when it does they go after the STUN entry, as on the
     * page. Anything wrong with it, and the defaults stand.
     */
    suspend fun load(http: OkHttpClient, endpoint: String?): List<Server> {
        if (endpoint == null) return DEFAULT
        return runCatching {
            val client = http.newBuilder().callTimeout(java.time.Duration.ofMillis(2_500)).build()
            client.newCall(Request.Builder().url(endpoint).build()).execute().use { r ->
                if (r.code != 200) return@use DEFAULT
                val o = kotlinx.serialization.json.Json.parseToJsonElement(r.body.string()) as kotlinx.serialization.json.JsonObject
                val extra = (o["iceServers"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { e ->
                    val s = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                    val urls = when (val u = s["urls"]) {
                        is kotlinx.serialization.json.JsonArray -> u.map { (it as kotlinx.serialization.json.JsonPrimitive).content }
                        is kotlinx.serialization.json.JsonPrimitive -> listOf(u.content)
                        else -> return@mapNotNull null
                    }
                    Server(urls, (s["username"] as? kotlinx.serialization.json.JsonPrimitive)?.content, (s["credential"] as? kotlinx.serialization.json.JsonPrimitive)?.content)
                }
                if (extra.isEmpty()) DEFAULT else listOf(DEFAULT.first()) + extra
            }
        }.getOrDefault(DEFAULT)
    }

    internal fun toRtc(list: List<Server>): List<PeerConnection.IceServer> = list.map { s ->
        PeerConnection.IceServer.builder(s.urls).apply {
            if (s.username != null) setUsername(s.username)
            if (s.credential != null) setPassword(s.credential)
        }.createIceServer()
    }
}

/** libwebrtc is initialised once, lazily: loading its native library is the expensive part. */
internal object RtcFactory {
    @Volatile private var factory: PeerConnectionFactory? = null

    fun get(context: Context): PeerConnectionFactory = factory ?: synchronized(this) {
        factory ?: run {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()
            )
            PeerConnectionFactory.builder().createPeerConnectionFactory().also { factory = it }
        }
    }
}

/**
 * A WebRTC data channel as the byte stream the session protocol expects. Records are written in 16 KB messages
 * (every browser takes those) and a writer waits while the channel's own buffer is full, so a slow link slows the
 * writer down instead of queueing frames without limit.
 */
internal class DataChannelDuplex(private val channel: DataChannel, private val onClosed: () -> Unit) : ByteDuplex {
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    private val drained = Channel<Unit>(Channel.CONFLATED)
    private var current: ByteArray? = null
    private var position = 0
    @Volatile private var closed = false

    /** Completes when the channel is open, or fails if it closes first. A channel has one observer, so this lives here. */
    val opened = CompletableDeferred<Unit>()

    init {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) { drained.trySend(Unit) }

            override fun onStateChange() {
                when (channel.state()) {
                    DataChannel.State.OPEN -> opened.complete(Unit)
                    DataChannel.State.CLOSING, DataChannel.State.CLOSED -> close()
                    else -> Unit
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val b = ByteArray(buffer.data.remaining())
                buffer.data.get(b)
                if (buffer.binary) inbox.trySend(b)
            }
        })
        if (channel.state() == DataChannel.State.OPEN) opened.complete(Unit)
    }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        var c = current
        if (c == null || position >= c.size) {
            c = inbox.receiveCatching().getOrNull() ?: return -1
            current = c
            position = 0
        }
        val n = minOf(length, c.size - position)
        System.arraycopy(c, position, buffer, offset, n)
        position += n
        return n
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
        var done = 0
        while (done < length) {
            if (closed) throw IOException("data channel closed")
            while (channel.bufferedAmount() > HIGH_WATER) {
                withTimeoutOrNull(250) { drained.receive() }
                if (closed) throw IOException("data channel closed")
            }
            val n = minOf(CHUNK, length - done)
            val ok = channel.send(DataChannel.Buffer(ByteBuffer.wrap(buffer.copyOfRange(offset + done, offset + done + n)), true))
            if (!ok) throw IOException("data channel refused a write")
            done += n
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        opened.completeExceptionally(IOException("channel closed before it opened"))
        inbox.close()
        runCatching { channel.close() }
        runCatching { onClosed() }
    }

    private companion object {
        const val CHUNK = 16 * 1024
        const val HIGH_WATER = 1L shl 20
    }
}

// ── SDP, as suspending calls ─────────────────────────────────────────

private abstract class SdpResult : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String?) = Unit
    override fun onSetFailure(error: String?) = Unit
}

internal suspend fun PeerConnection.makeOffer(): SessionDescription = suspendCancellableCoroutine { c ->
    createOffer(object : SdpResult() {
        override fun onCreateSuccess(sdp: SessionDescription) = c.resume(sdp)
        override fun onCreateFailure(error: String?) = c.resumeWithException(IOException("offer failed: $error"))
    }, MediaConstraints())
}

internal suspend fun PeerConnection.makeAnswer(): SessionDescription = suspendCancellableCoroutine { c ->
    createAnswer(object : SdpResult() {
        override fun onCreateSuccess(sdp: SessionDescription) = c.resume(sdp)
        override fun onCreateFailure(error: String?) = c.resumeWithException(IOException("answer failed: $error"))
    }, MediaConstraints())
}

internal suspend fun PeerConnection.setLocal(sdp: SessionDescription): Unit = suspendCancellableCoroutine { c ->
    setLocalDescription(object : SdpResult() {
        override fun onSetSuccess() = c.resume(Unit)
        override fun onSetFailure(error: String?) = c.resumeWithException(IOException("setLocalDescription failed: $error"))
    }, sdp)
}

internal suspend fun PeerConnection.setRemote(sdp: SessionDescription): Unit = suspendCancellableCoroutine { c ->
    setRemoteDescription(object : SdpResult() {
        override fun onSetSuccess() = c.resume(Unit)
        override fun onSetFailure(error: String?) = c.resumeWithException(IOException("setRemoteDescription failed: $error"))
    }, sdp)
}

/** The observer both sides use: it forwards local candidates, reports the channel, and ignores everything else. */
internal abstract class DataOnlyObserver : PeerConnection.Observer {
    override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
    override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
    override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
    override fun onRenegotiationNeeded() = Unit
    override fun onAddTrack(receiver: RtpReceiver, streams: Array<out org.webrtc.MediaStream>) = Unit
    override fun onDataChannel(channel: DataChannel) = Unit
}

internal fun rtcConfig(servers: List<Ice.Server>) = PeerConnection.RTCConfiguration(Ice.toRtc(servers)).apply {
    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
}
