package app.heat.rooms

import android.content.Context
import android.util.Log
import app.heat.core.link.Acceptor
import app.heat.core.link.ByteDuplex
import app.heat.core.link.CarrierKind
import app.heat.core.link.Dialer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * What both ends of a room need: an HTTP client for the signaling socket, the ICE servers, a scope to run in.
 * One per process; cheap until the first room opens (libwebrtc is not touched before then).
 */
class RoomNet(
    internal val context: Context,
    internal val scope: CoroutineScope,
    internal val server: PeerServer = PeerServer(),
    /** Where fresh TURN credentials come from, if the site offers them (see api/ice.js). */
    private val iceEndpoint: String? = DEFAULT_ICE_ENDPOINT
) {
    internal val http: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(6, TimeUnit.SECONDS)
        .build()

    @Volatile private var ice: List<Ice.Server>? = null
    @Volatile private var iceAt = 0L

    internal suspend fun iceServers(): List<Ice.Server> {
        val now = System.currentTimeMillis()
        ice?.let { if (now - iceAt < ICE_TTL_MS) return it }
        return Ice.load(http, iceEndpoint).also { ice = it; iceAt = now }
    }

    companion object {
        const val DEFAULT_ICE_ENDPOINT = "https://heat-wheat.vercel.app/api/ice"
        private const val ICE_TTL_MS = 30 * 60_000L
    }
}

private const val TAG = "HeatRooms"

/**
 * The viewer's way to a camera in a room: register under a throwaway id, offer a data channel to the room id,
 * trade candidates, and hand back the open channel as a byte stream. The session layer above runs the usual
 * handshake over it, so the room code is also the key.
 */
class RoomDialer(private val net: RoomNet, private val roomId: String) : Dialer {
    override val kind = CarrierKind.WEBRTC

    override suspend fun dial(): ByteDuplex = withTimeout(DIAL_MS) {
        val servers = net.iceServers()
        val signal = PeerSignal(randomId(), net.scope, net.http, net.server)
        signal.open()
        var duplex: DataChannelDuplex? = null
        var pc: PeerConnection? = null
        try {
            val factory = RtcFactory.get(net.context)
            val connectionId = "dc_" + randomId(10)
            val outgoing = ArrayList<IceCandidate>()
            var offerSent = false
            val sendCandidate = { c: IceCandidate ->
                signal.send("CANDIDATE", roomId, PeerJsCodec.candidate(c.sdp, c.sdpMid, c.sdpMLineIndex, connectionId))
            }
            val connection = factory.createPeerConnection(rtcConfig(servers), object : DataOnlyObserver() {
                override fun onIceCandidate(candidate: IceCandidate) {
                    synchronized(outgoing) { if (!offerSent) { outgoing += candidate; return } }
                    sendCandidate(candidate)
                }
            }) ?: throw IOException("could not create a peer connection")
            pc = connection
            val channel = connection.createDataChannel(connectionId, DataChannel.Init().apply { ordered = true })
                ?: throw IOException("could not create a data channel")
            val d = DataChannelDuplex(channel) { runCatching { connection.close(); connection.dispose() } }
            duplex = d

            val offer = connection.makeOffer()
            connection.setLocal(offer)
            signal.send("OFFER", roomId, PeerJsCodec.offer(offer.description, connectionId))
            synchronized(outgoing) { offerSent = true; outgoing.forEach(sendCandidate); outgoing.clear() }

            val failed = CompletableDeferred<Unit>()
            coroutineScope {
                val loop = launch {
                    var remoteSet = false
                    val waiting = ArrayList<PeerJsCodec.Candidate>()
                    fun add(c: PeerJsCodec.Candidate) { connection.addIceCandidate(IceCandidate(c.sdpMid, c.sdpMLineIndex, c.candidate)) }
                    for (m in signal.incoming) {
                        when (m.type) {
                            "ANSWER" -> if (m.connectionId == connectionId) {
                                val sdp = PeerJsCodec.sdpOf(m.payload) ?: continue
                                connection.setRemote(SessionDescription(SessionDescription.Type.ANSWER, sdp))
                                remoteSet = true
                                waiting.forEach(::add); waiting.clear()
                            }
                            "CANDIDATE" -> if (m.connectionId == connectionId) {
                                val c = PeerJsCodec.candidateOf(m.payload) ?: continue
                                if (remoteSet) add(c) else waiting += c
                            }
                            // The server could not deliver our offer: nobody is registered under that room id.
                            "EXPIRE" -> { failed.completeExceptionally(IOException("the camera is not in the room")); return@launch }
                        }
                    }
                    failed.completeExceptionally(IOException("signaling closed before the channel opened"))
                }
                val opened = launch {
                    try { d.opened.await(); failed.complete(Unit) } catch (e: Throwable) { failed.completeExceptionally(e) }
                }
                try {
                    failed.await()
                } finally {
                    loop.cancel(); opened.cancel()
                }
            }
            Log.i(TAG, "room channel open to $roomId")
            signal.close()
            d
        } catch (t: Throwable) {
            signal.close()
            duplex?.close() ?: pc?.let { runCatching { it.close(); it.dispose() } }
            throw if (t is IOException) t else IOException(t.message ?: t.javaClass.simpleName, t)
        }
    }

    private companion object {
        const val DIAL_MS = 22_000L
    }
}

