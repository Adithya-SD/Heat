package app.heat.core.link

import app.heat.core.crypto.AuthenticationException
import app.heat.core.util.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Why a session ended. Drives reconnect policy and what the UI tells the user. */
enum class CloseReason {
    /** We closed it on purpose. */
    LOCAL,

    /** The peer said goodbye. */
    PEER_BYE,

    /** The carrier ended without a goodbye (peer app killed, link dropped). */
    PEER_GONE,

    /** Nothing heard for [SessionConfig.deadAfterMs], or a write stuck that long. */
    TIMEOUT,

    /** A record failed authentication: tampering or desync. */
    CORRUPT,

    /** The peer broke the protocol. */
    PROTOCOL,

    /** The carrier threw. */
    IO,

    /** The outbound control queue overflowed: the peer stopped reading entirely. */
    OVERLOAD,

    /** The server refused us right after our proof (wrong key): it never sent READY. Retrying will not help. */
    REJECTED
}

/** Media stream ids. Each has its own delivery policy, see [Session]. */
object Stream {
    /** H.264. Delta frames depend on their predecessors, so loss is handled by gating on keyframes. */
    const val VIDEO = 1

    /** Audio. Independent frames; a short queue, oldest dropped first. */
    const val AUDIO = 2

    /** Latest-wins stills (alert thumbnails and the like). */
    const val THUMB = 3

    internal const val COUNT = 4
}

object MediaFlags {
    const val KEYFRAME = 1
}

class SessionConfig(
    /** How often each side pings. Also the cadence of the liveness check. */
    val pingIntervalMs: Long = 2_000,

    /** No inbound record (or a write stuck) for this long means the peer is gone. */
    val deadAfterMs: Long = 6_500,

    /** Pending control messages before we conclude the peer stopped reading. */
    val controlQueue: Int = 512,

    /** How long [Session.close] waits for BYE to flush before cutting the carrier. */
    val goodbyeMs: Long = 400
)

interface SessionHandler {
    /** Runs on the session's reader coroutine. Keep it short; [data] is only valid during the call. */
    fun onControl(data: ByteArray, offset: Int, length: Int)

    /** Runs on the reader coroutine. [data] is a borrowed buffer valid only during the call. */
    fun onMedia(stream: Int, flags: Int, ptsUs: Long, data: ByteArray, offset: Int, length: Int) = Unit

    /**
     * The send side had to discard video to stay current and is now holding
     * back delta frames until the next keyframe. The encoder should produce
     * one now; the receiver never sees a broken picture, only a brief freeze.
     */
    fun onMediaDropped(stream: Int) = Unit

    /** Called exactly once, when the session ends for any reason. */
    fun onClosed(reason: CloseReason, detail: String?)

    /** A handler callback threw. The session keeps running; a bug in the app layer must not drop the link. */
    fun onHandlerError(error: Throwable) = Unit
}

/** Cheap counters, readable from any thread. */
class SessionStats {
    val bytesTx = AtomicLong()
    val bytesRx = AtomicLong()
    val videoOffered = AtomicLong()
    val videoDropped = AtomicLong()
    val audioDropped = AtomicLong()
}

/**
 * One authenticated, encrypted, prioritised conversation with one peer over
 * one carrier.
 *
 * ## What is guaranteed
 *  - **Control is reliable and ordered** and always goes first. A tiny "disarm"
 *    never waits behind a keyframe.
 *  - **Media is never queued up.** A slow link loses frames instead of
 *    accumulating lag it can never pay back (see [Stream]).
 *  - **No broken pictures.** When video has to be dropped, delta frames are
 *    withheld until the next keyframe and [SessionHandler.onMediaDropped]
 *    fires so the encoder produces one. The receiver freezes for a moment; it
 *    never smears.
 *  - **Dead peers are detected in [SessionConfig.deadAfterMs]**, in either
 *    direction, including a write that never completes.
 *  - **A bug above cannot kill the link**: handler exceptions are contained.
 *  - **Exactly one [SessionHandler.onClosed]**, whatever the cause.
 */
