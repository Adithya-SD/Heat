package app.heat.rooms

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.security.SecureRandom

/**
 * Where PeerJS signaling lives. The defaults are the public server the Heat page uses, so a phone and a browser
 * meet on the same one. Signaling only carries the offer, the answer and network candidates for a moment; once the
 * data channel is up, nothing else goes through it, and the room id it sees is a hash of the room code.
 */
data class PeerServer(
    val host: String = "0.peerjs.com",
    val port: Int = 443,
    val path: String = "/",
    val secure: Boolean = true,
    val key: String = "peerjs"
) {
    fun url(id: String, token: String): String {
        val p = if (path.endsWith("/")) path else "$path/"
        return "${if (secure) "wss" else "ws"}://$host:$port${p}peerjs?key=$key&id=$id&token=$token"
    }
}

/** One message of the PeerJS signaling protocol. */
class SignalMessage(val type: String, val src: String?, val dst: String?, val payload: JsonObject?) {
    /** The negotiation this message belongs to (PeerJS calls it a connection id). */
    val connectionId: String? get() = payload?.get("connectionId")?.jsonPrimitive?.contentOrNull
}

/** Builds and reads the PeerJS wire format. Pure, so it is unit-tested. */
object PeerJsCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(type: String, dst: String? = null, payload: JsonObject? = null): String = buildJsonObject {
        put("type", type)
        if (dst != null) put("dst", dst)
        if (payload != null) put("payload", payload)
    }.toString()

    fun decode(text: String): SignalMessage? = runCatching {
        val o = json.parseToJsonElement(text).jsonObject
        SignalMessage(
            type = o["type"]!!.jsonPrimitive.content,
            src = o["src"]?.jsonPrimitive?.contentOrNull,
            dst = o["dst"]?.jsonPrimitive?.contentOrNull,
            payload = o["payload"] as? JsonObject
        )
    }.getOrNull()

    private fun description(type: String, sdp: String): JsonObject = buildJsonObject { put("type", type); put("sdp", sdp) }

    /** A data connection offer, shaped as PeerJS's own `peer.connect(id, { serialization: 'raw' })` makes it. */
    fun offer(sdp: String, connectionId: String): JsonObject = buildJsonObject {
        put("sdp", description("offer", sdp))
        put("type", "data")
        put("connectionId", connectionId)
        put("label", connectionId)
        put("reliable", true)
        put("serialization", "raw")
        put("browser", "chrome")
    }

    fun answer(sdp: String, connectionId: String): JsonObject = buildJsonObject {
        put("sdp", description("answer", sdp))
        put("type", "data")
        put("connectionId", connectionId)
        put("browser", "chrome")
    }

    fun candidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int, connectionId: String): JsonObject = buildJsonObject {
        put("candidate", buildJsonObject {
            put("candidate", candidate)
            if (sdpMid != null) put("sdpMid", sdpMid)
            put("sdpMLineIndex", sdpMLineIndex)
        })
        put("type", "data")
        put("connectionId", connectionId)
    }

    /** The SDP text out of an offer or answer payload, or null. */
    fun sdpOf(payload: JsonObject?): String? = (payload?.get("sdp") as? JsonObject)?.get("sdp")?.jsonPrimitive?.contentOrNull

    class Candidate(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int)

    fun candidateOf(payload: JsonObject?): Candidate? = runCatching {
        val c = payload!!["candidate"] as JsonObject
        Candidate(
            c["candidate"]!!.jsonPrimitive.content,
            c["sdpMid"]?.jsonPrimitive?.contentOrNull,
            c["sdpMLineIndex"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        )
    }.getOrNull()
}

/** The signaling server refused or could not be reached. */
class SignalException(val kind: Kind, message: String) : IOException(message) {
    enum class Kind { UNREACHABLE, ID_TAKEN, REFUSED }
}

/**
 * One registration with the PeerJS server, under [id]. Messages for that id arrive on [incoming]; the channel
 * closes when the socket does. The server forgets an id that stops sending heartbeats, so they are sent here.
 */
class PeerSignal(
    val id: String,
    private val scope: CoroutineScope,
    private val http: OkHttpClient,
    private val server: PeerServer = PeerServer()
) {
    val incoming = Channel<SignalMessage>(Channel.UNLIMITED)
    private val opened = CompletableDeferred<Unit>()
    @Volatile private var socket: WebSocket? = null
    private var heartbeat: Job? = null

    /** Connects and waits for the server to accept the id. Throws [SignalException]. */
    suspend fun open(timeoutMs: Long = 8_000) {
        val request = Request.Builder().url(server.url(id, token())).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = PeerJsCodec.decode(text) ?: return
                when (m.type) {
                    "OPEN" -> opened.complete(Unit)
                    "ID-TAKEN" -> opened.completeExceptionally(SignalException(SignalException.Kind.ID_TAKEN, "id taken"))
                    "INVALID-KEY", "ERROR" -> opened.completeExceptionally(SignalException(SignalException.Kind.REFUSED, m.type))
                    "HEARTBEAT" -> Unit
                    else -> incoming.trySend(m)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finished(null)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = finished(t)
        })
        try {
            withTimeout(timeoutMs) { opened.await() }
        } catch (e: SignalException) {
            close()
            throw e
        } catch (e: Exception) {
            close()
            throw SignalException(SignalException.Kind.UNREACHABLE, "can't reach the signaling server: ${e.message}")
        }
        heartbeat = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                socket?.send(PeerJsCodec.encode("HEARTBEAT"))
            }
        }
    }

    fun send(type: String, dst: String, payload: JsonObject) {
        socket?.send(PeerJsCodec.encode(type, dst, payload))
    }

    fun close() {
        heartbeat?.cancel()
        runCatching { socket?.close(1000, null) }
        socket = null
        incoming.close()
    }

    private fun finished(t: Throwable?) {
        heartbeat?.cancel()
        if (!opened.isCompleted) {
            opened.completeExceptionally(SignalException(SignalException.Kind.UNREACHABLE, t?.message ?: "closed before it opened"))
        }
        incoming.close()
    }

    private companion object {
        const val HEARTBEAT_MS = 5_000L

        fun token(): String = randomId(16)
    }
}

/** Random lowercase-alphanumeric id: what PeerJS itself generates, 16 symbols. */
fun randomId(length: Int = 16): String {
    val a = "abcdefghijklmnopqrstuvwxyz0123456789"
    return buildString { repeat(length) { append(a[RANDOM.nextInt(a.length)]) } }
}

private val RANDOM = SecureRandom()