/**
 * The camera's end of a room: claim the room id on the signaling server, answer whoever offers a channel, and give each
 * opened channel to the [acceptor], exactly as a Wi-Fi or Bluetooth connection would be. Reconnects by itself, forever.
 */
class RoomHost(
    private val net: RoomNet,
    private val roomId: String,
    private val acceptor: Acceptor,
    private val maxPeers: Int = 8
) {
    enum class Phase { CONNECTING, READY, TAKEN, OFFLINE }

    data class State(val phase: Phase = Phase.CONNECTING)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private val active = ConcurrentHashMap<String, Job>()

    fun start() {
        if (job != null) return
        job = net.scope.launch { run() }
    }

    fun stop() {
        job?.cancel()
        job = null
        active.values.forEach { it.cancel() }
        active.clear()
    }

    private suspend fun run() {
        var failures = 0
        var taken = 0
        while (net.scope.isActive) {
            val signal = PeerSignal(roomId, net.scope, net.http, net.server)
            try {
                _state.value = State(Phase.CONNECTING)
                signal.open()
                failures = 0; taken = 0
                _state.value = State(Phase.READY)
                Log.i(TAG, "room $roomId is open")
                coroutineScope {
                    for (m in signal.incoming) handle(this, signal, m)
                }
            } catch (e: SignalException) {
                if (e.kind == SignalException.Kind.ID_TAKEN) {
                    // Our own earlier registration lingers for a few seconds after a restart; give it time to expire.
                    if (++taken > 8) _state.value = State(Phase.TAKEN)
                } else {
                    _state.value = State(Phase.OFFLINE)
                    failures++
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                signal.close()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "room loop: ${e.message}")
                failures++
            } finally {
                signal.close()
            }
            if (_state.value.phase != Phase.TAKEN) _state.value = State(if (failures > 0) Phase.OFFLINE else Phase.CONNECTING)
            delay(minOf(1_000L * (1 shl minOf(failures, 4)), 15_000L))
        }
    }

    private val earlyCandidates = ConcurrentHashMap<String, MutableList<PeerJsCodec.Candidate>>()
    private val sinks = ConcurrentHashMap<String, (PeerJsCodec.Candidate) -> Unit>()

    private fun handle(scope: CoroutineScope, signal: PeerSignal, m: SignalMessage) {
        val id = m.connectionId ?: return
        when (m.type) {
            "OFFER" -> {
                if (active.size >= maxPeers) return
                val src = m.src ?: return
                val sdp = PeerJsCodec.sdpOf(m.payload) ?: return
                active[id]?.cancel()
                active[id] = scope.launch {
                    try {
                        answer(signal, src, id, sdp)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.i(TAG, "a viewer could not connect: ${e.message}")
                    } finally {
                        sinks.remove(id); earlyCandidates.remove(id)
                    }
                }
            }
            "CANDIDATE" -> {
                val c = PeerJsCodec.candidateOf(m.payload) ?: return
                val sink = sinks[id]
                if (sink != null) sink(c) else earlyCandidates.getOrPut(id) { java.util.Collections.synchronizedList(ArrayList()) } += c
            }
        }
    }

    private suspend fun answer(signal: PeerSignal, src: String, connectionId: String, offerSdp: String) {
        val servers = net.iceServers()
        val factory = RtcFactory.get(net.context)
        val opened = CompletableDeferred<DataChannel>()
        val outgoing = ArrayList<IceCandidate>()
        var answerSent = false
        val sendCandidate = { c: IceCandidate ->
            signal.send("CANDIDATE", src, PeerJsCodec.candidate(c.sdp, c.sdpMid, c.sdpMLineIndex, connectionId))
        }
        val pc = factory.createPeerConnection(rtcConfig(servers), object : DataOnlyObserver() {
            override fun onIceCandidate(candidate: IceCandidate) {
                synchronized(outgoing) { if (!answerSent) { outgoing += candidate; return } }
                sendCandidate(candidate)
            }

            override fun onDataChannel(channel: DataChannel) { opened.complete(channel) }
        }) ?: throw IOException("could not create a peer connection")
        var duplex: DataChannelDuplex? = null
        try {
            pc.setRemote(SessionDescription(SessionDescription.Type.OFFER, offerSdp))
            fun add(c: PeerJsCodec.Candidate) { pc.addIceCandidate(IceCandidate(c.sdpMid, c.sdpMLineIndex, c.candidate)) }
            sinks[connectionId] = ::add
            earlyCandidates.remove(connectionId)?.toList()?.forEach(::add)

            val answer = pc.makeAnswer()
            pc.setLocal(answer)
            signal.send("ANSWER", src, PeerJsCodec.answer(answer.description, connectionId))
            synchronized(outgoing) { answerSent = true; outgoing.forEach(sendCandidate); outgoing.clear() }

            val ch = withTimeout(CONNECT_MS) { opened.await() }
            val d = DataChannelDuplex(ch) { runCatching { pc.close(); pc.dispose() } }
            duplex = d
            withTimeout(CONNECT_MS) { d.opened.await() }
            Log.i(TAG, "a viewer connected to room $roomId")
            acceptor.accept(CarrierKind.WEBRTC, d)
        } catch (t: Throwable) {
            duplex?.close() ?: runCatching { pc.close(); pc.dispose() }
            throw t
        }
    }

    private companion object {
        const val CONNECT_MS = 20_000L
    }
}