class Session(
    val peer: DeviceId,
    /** True if we dialed (viewer side); false if we accepted (camera side). */
    val dialedByUs: Boolean,
    private val io: ByteDuplex,
    handshake: HandshakeResult,
    private val handler: SessionHandler,
    private val config: SessionConfig = SessionConfig(),
    private val clock: Clock = Clock.SYSTEM
) {
    private class Outgoing(val type: Int, val bytes: ByteArray)

    private class Frame(var data: ByteArray, var length: Int, var flags: Int, var ptsUs: Long)

    private val writer = RecordWriter(io, handshake.tx)
    private val reader = RecordReader(io, handshake.rx)

    /** True until the server's READY has been read (dialer side, optimistic handshake). */
    @Volatile private var awaitingReady = handshake.awaitingReady

    private val closed = AtomicBoolean(false)
    @Volatile private var closing = false
    private val control = Channel<Outgoing>(config.controlQueue)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val videoGate = VideoSlot()
    private val audioQueue = ArrayDeque<Frame>()
    private val thumbSlot = arrayOfNulls<Frame>(1)

    @Volatile private var lastRxMs = clock.nowMs()
    @Volatile private var writeStartedMs = 0L
    private val jobs = ArrayList<Job>(3)

    val stats = SessionStats()

    /** Smoothed round-trip time in ms, or -1 until the first pong. */
    @Volatile
    var rttMs: Int = -1
        private set

    val isOpen: Boolean get() = !closed.get() && !closing

    /** Milliseconds since anything at all was heard from the peer. A healthy link stays under ~2 s. */
    fun silenceMs(): Long = clock.nowMs() - lastRxMs

    // ── lifecycle ────────────────────────────────────────────────────

    fun start(scope: CoroutineScope) {
        check(jobs.isEmpty()) { "already started" }
        val name = CoroutineName("heat-session-${peer.short}")
        jobs += scope.launch(name) { readLoop() }
        jobs += scope.launch(name) { writeLoop() }
        jobs += scope.launch(name) { livenessLoop() }
    }

    /**
     * Say goodbye, give it a moment to flush, then cut the carrier. Safe to
     * call repeatedly. The deadline is enforced by the liveness loop, so a
     * wedged write cannot make this hang.
     */
    fun close() {
        if (closed.get() || closing) return
        closing = true
        if (jobs.isEmpty()) {
            finish(CloseReason.LOCAL, null)
            return
        }
        goodbyeDeadlineMs = clock.nowMs() + config.goodbyeMs
        control.trySend(Outgoing(RecordType.BYE, ByteArray(0)))
        wake.trySend(Unit)
    }

    @Volatile private var goodbyeDeadlineMs = Long.MAX_VALUE

    // ── sending ──────────────────────────────────────────────────────

    /**
     * Queue a control message. Never blocks. Returns false if the session is
     * closed. Overflowing the queue means the peer stopped reading, which ends
     * the session ([CloseReason.OVERLOAD]) rather than growing without bound.
     */
    fun sendControl(data: ByteArray): Boolean {
        if (!isOpen) return false
        if (control.trySend(Outgoing(RecordType.CONTROL, data)).isFailure) {
            finish(CloseReason.OVERLOAD, "control queue full")
            return false
        }
        wake.trySend(Unit)
        return true
    }

    /**
     * Offer a media frame. Never blocks, never queues behind older frames.
     * [data] is copied; the caller may reuse it immediately. Returns whether the
     * frame was accepted for sending.
     */
    fun sendMedia(stream: Int, flags: Int, ptsUs: Long, data: ByteArray, offset: Int, length: Int): Boolean {
        if (!isOpen || length <= 0 || stream !in 0 until Stream.COUNT) return false
        when (stream) {
            Stream.VIDEO -> {
                stats.videoOffered.incrementAndGet()
                when (videoGate.offer(flags, ptsUs, data, offset, length)) {
                    OfferResult.QUEUED -> Unit
                    OfferResult.REPLACED_KEY -> stats.videoDropped.incrementAndGet()
                    OfferResult.GATED -> {
                        stats.videoDropped.incrementAndGet()
                        return false
                    }
                    OfferResult.CHAIN_BROKEN -> {
                        stats.videoDropped.addAndGet(2)
                        notifyHandler { handler.onMediaDropped(Stream.VIDEO) }
                        return false
                    }
                }
            }

            Stream.AUDIO -> synchronized(audioQueue) {
                if (audioQueue.size >= AUDIO_QUEUE) {
                    audioQueue.removeFirst()
                    stats.audioDropped.incrementAndGet()
                }
                audioQueue.addLast(copyFrame(flags, ptsUs, data, offset, length))
            }

            else -> synchronized(thumbSlot) { thumbSlot[0] = copyFrame(flags, ptsUs, data, offset, length) }
        }
        wake.trySend(Unit)
        return true
    }

    /** True while video is being withheld until the next keyframe. */
    val awaitingKeyframe: Boolean get() = videoGate.awaitingKey

    // ── loops ────────────────────────────────────────────────────────

    private suspend fun readLoop() {
        try {
            while (true) {
                val n = reader.read()
                lastRxMs = clock.nowMs()
                stats.bytesRx.addAndGet((n + 4 + 16).toLong())
                val p = reader.plain
                if (awaitingReady) {
                    if (n != 1 || p[0].toInt() != RecordType.READY) throw ProtocolException("expected READY")
                    awaitingReady = false
                    continue
                }
                when (p[0].toInt()) {
                    RecordType.CONTROL -> notifyHandler { handler.onControl(p, 1, n - 1) }

                    RecordType.MEDIA -> {
                        if (n < 1 + MEDIA_HEAD) throw ProtocolException("short media record")
                        val stream = p[1].toInt() and 0xFF
                        val flags = p[2].toInt() and 0xFF
                        val pts = getLong(p, 3)
                        notifyHandler { handler.onMedia(stream, flags, pts, p, 1 + MEDIA_HEAD, n - 1 - MEDIA_HEAD) }
                    }

                    RecordType.PING -> {
                        if (n != 9) throw ProtocolException("bad ping")
                        enqueueRaw(RecordType.PONG, p.copyOfRange(1, 9))
                    }

                    RecordType.PONG -> {
                        if (n != 9) throw ProtocolException("bad pong")
                        val sample = (clock.nowMs() - getLong(p, 1)).toInt().coerceIn(0, 60_000)
                        val old = rttMs
                        rttMs = if (old < 0) sample else (old * 3 + sample) / 4
                    }

                    RecordType.BYE -> {
                        finish(CloseReason.PEER_BYE, null)
                        return
                    }

                    else -> throw ProtocolException("unknown record type ${p[0]}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failFrom(e)
        }
    }

    private suspend fun writeLoop() {
        val head = ByteArray(MEDIA_HEAD)
        try {
            while (true) {
                var wrote = false

                // Control first, always, drained to empty. Then at most one media frame,
                // then back around: control latency is bounded by one frame's write time.
                while (true) {
                    val m = control.tryReceive().getOrNull() ?: break
                    timedWrite { writer.write(m.type, body = m.bytes, bodyLen = m.bytes.size) }
                    stats.bytesTx.addAndGet((m.bytes.size + 1 + 4 + 16).toLong())
                    wrote = true
                    if (m.type == RecordType.BYE) {
                        finish(CloseReason.LOCAL, null)
                        return
                    }
                }

                val frame = takeMedia(head)
                if (frame != null) {
                    timedWrite { writer.write(RecordType.MEDIA, head, MEDIA_HEAD, frame.data, 0, frame.length) }
                    stats.bytesTx.addAndGet((frame.length + 1 + MEDIA_HEAD + 4 + 16).toLong())
                    wrote = true
                }

                if (!wrote) wake.receive()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failFrom(e)
        }
    }

    private suspend fun livenessLoop() {
        var nextPing = clock.nowMs() + config.pingIntervalMs
        try {
            while (true) {
                delay(LIVENESS_TICK_MS)
                val now = clock.nowMs()
                if (now > goodbyeDeadlineMs) {
                    finish(CloseReason.LOCAL, "goodbye timed out")
                    return
                }
                if (now - lastRxMs > config.deadAfterMs) {
                    finish(CloseReason.TIMEOUT, "no data for ${now - lastRxMs} ms")
                    return
                }
                val started = writeStartedMs
                if (started != 0L && now - started > config.deadAfterMs) {
                    finish(CloseReason.TIMEOUT, "write stuck for ${now - started} ms")
                    return
                }
                if (now >= nextPing) {
                    nextPing = now + config.pingIntervalMs
                    val ts = ByteArray(8)
                    putLong(ts, now)
                    enqueueRaw(RecordType.PING, ts)
                }
            }
        } catch (e: CancellationException) {
            throw e
        }
    }

    // ── helpers ──────────────────────────────────────────────────────

    private inline fun timedWrite(block: () -> Unit) {
        writeStartedMs = clock.nowMs().coerceAtLeast(1)
        try {
            block()
        } finally {
            writeStartedMs = 0
        }
    }

    private fun enqueueRaw(type: Int, bytes: ByteArray) {
        if (closed.get()) return
        if (control.trySend(Outgoing(type, bytes)).isFailure) {
            finish(CloseReason.OVERLOAD, "control queue full")
        } else {
            wake.trySend(Unit)
        }
    }

    private fun takeMedia(head: ByteArray): Frame? {
        var stream = Stream.AUDIO
        var frame: Frame? = synchronized(audioQueue) { audioQueue.removeFirstOrNull() }
        if (frame == null) {
            stream = Stream.VIDEO
            frame = videoGate.take()
        }
        if (frame == null) {
            stream = Stream.THUMB
            frame = synchronized(thumbSlot) { thumbSlot[0].also { thumbSlot[0] = null } }
        }
        if (frame == null) return null
        head[0] = stream.toByte()
        head[1] = frame.flags.toByte()
        putLong(head, frame.ptsUs, 2)
        return frame
    }

    private fun failFrom(e: Throwable) {
        when {
            closed.get() || closing -> finish(CloseReason.LOCAL, null)
            // Never got READY: the server did not accept our proof and hung up. That is a wrong key, not a bad link.
            awaitingReady && (e is AuthenticationException || e is ProtocolException || e is EOFException) ->
                finish(CloseReason.REJECTED, "server refused the proof")
            e is AuthenticationException -> finish(CloseReason.CORRUPT, e.message)
            e is ProtocolException -> finish(CloseReason.PROTOCOL, e.message)
            e is EOFException -> finish(CloseReason.PEER_GONE, null)
            e is IOException -> finish(CloseReason.IO, e.message)
            else -> finish(CloseReason.PROTOCOL, "unexpected ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** The single exit. Idempotent: the first caller wins, everyone else is a no-op. */
    private fun finish(reason: CloseReason, detail: String?) {
        if (!closed.compareAndSet(false, true)) return
        closing = true
        io.close()
        control.close()
        jobs.forEach { it.cancel() }
        notifyHandler { handler.onClosed(reason, detail) }
    }

    private inline fun notifyHandler(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            runCatching { handler.onHandlerError(e) }
        }
    }

    private fun copyFrame(flags: Int, ptsUs: Long, src: ByteArray, off: Int, len: Int): Frame {
        val data = ByteArray(len)
        System.arraycopy(src, off, data, 0, len)
        return Frame(data, len, flags, ptsUs)
    }

    // ── video gating ─────────────────────────────────────────────────

    private enum class OfferResult { QUEUED, REPLACED_KEY, GATED, CHAIN_BROKEN }

    /**
     * One-slot, newest-wins video queue that can never produce a corrupt GOP.
     *
     * Delta frames reference their predecessors, so silently overwriting an
     * unsent frame would hand the decoder a picture built on something it never
     * saw. Instead: if an unsent frame would be lost to a delta, the chain is
     * declared broken, both frames are discarded, and every delta is refused
     * until a keyframe resets the chain.
     */
    private inner class VideoSlot {
        private var frame: Frame? = null
        private var needKey = true

        val awaitingKey: Boolean get() = synchronized(this) { needKey }

        fun offer(flags: Int, ptsUs: Long, src: ByteArray, off: Int, len: Int): OfferResult = synchronized(this) {
            val key = flags and MediaFlags.KEYFRAME != 0
            if (key) {
                val replaced = frame != null
                frame = copyFrame(flags, ptsUs, src, off, len)
                needKey = false
                return if (replaced) OfferResult.REPLACED_KEY else OfferResult.QUEUED
            }
            if (needKey) return OfferResult.GATED
            if (frame != null) {
                frame = null
                needKey = true
                return OfferResult.CHAIN_BROKEN
            }
            frame = copyFrame(flags, ptsUs, src, off, len)
            OfferResult.QUEUED
        }

        fun take(): Frame? = synchronized(this) { frame.also { frame = null } }
    }

    private companion object {
        const val MEDIA_HEAD = 10
        const val AUDIO_QUEUE = 8
        const val LIVENESS_TICK_MS = 250L
    }
}
