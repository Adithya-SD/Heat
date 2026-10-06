package app.heat.core.link

import app.heat.core.util.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** What the application hears from one peer, regardless of how many carriers are up. */
interface PeerListener {
    /** A session finished its handshake on [kind]. Called for standbys too. Send `Hello` / resync here. */
    fun onUp(kind: CarrierKind) = Unit

    /** A control message, exactly once and in order, however many links died on the way. Array valid only during the call. */
    fun onMessage(data: ByteArray, offset: Int, length: Int)

    fun onMedia(
        kind: CarrierKind,
        stream: Int, flags: Int, ptsUs: Long, data: ByteArray, offset: Int, length: Int
    ) = Unit

    /** Video had to be dropped for this peer; the encoder should produce a keyframe. */
    fun onMediaDropped(stream: Int) = Unit

    /** One session ended (the conversation may continue on another carrier). */
    fun onDown(kind: CarrierKind, reason: CloseReason, detail: String?) = Unit

    /** The carrier carrying traffic changed (or none is left, null). */
    fun onBestChanged(kind: CarrierKind?) = Unit

    fun onError(error: Throwable) = Unit
}

/**
 * Everything about one peer, in one place: its sessions (one per carrier), the
 * rule for choosing among them, and the reliable control channel that survives
 * their coming and going.
 *
 * Both ends use this class, the dialing side and the accepting side, so the
 * two cannot disagree about how a conversation behaves.
 */
class Conversation(
    private val clock: Clock,
    scope: CoroutineScope,
    private val listener: PeerListener,
    private val sessionConfig: SessionConfig = SessionConfig(),
    reliableConfig: ReliableChannel.Config = ReliableChannel.Config(),
    epoch: Int = kotlin.random.Random.nextInt()
) : LinkHandler {

    val sessions = SessionSet()

    private val reliable = ReliableChannel(
        clock,
        transmit = { sessions.sendControl(it) },
        deliver = { d, o, l -> guarded { listener.onMessage(d, o, l) } },
        config = reliableConfig,
        epoch = epoch
    )

    private val evalLock = Any()
    private var lastBest: Session? = null
    @Volatile private var idleSinceMs = clock.nowMs()
    private val ticker: Job = scope.launch {
        while (true) {
            delay(TICK_MS)
            reevaluate()
            reliable.tick()
        }
    }

    /** Messages that were never acknowledged, i.e. not yet known to have arrived. */
    val pending: Int get() = reliable.pending

    val isUp: Boolean get() = sessions.isUp

    /** Milliseconds since this conversation last had any session, 0 while it has one. */
    fun idleMs(): Long = if (sessions.isUp) 0 else clock.nowMs() - idleSinceMs

    /**
     * Sends a control message to the peer. Reliable messages are kept until
     * acknowledged and delivered exactly once, in order, across reconnects and
     * carrier switches. Unreliable ones are fire-and-forget (status ticks).
     */
    fun send(payload: ByteArray, reliable: Boolean = true): Boolean = this.reliable.send(payload, reliable)

    fun sendMedia(stream: Int, flags: Int, ptsUs: Long, data: ByteArray, offset: Int, length: Int): Boolean =
        sessions.sendMedia(stream, flags, ptsUs, data, offset, length)

    /** Accepting side: authenticate-complete connection becomes a session in this conversation. */
    fun adopt(kind: CarrierKind, result: HandshakeResult, io: ByteDuplex, scope: CoroutineScope): Session {
        lateinit var session: Session
        val handler = TaggedHandler(kind, this) { _, _ -> sessions.remove(session); reevaluate() }
        session = Session(result.peer, false, io, result, handler, sessionConfig, clock)
        handler.session = session
        sessions.add(kind, session)
        session.start(scope)
        onSessionUp(kind, session)
        return session
    }

    fun close() {
        ticker.cancel()
        sessions.closeAll()
    }

    // ── LinkHandler (called by sessions) ─────────────────────────────

    override fun onSessionUp(kind: CarrierKind, session: Session) {
        idleSinceMs = clock.nowMs()
        guarded { listener.onUp(kind) }
        reevaluate()
    }

    override fun onControl(kind: CarrierKind, session: Session, data: ByteArray, offset: Int, length: Int) =
        reliable.onRaw(data, offset, length)

    override fun onMedia(
        kind: CarrierKind, session: Session,
        stream: Int, flags: Int, ptsUs: Long, data: ByteArray, offset: Int, length: Int
    ) {
        // Only the best session's media counts; a stale carrier's leftovers must not interleave with the live one.
        if (sessions.best()?.session === session) listener.onMedia(kind, stream, flags, ptsUs, data, offset, length)
    }

    override fun onMediaDropped(kind: CarrierKind, session: Session, stream: Int) {
        if (sessions.best()?.session === session) guarded { listener.onMediaDropped(stream) }
    }

    override fun onSessionDown(kind: CarrierKind, session: Session, reason: CloseReason, detail: String?) {
        if (!sessions.isUp) idleSinceMs = clock.nowMs()
        guarded { listener.onDown(kind, reason, detail) }
    }

    override fun onHandlerError(error: Throwable) = listener.onError(error)

    // ── internals ────────────────────────────────────────────────────

    /** Notices a change of best session (a death, a promotion, a suspect demotion) and reacts exactly once. */
    internal fun reevaluate() {
        val bestEntry = sessions.best()
        val best = bestEntry?.session
        synchronized(evalLock) {
            if (best === lastBest) return
            lastBest = best
        }
        guarded { listener.onBestChanged(bestEntry?.kind) }
        // Anything not yet acknowledged may have died with the old route: send it again on the new one.
        reliable.bestChanged()
        // The new route's video starts from a clean slate: the decoder needs a keyframe.
        if (best != null) guarded { listener.onMediaDropped(Stream.VIDEO) }
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            runCatching { listener.onError(e) }
        }
    }

    private companion object {
        const val TICK_MS = 50L
    }
}

/**
 * Camera side: one [Conversation] per viewer that has ever connected, created
 * on first contact and dropped once it has been gone long enough.
 */
class ConversationRegistry(
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val sessionConfig: SessionConfig = SessionConfig(),
    private val factory: (DeviceId) -> PeerListener
) {
    private val conversations = ConcurrentHashMap<DeviceId, Conversation>()

    fun forPeer(id: DeviceId): Conversation =
        conversations.computeIfAbsent(id) { Conversation(clock, scope, factory(it), sessionConfig) }

    fun existing(id: DeviceId): Conversation? = conversations[id]

    fun connectedPeers(): Set<DeviceId> = conversations.filter { it.value.isUp }.keys

    fun all(): Map<DeviceId, Conversation> = conversations

    /** Forget viewers that have been gone for [maxIdleMs] and have nothing left to deliver. */
    fun reap(maxIdleMs: Long = 10 * 60_000L) {
        for ((id, c) in conversations) {
            if (c.idleMs() > maxIdleMs && c.pending == 0) {
                conversations.remove(id)?.close()
            }
        }
    }

    /** What the [Acceptor] should call with each authenticated incoming session. */
    fun acceptHandler() = AcceptHandler { kind, result, io ->
        forPeer(result.peer).adopt(kind, result, io, scope)
    }

    fun closeAll() {
        conversations.values.forEach { it.close() }
        conversations.clear()
    }
}
