package app.heat.core.link

import app.heat.core.util.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.random.Random

/** What the application layer hears from a link, tagged with the carrier it arrived on. */
interface LinkHandler {
    /** A session finished its handshake. Send your `Hello` and any resync here. */
    fun onSessionUp(kind: CarrierKind, session: Session) = Unit

    fun onControl(kind: CarrierKind, session: Session, data: ByteArray, offset: Int, length: Int)

    fun onMedia(
        kind: CarrierKind, session: Session,
        stream: Int, flags: Int, ptsUs: Long, data: ByteArray, offset: Int, length: Int
    ) = Unit

    /** Video had to be dropped on this session; ask the encoder for a keyframe. */
    fun onMediaDropped(kind: CarrierKind, session: Session, stream: Int) = Unit

    fun onSessionDown(kind: CarrierKind, session: Session, reason: CloseReason, detail: String?) = Unit

    fun onHandlerError(error: Throwable) = Unit
}

/** Adapts a [LinkHandler] to one session's [SessionHandler], tagging it with its carrier. */
internal class TaggedHandler(
    private val kind: CarrierKind,
    private val link: LinkHandler,
    private val onClosedHook: (CloseReason, String?) -> Unit
) : SessionHandler {
    lateinit var session: Session

    override fun onControl(data: ByteArray, offset: Int, length: Int) =
        link.onControl(kind, session, data, offset, length)

    override fun onMedia(stream: Int, flags: Int, ptsUs: Long, data: ByteArray, offset: Int, length: Int) =
        link.onMedia(kind, session, stream, flags, ptsUs, data, offset, length)

    override fun onMediaDropped(stream: Int) = link.onMediaDropped(kind, session, stream)

    override fun onClosed(reason: CloseReason, detail: String?) {
        try {
            link.onSessionDown(kind, session, reason, detail)
        } finally {
            onClosedHook(reason, detail)
        }
    }

    override fun onHandlerError(error: Throwable) = link.onHandlerError(error)
}

/** How hard a dialer tries, and how quickly it gives up and backs off. */
class RetryPolicy(
    val minMs: Long = 250,
    val maxMs: Long = 5_000,
    /** Spread so a house full of devices does not retry in lockstep. */
    val jitter: Double = 0.2,
    val dialTimeoutMs: Long = 3_000,
    /** A peer that rejected our key will not change its mind; do not hammer it. */
    val authRetryMs: Long = 20_000,
    /** A session shorter than this was not a success (it died right after connecting). */
    val genuineMs: Long = 2_000
)

/** Where one maintainer is in its cycle. */
enum class DialPhase { IDLE, DIALING, HANDSHAKING, UP, BACKOFF, UNPAIRED }

/**
 * Keeps one carrier to one peer alive, forever, without ever making things worse.
 *
 * Connect → authenticate → run until the session ends → wait → repeat. The wait
 * is exponential with jitter, but it resets to the minimum only after a session
 * that actually *lasted* (see [RetryPolicy.genuineMs]): resetting on mere
 * connection success is how a peer that accepts and immediately hangs up turns
 * into a reconnect storm. A peer that rejects our key is retried rarely and
 * reported as [DialPhase.UNPAIRED], because no amount of retrying fixes it.
 *
 * [nudge] skips the current wait. Call it when something changes that makes a
 * connection newly possible: Wi-Fi came back, a beacon was heard, the app
 * returned to the foreground.
 */
