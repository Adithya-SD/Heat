package app.heat.core.link

import app.heat.core.crypto.AeadStream
import app.heat.core.crypto.AuthenticationException
import app.heat.core.crypto.Crypto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Why a handshake failed. The UI and the retry policy treat these very differently. */
enum class HandshakeFailure {
    /** The peer does not hold our group key (or we do not hold theirs). Retrying will not help; re-pair. */
    AUTH,

    /** The peer spoke something that is not this protocol, or a version we cannot talk. */
    PROTOCOL,

    /** The carrier broke mid-handshake. Retry with backoff. */
    IO,

    /** The peer accepted the connection and then said nothing. */
    TIMEOUT
}

class HandshakeException(val failure: HandshakeFailure, message: String, cause: Throwable? = null) :
    IOException(message, cause)

/** The outcome of a successful handshake: who is on the other end, and the two record streams. */
class HandshakeResult internal constructor(
    val peer: DeviceId,
    internal val tx: AeadStream,
    internal val rx: AeadStream,
    /**
     * The server's READY has not been read yet: the dialer sent its proof and went straight on. The session must
     * treat the first record it reads as READY, and a failure before it as the server refusing us.
     */
    internal val awaitingReady: Boolean = false
)

/**
 * Mutual authentication with a pre-shared [GroupKey], no secrets in the clear.
 *
 * ```
 * client                                   server
 *   HELLO  ver, groupTag, clientId, nC  ─►
 *                                      ◄─  CHALLENGE  ver, serverId, nS, HMAC(auth, "server" | HELLO | serverId | nS)
 *   FINISH HMAC(auth, "client" | HELLO | serverId | nS | serverProof)  ─►
 *                                      ◄─  READY (first AES-GCM record)
 * ```
 *
 * The dialer does not wait for READY. Once its proof is sent it already has both session keys and can send its
 * first records in the same breath, so a connection costs two flights instead of three before data moves; over
 * a Bluetooth link that is worth hundreds of milliseconds. READY is still checked, as the first record the
 * session reads: if the server was not satisfied it says so and hangs up, and that is reported exactly as
 * before (the key does not match), just a moment later.
 *
 * - Both sides prove knowledge of the key before either accepts the other, and
 *   each proof covers the peer's fresh nonce, so a recorded session cannot be
 *   replayed.
 * - Session keys are HKDF(groupKey, salt = nC | nS): every session gets new
 *   keys, and the two directions use different ones.
 * - Pre-authentication input is capped at 256 bytes and the whole exchange at
 *   [TIMEOUT_MS], so an unauthenticated socket cannot make us allocate or wait.
 * - The wrong group gets a bare REJECT and nothing else: no oracle for the key.
 */
object Handshake {
    const val VERSION = 1
    const val TIMEOUT_MS = 4_000L

    /** A stranger that has not said HELLO by now is holding a slot for nothing. */
    const val FIRST_FRAME_MS = 1_500L

    private const val T_HELLO = 1
    private const val T_CHALLENGE = 2
    private const val T_FINISH = 3
    private const val T_REJECT = 4

    private const val MAX_PLAIN_BODY = 256
    private const val NONCE = 16
    private const val MAC = 32

    private val SERVER_LABEL = "heat/1 server".toByteArray()
    private val CLIENT_LABEL = "heat/1 client".toByteArray()
    private val KEYS_LABEL = "heat/1 keys".toByteArray()

