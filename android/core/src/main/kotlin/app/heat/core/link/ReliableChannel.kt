package app.heat.core.link

import app.heat.core.util.Clock
import kotlin.random.Random

/**
 * Exactly-once, in-order delivery of control messages across any number of
 * link failures and carrier switches.
 *
 * A session guarantees order on *one* connection, but a connection can die with
 * messages in its queue, or in flight, and the sender can never know which. So
 * the guarantee lives one level up, with the oldest idea in networking:
 * numbered messages, cumulative acknowledgements, retransmit until acked,
 * discard duplicates.
 *
 *  - The sender keeps every reliable message in an outbox until the peer
 *    acknowledges it. When the best session changes (it died, or a better one
 *    appeared), or an acknowledgement is overdue, everything unacknowledged is
 *    sent again, in order, on the current best session.
 *  - The receiver delivers ids strictly in order, once. A duplicate is dropped;
 *    an early arrival waits for its predecessors, which retransmission is
 *    guaranteed to supply. There is deliberately no timeout that gives up on a
 *    gap: the only way an id can be skipped is the sender saying, in the frame
 *    itself, that it no longer holds it (outbox overflow after a very long
 *    outage). That case is counted in [lost] and [skippedGaps].
 *  - [epoch] identifies one incarnation of the sender. A restarted peer
 *    numbers from 1 again; the new epoch tells the receiver to start counting
 *    afresh instead of discarding everything as already seen.
 *
 * Unreliable messages ([send] with `reliable = false`) skip all of that: they
 * are for things that are stale a second later anyway (status ticks).
 *
 * Wire format of one control payload:
 * ```
 * 0x00 | payload                                              unreliable
 * 0x01 | epoch u32 | id u64 | base u64 | ackEpoch u32 | ack u64 | payload   reliable, with a piggybacked
 *                                                                          ack; base is the oldest id the
 *                                                                          sender still holds
 * 0x02 | ackEpoch u32 | ack u64                                  acknowledgement only
 * ```
 *
 * Thread-safe. [transmit] runs under the internal lock, so it must be quick and must not block. [deliver] runs
 * outside it (one message at a time, in order), so the application may take its own locks there and call [send]
 * from inside it or from under those locks without a lock-order inversion.
 */