class DialMaintainer(
    val dialer: Dialer,
    private val self: DeviceId,
    private val group: GroupKey,
    private val expect: DeviceId?,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val link: LinkHandler,
    private val sessions: SessionSet,
    private val sessionConfig: SessionConfig = SessionConfig(),
    private val policy: RetryPolicy = RetryPolicy(),
    private val onPhase: (DialPhase) -> Unit = {}
) {
    private val _phase = MutableStateFlow(DialPhase.IDLE)
    val phase: StateFlow<DialPhase> = _phase.asStateFlow()

    @Volatile var lastFailure: HandshakeFailure? = null
        private set

    private val nudges = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        setPhase(DialPhase.IDLE)
    }

    fun nudge() {
        nudges.trySend(Unit)
    }

    private fun setPhase(p: DialPhase) {
        _phase.value = p
        onPhase(p)
    }

    private suspend fun loop() {
        var backoff = policy.minMs
        while (true) {
            setPhase(DialPhase.DIALING)
            var wait = backoff

            val io = try {
                withTimeout(policy.dialTimeoutMs) { dialer.dial() }
            } catch (e: TimeoutCancellationException) {
                null
            } catch (e: IOException) {
                null
            }

            if (io != null) {
                setPhase(DialPhase.HANDSHAKING)
                try {
                    val hr = Handshake.client(io, self, group, expect)
                    lastFailure = null
                    val reason = runSession(hr, io)
                    // `runSession` returns when the session ends; judge it by how long it lived.
                    wait = if (reason.lasted >= policy.genuineMs) policy.minMs else backoff
                    backoff = if (reason.lasted >= policy.genuineMs) policy.minMs else (backoff * 2).coerceAtMost(policy.maxMs)
                } catch (e: HandshakeException) {
                    io.close()
                    lastFailure = e.failure
                    if (e.failure == HandshakeFailure.AUTH) {
                        setPhase(DialPhase.UNPAIRED)
                        withTimeoutOrNull(policy.authRetryMs) { nudges.receive() }
                        continue
                    }
                    backoff = (backoff * 2).coerceAtMost(policy.maxMs)
                } catch (e: IOException) {
                    io.close()
                    backoff = (backoff * 2).coerceAtMost(policy.maxMs)
                }
            } else {
                backoff = (backoff * 2).coerceAtMost(policy.maxMs)
            }

            setPhase(DialPhase.BACKOFF)
            val jittered = (wait * (1.0 + (Random.nextDouble() * 2 - 1) * policy.jitter)).toLong().coerceAtLeast(1)
            withTimeoutOrNull(jittered) { nudges.receive() }
        }
    }

    private class Ended(val reason: CloseReason, val lasted: Long)

    private suspend fun runSession(hr: HandshakeResult, io: ByteDuplex): Ended {
        val closed = CompletableDeferred<CloseReason>()
        val upAt = clock.nowMs()
        lateinit var session: Session
        val handler = TaggedHandler(dialer.kind, link) { reason, _ ->
            sessions.remove(session)
            closed.complete(reason)
        }
        session = Session(hr.peer, true, io, hr, handler, sessionConfig, clock)
        handler.session = session
        sessions.add(dialer.kind, session)
        session.start(scope)
        setPhase(DialPhase.UP)
        try {
            link.onSessionUp(dialer.kind, session)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            link.onHandlerError(e)
        }
        val reason = closed.await()
        return Ended(reason, clock.nowMs() - upAt)
    }
}

/** Overall connectivity to one peer, for the UI. */
data class LinkState(
    val phase: Phase,
    /** Carrier carrying traffic now. */
    val carrier: CarrierKind? = null,
    val rttMs: Int = -1,
    /** Other carriers that are up and ready to take over. */
    val standby: Set<CarrierKind> = emptySet()
) {
    enum class Phase {
        /** Not trying. */
        IDLE,

        /** Trying, nothing up yet. */
        CONNECTING,

        /** At least one session is up. */
        UP,

        /** Was up or tried; waiting to retry. */
        RETRYING,

        /** The peer will not accept our key. Needs re-pairing; retrying will not help. */
        UNPAIRED
    }
}

/**
 * Everything the viewer side needs to hold a connection to one camera:
 * one [DialMaintainer] per carrier, all running at once, feeding one
 * [Conversation] that picks among whatever is up and keeps control messages
 * exactly-once across their comings and goings.
 *
 * Carriers race. Whichever authenticates first carries traffic; the others
 * come up behind it as hot standbys, so when the primary dies the switch is
 * instant instead of a reconnect.
 */