    /** Dial side. [expect] pins the peer's identity when it is known (a remembered camera). */
    suspend fun client(
        io: ByteDuplex,
        self: DeviceId,
        group: GroupKey,
        expect: DeviceId? = null,
        timeoutMs: Long = TIMEOUT_MS,
        /** Start sending before the server's READY arrives (see above). Off only for tests that want the old, strict shape. */
        optimistic: Boolean = true
    ): HandshakeResult = guarded(io, timeoutMs) {
        val clientNonce = Crypto.random(NONCE)
        val hello = ByteArray(1 + GroupKey.TAG_SIZE + DeviceId.SIZE + NONCE)
        hello[0] = VERSION.toByte()
        System.arraycopy(group.tag, 0, hello, 1, GroupKey.TAG_SIZE)
        self.copyInto(hello, 1 + GroupKey.TAG_SIZE)
        System.arraycopy(clientNonce, 0, hello, 1 + GroupKey.TAG_SIZE + DeviceId.SIZE, NONCE)
        writeFrame(io, T_HELLO, hello)

        val (type, body) = readFrame(io)
        if (type == T_REJECT) throw HandshakeException(HandshakeFailure.AUTH, "peer is not in our group")
        if (type != T_CHALLENGE || body.size != 1 + DeviceId.SIZE + NONCE + MAC) {
            throw HandshakeException(HandshakeFailure.PROTOCOL, "unexpected reply (type $type, ${body.size} bytes)")
        }
        if (body[0].toInt() != VERSION) {
            throw HandshakeException(HandshakeFailure.PROTOCOL, "peer speaks protocol ${body[0]}")
        }
        val serverId = DeviceId.fromBytes(body, 1)
        val serverNonce = body.copyOfRange(1 + DeviceId.SIZE, 1 + DeviceId.SIZE + NONCE)
        val serverProof = body.copyOfRange(1 + DeviceId.SIZE + NONCE, body.size)

        val expectedServer = Crypto.hmac(group.authKey, SERVER_LABEL, hello, serverId.bytes, serverNonce)
        if (!Crypto.equal(expectedServer, serverProof)) {
            throw HandshakeException(HandshakeFailure.AUTH, "server failed to prove the group key")
        }
        if (expect != null && expect != serverId) {
            throw HandshakeException(HandshakeFailure.AUTH, "reached $serverId, expected $expect")
        }

        val clientProof = Crypto.hmac(group.authKey, CLIENT_LABEL, hello, serverId.bytes, serverNonce, serverProof)
        writeFrame(io, T_FINISH, clientProof)

        val keys = deriveKeys(group, clientNonce, serverNonce, hello)
        val tx = AeadStream(keys.c2s, keys.c2sPrefix)
        val rx = AeadStream(keys.s2c, keys.s2cPrefix)
        if (optimistic) return@guarded HandshakeResult(serverId, tx, rx, awaitingReady = true)

        // The server's first record proves it derived the same keys. Waiting for
        // it here is what turns "the server hung up on us" into a precise
        // AUTH failure instead of a vague disconnect.
        val ready = RecordReader(io, rx)
        val n = try {
            ready.read()
        } catch (e: AuthenticationException) {
            throw HandshakeException(HandshakeFailure.AUTH, "server keys do not match", e)
        } catch (e: IOException) {
            throw HandshakeException(HandshakeFailure.AUTH, "server closed after FINISH (rejected our proof)", e)
        }
        if (n != 1 || ready.plain[0].toInt() != RecordType.READY) {
            throw HandshakeException(HandshakeFailure.PROTOCOL, "expected READY")
        }
        HandshakeResult(serverId, tx, rx)
    }

    /** Listen side. */
    suspend fun server(
        io: ByteDuplex,
        self: DeviceId,
        group: GroupKey,
        timeoutMs: Long = TIMEOUT_MS
    ): HandshakeResult = guarded(io, timeoutMs, firstFrameMs = FIRST_FRAME_MS) { gotFirstFrame ->
        val (type, hello) = readFrame(io)
        gotFirstFrame.set(true)
        if (type != T_HELLO || hello.size != 1 + GroupKey.TAG_SIZE + DeviceId.SIZE + NONCE) {
            throw HandshakeException(HandshakeFailure.PROTOCOL, "bad HELLO")
        }
        if (hello[0].toInt() != VERSION) {
            reject(io)
            throw HandshakeException(HandshakeFailure.PROTOCOL, "client speaks protocol ${hello[0]}")
        }
        val tag = hello.copyOfRange(1, 1 + GroupKey.TAG_SIZE)
        if (!Crypto.equal(tag, group.tag)) {
            reject(io)
            throw HandshakeException(HandshakeFailure.AUTH, "client is not in our group")
        }
        val clientId = DeviceId.fromBytes(hello, 1 + GroupKey.TAG_SIZE)
        val clientNonce = hello.copyOfRange(1 + GroupKey.TAG_SIZE + DeviceId.SIZE, hello.size)

        val serverNonce = Crypto.random(NONCE)
        val serverProof = Crypto.hmac(group.authKey, SERVER_LABEL, hello, self.bytes, serverNonce)
        val challenge = ByteArray(1 + DeviceId.SIZE + NONCE + MAC)
        challenge[0] = VERSION.toByte()
        self.copyInto(challenge, 1)
        System.arraycopy(serverNonce, 0, challenge, 1 + DeviceId.SIZE, NONCE)
        System.arraycopy(serverProof, 0, challenge, 1 + DeviceId.SIZE + NONCE, MAC)
        writeFrame(io, T_CHALLENGE, challenge)

        val (finishType, finish) = readFrame(io)
        if (finishType != T_FINISH || finish.size != MAC) {
            throw HandshakeException(HandshakeFailure.PROTOCOL, "bad FINISH")
        }
        val expectedClient = Crypto.hmac(group.authKey, CLIENT_LABEL, hello, self.bytes, serverNonce, serverProof)
        if (!Crypto.equal(expectedClient, finish)) {
            reject(io)
            throw HandshakeException(HandshakeFailure.AUTH, "client failed to prove the group key")
        }

        val keys = deriveKeys(group, clientNonce, serverNonce, hello)
        val tx = AeadStream(keys.s2c, keys.s2cPrefix)
        val rx = AeadStream(keys.c2s, keys.c2sPrefix)
        RecordWriter(io, tx).write(RecordType.READY)
        HandshakeResult(clientId, tx, rx)
    }

