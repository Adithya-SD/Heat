package app.heat.core.link

import java.util.concurrent.CopyOnWriteArrayList

/**
 * All live sessions with ONE peer, one per carrier, and the rule for choosing among them.
 *
 * The rule is the whole point of this class: **there is one conversation with
 * a peer, and a carrier is just how it happens to be travelling right now.**
 *
 *  - "Best" is the lowest-ranked carrier that is open *and healthy*. A session
 *    that has gone quiet for [SUSPECT_SILENCE_MS] (pings go out every 2 s) is
 *    demoted below any healthy one, so traffic shifts to the spare while the
 *    suspect link is still pending its timeout, instead of vanishing into it.
 *    If it recovers, it is promoted again.
 *  - Control and media both go down the best session only. Video is thousands
 *    of times larger than anything else, and control reliability is handled by
 *    [ReliableChannel] (numbering, acks, resend), not by sending twice.
 *  - When the best dies, the next is already there: already authenticated,
 *    already warm.
 */
class SessionSet(private val onChange: () -> Unit = {}) {

    class Entry(val kind: CarrierKind, val session: Session)

    private val entries = CopyOnWriteArrayList<Entry>()

    fun add(kind: CarrierKind, session: Session) {
        entries += Entry(kind, session)
        onChange()
    }

    fun remove(session: Session) {
        entries.removeAll { it.session === session }
        onChange()
    }

    /** Open sessions: healthy ones first, each group by carrier preference. */
    fun live(): List<Entry> {
        val open = entries.filter { it.session.isOpen }
        val (healthy, suspect) = open.partition { it.session.silenceMs() < SUSPECT_SILENCE_MS }
        return healthy.sortedBy { it.kind.rank } + suspect.sortedBy { it.kind.rank }
    }

    fun best(): Entry? = live().firstOrNull()

    val isUp: Boolean get() = entries.any { it.session.isOpen }

    /** Queues [data] on the best session. */
    fun sendControl(data: ByteArray): Boolean {
        for (entry in live()) if (entry.session.sendControl(data)) return true
        return false
    }

    /** Offers a media frame to the best session only. */
    fun sendMedia(stream: Int, flags: Int, ptsUs: Long, data: ByteArray, offset: Int, length: Int): Boolean =
        best()?.session?.sendMedia(stream, flags, ptsUs, data, offset, length) ?: false

    fun closeAll() = entries.forEach { it.session.close() }

    companion object {
        /**
         * Pings go out every 2 s, so a healthy link is never silent for much more
         * than that. Past this, assume the worst and prefer the spare.
         */
        const val SUSPECT_SILENCE_MS = 3_000L
    }
}
