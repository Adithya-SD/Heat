package app.heat.core.link

import app.heat.core.util.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReliableChannelTest {

    /** Two channels joined by a simulated network with loss, duplication, reordering and outages. */
    private class Net(seed: Int, val loss: Double = 0.0, val dup: Double = 0.0, val maxDelayMs: Int = 0) {
        var now = 1_000L
        val clock = Clock { now }
        private val rnd = Random(seed)
        var outage = false          // transmit() returns false: no session at all
        var blackhole = false       // transmit() claims success but nothing arrives
        private class Packet(val at: Long, val to: Int, val bytes: ByteArray)
        private val inflight = ArrayList<Packet>()
        val gotA = ArrayList<String>()
        val gotB = ArrayList<String>()

        val a: ReliableChannel = ReliableChannel(clock, { send(1, it) }, { d, o, l -> gotA += String(d, o, l) }, ReliableChannel.Config(maxOutbox = 100_000, maxOutboxBytes = 100_000_000), epoch = 111)
        val b: ReliableChannel = ReliableChannel(clock, { send(0, it) }, { d, o, l -> gotB += String(d, o, l) }, ReliableChannel.Config(maxOutbox = 100_000, maxOutboxBytes = 100_000_000), epoch = 222)

        private fun send(to: Int, bytes: ByteArray): Boolean {
            if (outage) return false
            if (blackhole || rnd.nextDouble() < loss) return true
            val copies = if (rnd.nextDouble() < dup) 2 else 1
            repeat(copies) {
                inflight += Packet(now + (if (maxDelayMs > 0) rnd.nextInt(maxDelayMs + 1) else 0), to, bytes.copyOf())
            }
            return true
        }

        fun step(ms: Int = 10) {
            now += ms
            val due = inflight.filter { it.at <= now }.sortedBy { it.at }
            inflight.removeAll(due.toSet())
            for (p in due) (if (p.to == 0) a else b).onRaw(p.bytes, 0, p.bytes.size)
            if (now % 50 == 0L) { a.tick(); b.tick() }
        }

        fun run(ms: Int) { repeat(ms / 10) { step() } }
    }

    @Test
    fun in_order_delivery_and_acks_empty_the_outbox() {
        val n = Net(1)
        repeat(10) { n.a.send("m$it".toByteArray()) }
        n.run(500)
        assertEquals((0 until 10).map { "m$it" }, n.gotB)
        assertEquals(0, n.a.pending)
    }

    @Test
    fun a_lost_message_is_retransmitted_and_delivered_once() {
        val n = Net(2, loss = 1.0)
        n.a.send("hello".toByteArray())
        n.run(500)
        assertTrue(n.gotB.isEmpty())
        // network heals
        val healed = Net(2)
        healed.a.send("hello".toByteArray())
        healed.run(100)
        assertEquals(listOf("hello"), healed.gotB)
    }

    @Test
    fun retransmission_fires_after_the_timeout_when_the_first_copy_vanished() {
        val n = Net(3)
        n.blackhole = true
        n.a.send("x".toByteArray())
        n.run(1_000)
        assertTrue(n.gotB.isEmpty())
        n.blackhole = false
        n.run(2_000)
        assertEquals(listOf("x"), n.gotB)
        assertEquals(0, n.a.pending)
    }

    @Test
    fun duplicates_are_dropped() {
        val n = Net(4, dup = 1.0)
        repeat(20) { n.a.send("d$it".toByteArray()) }
        n.run(1_000)
        assertEquals((0 until 20).map { "d$it" }, n.gotB)
    }

    @Test
    fun reordering_is_repaired_before_delivery() {
        val n = Net(5, maxDelayMs = 400)
        repeat(50) { n.a.send("r$it".toByteArray()) }
        n.run(5_000)
        assertEquals((0 until 50).map { "r$it" }, n.gotB)
    }

    @Test
    fun a_session_switch_resends_everything_unacknowledged() {
        val n = Net(6)
        n.blackhole = true                       // old carrier swallows everything
        repeat(5) { n.a.send("s$it".toByteArray()) }
        n.run(200)
        n.blackhole = false                      // new carrier is healthy
        n.a.bestChanged()
        n.run(200)
        assertEquals((0 until 5).map { "s$it" }, n.gotB)
    }

    @Test
    fun messages_sent_while_there_is_no_session_are_kept_and_delivered_when_one_appears() {
        val n = Net(7)
        n.outage = true
        repeat(5) { n.a.send("q$it".toByteArray()) }
        n.run(3_000)
        assertTrue(n.gotB.isEmpty())
        assertEquals(5, n.a.pending)
        n.outage = false
        n.a.bestChanged()
        n.run(500)
        assertEquals((0 until 5).map { "q$it" }, n.gotB)
    }

    @Test
    fun a_restarted_peer_is_not_mistaken_for_a_stream_of_duplicates() {
        val n = Net(8)
        repeat(5) { n.a.send("old$it".toByteArray()) }
        n.run(300)
        // A restarts: new incarnation, ids start at 1 again.
        val a2Got = ArrayList<String>()
        val a2 = ReliableChannel(n.clock, { bytes -> n.b.onRaw(bytes, 0, bytes.size); true }, { d, o, l -> a2Got += String(d, o, l) }, epoch = 999)
        a2.send("new0".toByteArray())
        a2.send("new1".toByteArray())
        assertEquals((0 until 5).map { "old$it" } + listOf("new0", "new1"), n.gotB)
    }

    private fun frame(epoch: Int, id: Long, base: Long, text: String): ByteArray {
        val f = ByteArray(33 + text.length)
        f[0] = 1
        putInt(f, epoch, 1); putLong(f, id, 5); putLong(f, base, 13); putInt(f, 0, 21); putLong(f, 0, 25)
        System.arraycopy(text.toByteArray(), 0, f, 33, text.length)
        return f
    }

    @Test
    fun a_gap_is_never_skipped_on_a_timer_only_when_the_sender_says_it_no_longer_has_it() {
        val clock = object { var now = 0L }
        val got = ArrayList<String>()
        val rx = ReliableChannel(Clock { clock.now }, { true }, { d, o, l -> got += String(d, o, l) }, epoch = 1)
        val a = frame(7, 1, 1, "a")
        rx.onRaw(a, 0, a.size)
        val c = frame(7, 3, 1, "c")          // id 2 missing, but the sender still holds it (base = 1)
        rx.onRaw(c, 0, c.size)
        clock.now = 600_000
        repeat(100) { rx.tick() }
        assertEquals("waits for a message the sender can still retransmit", listOf("a"), got)

        val d = frame(7, 4, 3, "d")          // sender overflowed: ids below 3 are gone for good
        rx.onRaw(d, 0, d.size)
        assertEquals(listOf("a", "c", "d"), got)
        assertEquals(1L, rx.skippedGaps)
    }

    @Test
    fun the_outbox_is_bounded() {
        val a = ReliableChannel(Clock { 0L }, { false }, { _, _, _ -> }, epoch = 1)
        repeat(1_000) { a.send("x".toByteArray()) }
        assertTrue(a.pending <= 256)
        assertTrue(a.lost > 0)
    }

    @Test
    fun unreliable_messages_are_delivered_immediately_and_never_retransmitted() {
        val n = Net(10)
        n.a.send("tick".toByteArray(), reliable = false)
        n.run(100)
        assertEquals(listOf("tick"), n.gotB)
        assertEquals(0, n.a.pending)
        n.blackhole = true
        n.a.send("tick2".toByteArray(), reliable = false)
        n.blackhole = false
        n.run(5_000)
        assertEquals(listOf("tick"), n.gotB)
    }

    /**
     * The headline property. Two peers talk through a network that loses, duplicates and
     * reorders packets, goes down entirely, silently swallows traffic, and keeps "switching
     * carriers", for a simulated minute. Afterwards, with the network healed, every message
     * in each direction must have arrived exactly once and in order.
     */
    @Test
    fun chaos_never_loses_duplicates_or_reorders_a_single_message() {
        for (seed in 1..40) {
            val n = Net(seed, loss = 0.25, dup = 0.15, maxDelayMs = 300)
            val rnd = Random(seed * 7919)
            var sentA = 0
            var sentB = 0
            repeat(6_000) { tickIndex ->            // 60 simulated seconds
                if (rnd.nextInt(10) == 0) n.a.send("A${sentA++}".toByteArray())
                if (rnd.nextInt(10) == 0) n.b.send("B${sentB++}".toByteArray())
                if (rnd.nextInt(200) == 0) n.outage = !n.outage
                if (rnd.nextInt(300) == 0) n.blackhole = !n.blackhole
                if (rnd.nextInt(150) == 0) { n.a.bestChanged(); n.b.bestChanged() }
                n.step()
            }
            // heal and let everything drain
            n.outage = false; n.blackhole = false
            n.a.bestChanged(); n.b.bestChanged()
            n.run(60_000)

            fun diag(name: String, want: List<String>, got: List<String>): String {
                val i = want.indices.firstOrNull { it >= got.size || want[it] != got[it] } ?: -1
                return "seed $seed $name: want ${want.size} got ${got.size}, first mismatch at $i " +
                    "(${got.getOrNull(i)} vs ${want.getOrNull(i)}), lostA=${n.a.lost} lostB=${n.b.lost} " +
                    "gaps=${n.a.skippedGaps}/${n.b.skippedGaps} pending=${n.a.pending}/${n.b.pending} A[${n.a.debug()}] B[${n.b.debug()}]"
            }
            assertEquals("seed $seed: outbox must not overflow in this scenario", 0L, n.a.lost + n.b.lost)
            val wantB = (0 until sentA).map { "A$it" }
            val wantA = (0 until sentB).map { "B$it" }
            assertTrue(diag("A→B", wantB, n.gotB), wantB == n.gotB)
            assertTrue(diag("B→A", wantA, n.gotA), wantA == n.gotA)
        }
    }
}