    // ── internals ────────────────────────────────────────────────────

    private class Keys(val c2s: ByteArray, val s2c: ByteArray, val c2sPrefix: ByteArray, val s2cPrefix: ByteArray)

    private fun deriveKeys(group: GroupKey, clientNonce: ByteArray, serverNonce: ByteArray, hello: ByteArray): Keys {
        val prk = group.extract(clientNonce + serverNonce)
        val okm = Crypto.hkdfExpand(prk, KEYS_LABEL + Crypto.sha256(hello), 72)
        return Keys(
            c2s = okm.copyOfRange(0, 32),
            s2c = okm.copyOfRange(32, 64),
            c2sPrefix = okm.copyOfRange(64, 68),
            s2cPrefix = okm.copyOfRange(68, 72)
        )
    }

    /**
     * Runs [block] under a hard deadline. The deadline works by closing [io]
     * from a sibling coroutine: that is the only way to interrupt a read that
     * is blocked inside a socket, and it is why every [ByteDuplex.close] must
     * unblock pending IO.
     */
    private suspend fun <T> guarded(
        io: ByteDuplex,
        timeoutMs: Long,
        firstFrameMs: Long = 0,
        block: suspend (gotFirstFrame: AtomicBoolean) -> T
    ): T = coroutineScope {
        val timedOut = AtomicBoolean(false)
        val gotFirstFrame = AtomicBoolean(firstFrameMs <= 0)
        val watchdog = launch {
            if (firstFrameMs in 1 until timeoutMs) {
                delay(firstFrameMs)
                if (!gotFirstFrame.get()) {
                    timedOut.set(true)
                    io.close()
                    return@launch
                }
                delay(timeoutMs - firstFrameMs)
            } else {
                delay(timeoutMs)
            }
            timedOut.set(true)
            io.close()
        }
        try {
            block(gotFirstFrame)
        } catch (e: HandshakeException) {
            if (timedOut.get()) throw HandshakeException(HandshakeFailure.TIMEOUT, "handshake timed out", e)
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthenticationException) {
            throw HandshakeException(HandshakeFailure.AUTH, "authentication failed", e)
        } catch (e: IOException) {
            if (timedOut.get()) throw HandshakeException(HandshakeFailure.TIMEOUT, "handshake timed out", e)
            throw HandshakeException(HandshakeFailure.IO, e.message ?: "I/O error", e)
        } finally {
            watchdog.cancel()
        }
    }

    private suspend fun writeFrame(io: ByteDuplex, type: Int, body: ByteArray) {
        val frame = ByteArray(3 + body.size)
        frame[0] = type.toByte()
        frame[1] = (body.size ushr 8).toByte()
        frame[2] = body.size.toByte()
        System.arraycopy(body, 0, frame, 3, body.size)
        io.write(frame, 0, frame.size)
    }

    private suspend fun readFrame(io: ByteDuplex): Pair<Int, ByteArray> {
        val head = ByteArray(3)
        io.readFully(head, 0, 3)
        val len = ((head[1].toInt() and 0xFF) shl 8) or (head[2].toInt() and 0xFF)
        if (len > MAX_PLAIN_BODY) throw HandshakeException(HandshakeFailure.PROTOCOL, "oversized handshake frame")
        val body = ByteArray(len)
        if (len > 0) io.readFully(body, 0, len)
        return (head[0].toInt() and 0xFF) to body
    }

    private suspend fun reject(io: ByteDuplex) {
        runCatching { writeFrame(io, T_REJECT, ByteArray(0)) }
    }
}