class ReliableChannel(
    private val clock: Clock,
    /** Sends raw control bytes on the current best session. Returns false if none could take it. */
    private val transmit: (ByteArray) -> Boolean,
    /** Receives each message exactly once, in order. The array is only valid during the call. */
    private val deliver: (ByteArray, Int, Int) -> Unit,
    private val config: Config = Config(),
    val epoch: Int = Random.nextInt()
) {
    class Config(
        val maxOutbox: Int = 256,
        val maxOutboxBytes: Int = 512 * 1024,
        /** Unacknowledged this long with nothing else prompting it: send again. */
        val retransmitMs: Long = 1_500,
        /** Acknowledgements are coalesced for this long, to ride on the next outgoing message. */
        val ackDelayMs: Long = 60,
        val maxReorder: Int = 128,

        /**
         * Retransmissions go out at most this many frames at a time, oldest first,
         * and further ones are released as acknowledgements arrive. Dumping the
         * whole backlog at once after an outage only overflows the receiver.
         */
        val window: Int = 48,

        /** An acknowledgement that makes progress re-sends frames unacknowledged for this long (fast retransmit). */
        val fastRetransmitMs: Long = 400
    )

    private class Out(val id: Long, val payload: ByteArray, var lastSentMs: Long)

    private val lock = Any()

    /** Serialises delivery so order holds across threads. Never taken while [lock] is held. */
    private val deliverLock = Any()

    private class Delivery(val data: ByteArray, val offset: Int, val length: Int)

    /** Messages ready for the application, collected under [lock] and handed over outside it. */
    private var inbox: ArrayList<Delivery>? = null

    // sender
    private val outbox = ArrayDeque<Out>()
    private var outboxBytes = 0
    private var nextId = 1L

    // receiver
    private var peerEpoch = 0
    private var haveEpoch = false
    private var highest = 0L
    private val held = java.util.TreeMap<Long, ByteArray>()
    private var ackDirty = false
    private var lastAckSentMs = 0L

    /** Reliable messages discarded because the outbox overflowed (peer gone for a long time). */
    @Volatile var lost = 0L
        private set

    /** Messages the peer told us it no longer had (its outbox overflowed). Should stay 0. */
    @Volatile var skippedGaps = 0L
        private set

    val pending: Int get() = synchronized(lock) { outbox.size }

    // ── sending ──────────────────────────────────────────────────────

    fun send(payload: ByteArray, reliable: Boolean = true): Boolean = synchronized(lock) {
        if (!reliable) {
            val framed = ByteArray(1 + payload.size)
            System.arraycopy(payload, 0, framed, 1, payload.size)
            return transmit(framed)
        }
        while (outbox.size >= config.maxOutbox || (outbox.isNotEmpty() && outboxBytes + payload.size > config.maxOutboxBytes)) {
            outboxBytes -= outbox.removeFirst().payload.size
            lost++
        }
        val entry = Out(nextId++, payload, 0)
        outbox.addLast(entry)
        outboxBytes += payload.size
        transmitEntry(entry)
        true
    }

    /** The best session changed (died, or a better carrier came up): anything unacknowledged goes out again now. */
    fun bestChanged(): Unit = synchronized(lock) { resend(minAgeMs = 0) }

    // ── receiving ────────────────────────────────────────────────────

    fun onRaw(data: ByteArray, offset: Int, length: Int) {
        synchronized(deliverLock) {
            val ready = synchronized(lock) {
                receive(data, offset, length)
                inbox.also { inbox = null }
            }
            if (ready != null) for (d in ready) deliver(d.data, d.offset, d.length)
        }
    }

    private fun receive(data: ByteArray, offset: Int, length: Int) {
        if (length < 1) return
        when (data[offset].toInt()) {
            FLAG_UNRELIABLE -> queue(data, offset + 1, length - 1)

            FLAG_RELIABLE -> {
                if (length < RELIABLE_HEAD) return
                val theirEpoch = getInt(data, offset + 1)
                val id = getLong(data, offset + 5)
                val base = getLong(data, offset + 13)
                onAck(getInt(data, offset + 21), getLong(data, offset + 25))
                onData(theirEpoch, id, base, data, offset + RELIABLE_HEAD, length - RELIABLE_HEAD)
            }

            FLAG_ACK -> {
                if (length < ACK_SIZE) return
                onAck(getInt(data, offset + 1), getLong(data, offset + 5))
            }
            // Unknown flag from a newer peer: ignore.
        }
    }

    /** Call every ~50 ms. Sends delayed acks and retransmits overdue messages. */
    fun tick(): Unit = synchronized(lock) {
        val now = clock.nowMs()

        if (ackDirty && now - lastAckSentMs >= config.ackDelayMs && haveEpoch) {
            if (transmit(ackFrame())) {
                ackDirty = false
                lastAckSentMs = now
            }
        }

        val oldest = outbox.firstOrNull()
        if (oldest != null && now - oldest.lastSentMs >= config.retransmitMs) resend(config.retransmitMs)
    }

    // ── internals ────────────────────────────────────────────────────

    private fun onAck(ackEpoch: Int, ack: Long) {
        if (ackEpoch != epoch) return // acknowledges a previous incarnation of us
        var progressed = false
        while (outbox.isNotEmpty() && outbox.first().id <= ack) {
            outboxBytes -= outbox.removeFirst().payload.size
            progressed = true
        }
        // Ack-clocked: progress means the path works, so release the next window now rather than waiting out the timer.
        if (progressed && outbox.isNotEmpty()) resend(config.fastRetransmitMs)
    }

    private fun onData(theirEpoch: Int, id: Long, base: Long, data: ByteArray, offset: Int, length: Int) {
        if (!haveEpoch || theirEpoch != peerEpoch) {
            haveEpoch = true
            peerEpoch = theirEpoch
            highest = 0
            held.clear()
        }
        ackDirty = true
        // The sender holds nothing below `base`: those ids will never come. Skip them, once, loudly.
        if (base > highest + 1) {
            skippedGaps += base - 1 - highest
            highest = base - 1
            drainHeld()
        }
        when {
            id <= highest -> Unit // duplicate

            id == highest + 1 -> {
                highest = id
                queue(data, offset, length)
                drainHeld()
            }

            else -> {
                held.putIfAbsent(id, data.copyOfRange(offset, offset + length))
                // Keep the frames nearest the gap: they are the ones that unblock delivery.
                while (held.size > config.maxReorder) held.pollLastEntry()
            }
        }
    }

    private fun queue(data: ByteArray, offset: Int, length: Int) {
        val list = inbox ?: ArrayList<Delivery>(2).also { inbox = it }
        list += Delivery(data, offset, length)
    }

    private fun drainHeld() {
        while (true) {
            val next = held.firstEntry() ?: break
            if (next.key <= highest) {
                held.remove(next.key)
                continue
            }
            if (next.key != highest + 1) break
            held.remove(next.key)
            highest = next.key
            val payload = next.value
            queue(payload, 0, payload.size)
        }
    }

    /** Sends up to [Config.window] of the oldest unacknowledged frames that have not been sent in the last [minAgeMs]. */
    private fun resend(minAgeMs: Long) {
        val now = clock.nowMs()
        var looked = 0
        for (entry in outbox) {
            if (looked++ >= config.window) break
            if (now - entry.lastSentMs < minAgeMs) continue
            if (!transmitEntry(entry)) break
        }
    }

    private fun transmitEntry(e: Out): Boolean {
        val ok = transmit(reliableFrame(e))
        if (ok) {
            e.lastSentMs = clock.nowMs()
            ackDirty = false // the frame carried our latest acknowledgement
            lastAckSentMs = e.lastSentMs
        }
        return ok
    }

    private fun reliableFrame(e: Out): ByteArray {
        val f = ByteArray(RELIABLE_HEAD + e.payload.size)
        f[0] = FLAG_RELIABLE.toByte()
        putInt(f, epoch, 1)
        putLong(f, e.id, 5)
        putLong(f, outbox.firstOrNull()?.id ?: e.id, 13)
        putInt(f, if (haveEpoch) peerEpoch else 0, 21)
        putLong(f, highest, 25)
        System.arraycopy(e.payload, 0, f, RELIABLE_HEAD, e.payload.size)
        return f
    }

    private fun ackFrame(): ByteArray {
        val f = ByteArray(ACK_SIZE)
        f[0] = FLAG_ACK.toByte()
        putInt(f, peerEpoch, 1)
        putLong(f, highest, 5)
        return f
    }

    /** Internal state, for test diagnostics only. */
    internal fun debug(): String = synchronized(lock) {
        "highest=$highest held=${held.size}(${held.keys.take(3)}) outbox=${outbox.size} first=${outbox.firstOrNull()?.id} next=$nextId ackDirty=$ackDirty"
    }

    private companion object {
        const val FLAG_UNRELIABLE = 0
        const val FLAG_RELIABLE = 1
        const val FLAG_ACK = 2
        const val RELIABLE_HEAD = 33
        const val ACK_SIZE = 13
    }
}