class PeerLink(
    val peer: DeviceId?,
    private val self: DeviceId,
    private val group: GroupKey,
    private val scope: CoroutineScope,
    private val clock: Clock,
    listener: PeerListener,
    private val sessionConfig: SessionConfig = SessionConfig(),
    private val policy: RetryPolicy = RetryPolicy()
) {
    private val lock = Any()
    private val maintainers = LinkedHashMap<CarrierKind, DialMaintainer>()
    private val _state = MutableStateFlow(LinkState(LinkState.Phase.IDLE))
    private var running = false

    private val conversation: Conversation = Conversation(
        clock, scope,
        object : PeerListener by listener {
            override fun onUp(kind: CarrierKind) { listener.onUp(kind); recompute() }
            override fun onDown(kind: CarrierKind, reason: CloseReason, detail: String?) {
                listener.onDown(kind, reason, detail); recompute()
            }
            override fun onBestChanged(kind: CarrierKind?) { listener.onBestChanged(kind); recompute() }
        },
        sessionConfig
    )

    val state: StateFlow<LinkState> = _state.asStateFlow()

    /** Failure of the last handshake on any carrier, e.g. [HandshakeFailure.AUTH]. */
    val lastFailure: HandshakeFailure?
        get() = synchronized(lock) { maintainers.values.firstNotNullOfOrNull { it.lastFailure } }

    /** Reliable messages sent but not yet known to have arrived. */
    val pending: Int get() = conversation.pending

    fun start() {
        synchronized(lock) {
            running = true
            maintainers.values.forEach { it.start() }
        }
        recompute()
    }

    /** Stops dialing and closes every session. */
    fun stop() {
        synchronized(lock) {
            running = false
            maintainers.values.forEach { it.stop() }
        }
        conversation.close()
        recompute()
    }

    /**
     * Sets (or replaces, or with null removes) how to reach the peer over [kind].
     * An existing live session on the old route is left alone and ends on its own.
     */
    fun setDialer(kind: CarrierKind, dialer: Dialer?, retry: RetryPolicy = policy) {
        synchronized(lock) {
            maintainers.remove(kind)?.stop()
            if (dialer != null) {
                val m = DialMaintainer(
                    dialer, self, group, peer, scope, clock, conversation, conversation.sessions,
                    sessionConfig, retry, onPhase = { recompute() }
                )
                maintainers[kind] = m
                if (running) m.start()
            }
        }
        recompute()
    }

    fun hasDialer(kind: CarrierKind): Boolean = synchronized(lock) { kind in maintainers }

    /** Something changed that makes connecting newly possible. Retry now. */
    fun nudge() = synchronized(lock) { maintainers.values.forEach { it.nudge() } }

    /** Reliable (exactly once, in order) unless [reliable] is false. Never blocks. */
    fun send(payload: ByteArray, reliable: Boolean = true): Boolean = conversation.send(payload, reliable)

    fun sendMedia(stream: Int, flags: Int, ptsUs: Long, data: ByteArray, offset: Int, length: Int): Boolean =
        conversation.sendMedia(stream, flags, ptsUs, data, offset, length)

    private fun recompute() {
        val live = conversation.sessions.live()
        val next = synchronized(lock) {
            when {
                live.isNotEmpty() -> LinkState(
                    LinkState.Phase.UP,
                    carrier = live.first().kind,
                    rttMs = live.first().session.rttMs,
                    standby = live.drop(1).map { it.kind }.toSet()
                )
                !running || maintainers.isEmpty() -> LinkState(LinkState.Phase.IDLE)
                maintainers.values.all { it.phase.value == DialPhase.UNPAIRED } ->
                    LinkState(LinkState.Phase.UNPAIRED)
                maintainers.values.any { it.phase.value == DialPhase.BACKOFF } ->
                    LinkState(LinkState.Phase.RETRYING)
                else -> LinkState(LinkState.Phase.CONNECTING)
            }
        }
        if (_state.value != next) _state.value = next
    }
}
