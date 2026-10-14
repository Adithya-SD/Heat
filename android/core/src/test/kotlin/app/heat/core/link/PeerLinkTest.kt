package app.heat.core.link

import app.heat.core.testing.MemoryLink
import app.heat.core.util.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The connection manager under abuse: cut links, silent partitions, wrong keys,
 * a flapping network, two carriers racing. All in virtual time, so a minute of
 * flapping runs in milliseconds and reproduces exactly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerLinkTest {

    private val group = GroupKey.generate()
    private val camera = DeviceId.random()
    private val viewer = DeviceId.random()

    private class Events : PeerListener {
        val messages = CopyOnWriteArrayList<String>()
        val up = CopyOnWriteArrayList<CarrierKind>()
        val down = CopyOnWriteArrayList<Pair<CarrierKind, CloseReason>>()
        val media = CopyOnWriteArrayList<Long>()
        val keyRequests = AtomicInteger()
        val errors = CopyOnWriteArrayList<Throwable>()
        override fun onUp(kind: CarrierKind) { up += kind }
        override fun onMessage(data: ByteArray, offset: Int, length: Int) { messages += String(data, offset, length) }
        override fun onMedia(kind: CarrierKind, stream: Int, flags: Int, ptsUs: Long, data: ByteArray, offset: Int, length: Int) { media += ptsUs }
        override fun onMediaDropped(stream: Int) { keyRequests.incrementAndGet() }
        override fun onDown(kind: CarrierKind, reason: CloseReason, detail: String?) { down += kind to reason }
        override fun onError(error: Throwable) { errors += error }
    }

    /** The camera end: accepts whatever the "network" delivers. */
    private inner class CameraSide(scope: TestScope, key: GroupKey = group) {
        val events = Events()
        val clock = Clock { scope.testScheduler.currentTime }
        val registry = ConversationRegistry(clock, scope.backgroundScope) { events }
        val acceptor = Acceptor(camera, key, scope.backgroundScope, handler = registry.acceptHandler())
        val toViewer: Conversation get() = registry.forPeer(viewer)
    }

    /** A dialer whose connections go through a fresh [MemoryLink] into [cam] unless told to fail. */
    private inner class FakeDialer(
        override val kind: CarrierKind,
        private val cam: CameraSide,
        private val scope: TestScope? = null,
        private val autoCutAfterMs: Long = -1,
        private val latencyMs: Long = 0
    ) : Dialer {
        val links = CopyOnWriteArrayList<MemoryLink>()
        val attempts = AtomicInteger()
        @Volatile var reachable = true
        override suspend fun dial(): ByteDuplex {
            attempts.incrementAndGet()
            if (!reachable) throw IOException("unreachable")
            val link = MemoryLink()
            link.faultsA.latencyMs = latencyMs
            link.faultsB.latencyMs = latencyMs
            links += link
            cam.acceptor.accept(kind, link.b)
            if (autoCutAfterMs >= 0) scope!!.backgroundScope.launch { delay(autoCutAfterMs); link.cut() }
            return link.a
        }
    }

    private fun TestScope.link(ev: Events, key: GroupKey = group) = PeerLink(
        camera, viewer, key, backgroundScope, Clock { testScheduler.currentTime }, ev,
        policy = RetryPolicy(jitter = 0.0)
    )

    private fun TestScope.settle(ms: Long = 300) { advanceTimeBy(ms); runCurrent() }

    @Test
    fun connects_and_carries_messages_both_ways() = runTest {
        val cam = CameraSide(this)
        val ev = Events()
        val l = link(ev)
        l.setDialer(CarrierKind.LAN, FakeDialer(CarrierKind.LAN, cam))
        l.start()
        settle()
        assertEquals(LinkState.Phase.UP, l.state.value.phase)
        assertEquals(CarrierKind.LAN, l.state.value.carrier)

        assertTrue(l.send("arm".toByteArray()))
        cam.toViewer.send("status".toByteArray())
        settle()
        assertEquals(listOf("arm"), cam.events.messages.toList())
        assertEquals(listOf("status"), ev.messages.toList())
    }

    @Test
    fun reconnects_by_itself_after_the_link_is_cut() = runTest {
        val cam = CameraSide(this)
        val ev = Events()
        val d = FakeDialer(CarrierKind.LAN, cam)
        val l = link(ev)
        l.setDialer(CarrierKind.LAN, d)
        l.start()
        settle()
        assertEquals(1, ev.up.size)

        d.links.last().cut()
        settle(5_000)
        assertEquals(2, ev.up.size)
        assertEquals(LinkState.Phase.UP, l.state.value.phase)
    }

    @Test
    fun a_command_sent_into_a_dying_link_still_arrives_after_the_reconnect() = runTest {
        val cam = CameraSide(this)
        val d = FakeDialer(CarrierKind.LAN, cam)
        val l = link(Events())
        l.setDialer(CarrierKind.LAN, d)
        l.start()
        settle()

        d.links.last().cut()
        l.send("disarm".toByteArray())   // queued on a session that is already dead
        settle(8_000)
        assertEquals(listOf("disarm"), cam.events.messages.toList())  // exactly once
        assertEquals(0, l.pending)
    }

    @Test
    fun survives_a_flapping_network_for_a_whole_minute_and_ends_up_connected() = runTest {
        val cam = CameraSide(this)
        val ev = Events()
        val d = FakeDialer(CarrierKind.LAN, cam)
        val l = link(ev)
        l.setDialer(CarrierKind.LAN, d)
        l.start()
        settle()

        val sent = ArrayList<String>()
        repeat(30) { i ->
            d.reachable = i % 3 == 0
            d.links.lastOrNull()?.cut()
            val m = "cmd$i"; sent += m; l.send(m.toByteArray())
            advanceTimeBy(1_000); runCurrent()
        }
        d.reachable = true
        advanceTimeBy(20_000); runCurrent()
        assertEquals(LinkState.Phase.UP, l.state.value.phase)
        assertEquals("every command delivered once, in order, despite 30 cuts", sent, cam.events.messages.toList())
    }

    @Test
    fun backoff_grows_while_the_camera_is_unreachable_and_does_not_storm() = runTest {
        val cam = CameraSide(this)
        val d = FakeDialer(CarrierKind.LAN, cam).also { it.reachable = false }
        val l = link(Events())
        l.setDialer(CarrierKind.LAN, d)
        l.start()
        advanceTimeBy(60_000); runCurrent()
        // 250, 500, 1000, 2000, 4000, then 5000 each: ~15 attempts in a minute, not hundreds.
        assertTrue("attempts=${d.attempts.get()}", d.attempts.get() in 8..20)
        assertEquals(LinkState.Phase.RETRYING, l.state.value.phase)
    }

    @Test
    fun nudge_retries_immediately_instead_of_waiting_out_the_backoff() = runTest {
        val cam = CameraSide(this)
        val d = FakeDialer(CarrierKind.LAN, cam).also { it.reachable = false }
        val l = link(Events())
        l.setDialer(CarrierKind.LAN, d)
        l.start()
        advanceTimeBy(20_000); runCurrent()   // deep into backoff
        d.reachable = true
        l.nudge()                              // e.g. Wi-Fi came back
        settle(50)
        assertEquals(LinkState.Phase.UP, l.state.value.phase)
    }

    @Test
    fun a_wrong_key_is_reported_as_unpaired_and_retried_rarely() = runTest {
        val cam = CameraSide(this)
        val d = FakeDialer(CarrierKind.LAN, cam)
        val l = link(Events(), key = GroupKey.generate())
        l.setDialer(CarrierKind.LAN, d)
        l.start()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(LinkState.Phase.UNPAIRED, l.state.value.phase)
        assertEquals(HandshakeFailure.AUTH, l.lastFailure)
        assertTrue("attempts=${d.attempts.get()}", d.attempts.get() <= 4)
    }

    @Test
    fun a_peer_that_accepts_then_hangs_up_quickly_does_not_cause_a_reconnect_storm() = runTest {
        val cam = CameraSide(this)
        val d = FakeDialer(CarrierKind.LAN, cam, this, autoCutAfterMs = 500)
        val l = link(Events())
        l.setDialer(CarrierKind.LAN, d)
        l.start()
        advanceTimeBy(60_000); runCurrent()
        // Sessions that die in 0.5 s are not "successes": backoff keeps growing. A reset-on-connect
        // bug would manage ~80 attempts here.
        assertTrue("attempts=${d.attempts.get()}", d.attempts.get() < 25)
    }

    @Test
    fun a_silent_partition_is_noticed_and_healed() = runTest {
        val cam = CameraSide(this)
        val ev = Events()
        val d = FakeDialer(CarrierKind.LAN, cam)
        val l = link(ev)
        l.setDialer(CarrierKind.LAN, d)
        l.start()
        settle()
        d.links.last().faultsA.blackhole = true
        d.links.last().faultsB.blackhole = true
        advanceTimeBy(10_000); runCurrent()
        assertTrue(ev.down.any { it.second == CloseReason.TIMEOUT })
        advanceTimeBy(10_000); runCurrent()
        assertEquals(LinkState.Phase.UP, l.state.value.phase)
    }

    // ── two carriers ─────────────────────────────────────────────────

    @Test
    fun both_carriers_come_up_and_traffic_prefers_the_better_one() = runTest {
        val cam = CameraSide(this)
        val l = link(Events())
        l.setDialer(CarrierKind.LAN, FakeDialer(CarrierKind.LAN, cam))
        l.setDialer(CarrierKind.BLUETOOTH, FakeDialer(CarrierKind.BLUETOOTH, cam))
        l.start()
        settle()
        assertEquals(CarrierKind.LAN, l.state.value.carrier)
        assertEquals(setOf(CarrierKind.BLUETOOTH), l.state.value.standby)
    }

    @Test
    fun losing_the_primary_fails_over_with_no_gap_in_control() = runTest {
        val cam = CameraSide(this)
        val lan = FakeDialer(CarrierKind.LAN, cam)
        val l = link(Events())
        l.setDialer(CarrierKind.LAN, lan)
        l.setDialer(CarrierKind.BLUETOOTH, FakeDialer(CarrierKind.BLUETOOTH, cam))
        l.start()
        settle()

        lan.reachable = false
        lan.links.last().cut()
        l.send("disarm".toByteArray())   // right at the moment of the cut
        settle(300)
        assertEquals(listOf("disarm"), cam.events.messages.toList())
        assertEquals(CarrierKind.BLUETOOTH, l.state.value.carrier)
    }

    @Test
    fun a_half_dead_primary_does_not_swallow_commands() = runTest {
        val cam = CameraSide(this)
        val lan = FakeDialer(CarrierKind.LAN, cam)
        val l = link(Events())
        l.setDialer(CarrierKind.LAN, lan)
        l.setDialer(CarrierKind.BLUETOOTH, FakeDialer(CarrierKind.BLUETOOTH, cam))
        l.start()
        settle()

        // Wi-Fi goes silently dead: writes "succeed", nothing arrives, no error.
        lan.links.last().faultsA.blackhole = true
        lan.links.last().faultsB.blackhole = true
        l.send("early".toByteArray())          // vanishes into the blackhole
        advanceTimeBy(3_500); runCurrent()     // long enough to be suspect, not yet declared dead
        l.send("late".toByteArray())           // should route over the standby
        advanceTimeBy(500); runCurrent()
        assertEquals(listOf("early", "late"), cam.events.messages.toList())
    }

    @Test
    fun video_moves_to_the_standby_carrier_and_a_keyframe_is_requested() = runTest {
        val cam = CameraSide(this)
        val ev = Events()
        val lan = FakeDialer(CarrierKind.LAN, cam)
        val l = link(ev)
        l.setDialer(CarrierKind.LAN, lan)
        l.setDialer(CarrierKind.BLUETOOTH, FakeDialer(CarrierKind.BLUETOOTH, cam))
        l.start()
        settle()

        val toViewer = cam.toViewer
        val frame = ByteArray(500)
        assertTrue(toViewer.sendMedia(Stream.VIDEO, MediaFlags.KEYFRAME, 1, frame, 0, frame.size))
        settle(100)
        assertEquals(listOf(1L), ev.media.toList())

        val before = cam.events.keyRequests.get()
        lan.links.last().cut()
        settle(200)
        assertTrue("camera must be told to produce a keyframe for the new route", cam.events.keyRequests.get() > before)
        // Deltas are refused on the fresh route until a keyframe arrives: no smear.
        assertFalse(toViewer.sendMedia(Stream.VIDEO, 0, 2, frame, 0, frame.size))
        assertTrue(toViewer.sendMedia(Stream.VIDEO, MediaFlags.KEYFRAME, 3, frame, 0, frame.size))
        settle(100)
        assertEquals(listOf(1L, 3L), ev.media.toList())
    }

    @Test
    fun stop_closes_everything_and_goes_idle() = runTest {
        val cam = CameraSide(this)
        val l = link(Events())
        l.setDialer(CarrierKind.LAN, FakeDialer(CarrierKind.LAN, cam))
        l.start()
        settle()
        l.stop()
        settle(2_000)
        assertEquals(LinkState.Phase.IDLE, l.state.value.phase)
    }

    @Test
    fun a_dialer_can_be_swapped_while_running_to_follow_a_changed_address() = runTest {
        val cam = CameraSide(this)
        val old = FakeDialer(CarrierKind.LAN, cam).also { it.reachable = false }
        val l = link(Events())
        l.setDialer(CarrierKind.LAN, old)
        l.start()
        advanceTimeBy(3_000); runCurrent()
        assertEquals(LinkState.Phase.RETRYING, l.state.value.phase)

        l.setDialer(CarrierKind.LAN, FakeDialer(CarrierKind.LAN, cam)) // beacon reported a new IP
        settle(100)
        assertEquals(LinkState.Phase.UP, l.state.value.phase)
    }

    @Test
    fun strangers_cannot_exhaust_the_acceptor() = runTest {
        val cam = CameraSide(this)
        // 20 connections that never say a word.
        val silent = (1..20).map { MemoryLink() }
        silent.forEach { cam.acceptor.accept(CarrierKind.LAN, it.b) }
        // A real viewer must still get in once the silent ones are dropped (1.5 s).
        val l = link(Events())
        l.setDialer(CarrierKind.LAN, FakeDialer(CarrierKind.LAN, cam))
        l.start()
        advanceTimeBy(6_000); runCurrent()
        assertEquals(LinkState.Phase.UP, l.state.value.phase)
    }

    @Test
    fun a_message_queued_before_connecting_arrives_after_three_flights_not_five() = runTest {
        // Over a slow link every flight is what you wait for: HELLO, CHALLENGE, then FINISH together with the
        // first record. The old handshake also waited for READY before sending anything.
        val cam = CameraSide(this)
        val l = link(Events())
        l.setDialer(CarrierKind.LAN, FakeDialer(CarrierKind.LAN, cam, latencyMs = 100))
        l.send("watch".toByteArray())
        l.start()
        var t = 0
        while (cam.events.messages.isEmpty() && t < 3_000) { advanceTimeBy(10); runCurrent(); t += 10 }
        assertEquals(listOf("watch"), cam.events.messages.toList())
        assertTrue("took $t ms for a 100 ms link", t <= 450)
    }

    @Test
    fun a_wrong_key_is_still_reported_as_unpaired_even_though_the_dialer_started_before_the_answer() = runTest {
        val cam = CameraSide(this, key = GroupKey.generate())      // the camera holds a different key
        val l = link(Events())
        l.setDialer(CarrierKind.LAN, FakeDialer(CarrierKind.LAN, cam))
        l.start()
        settle(2_000)
        assertEquals(LinkState.Phase.UNPAIRED, l.state.value.phase)
        assertEquals(HandshakeFailure.AUTH, l.lastFailure)
    }

    @Test
    fun dropping_a_standby_carrier_closes_it_stops_redialing_it_and_leaves_the_best_untouched() = runTest {
        val cam = CameraSide(this)
        val l = link(Events())
        val bt = FakeDialer(CarrierKind.BLUETOOTH, cam)
        l.setDialer(CarrierKind.BT_DIRECT, FakeDialer(CarrierKind.BT_DIRECT, cam))
        l.setDialer(CarrierKind.BLUETOOTH, bt)
        l.start()
        settle()
        assertEquals(CarrierKind.BT_DIRECT, l.state.value.carrier)          // the direct socket outranks the Data Layer
        assertEquals(setOf(CarrierKind.BLUETOOTH), l.state.value.standby)

        l.dropCarrier(CarrierKind.BLUETOOTH)
        settle(3_000)
        assertEquals(LinkState.Phase.UP, l.state.value.phase)
        assertEquals(CarrierKind.BT_DIRECT, l.state.value.carrier)
        assertTrue(l.state.value.standby.isEmpty())
        assertFalse(l.hasDialer(CarrierKind.BLUETOOTH))
        val attemptsAfterDrop = bt.attempts.get()
        settle(10_000)
        assertEquals(attemptsAfterDrop, bt.attempts.get())                  // and it stays dropped
    }

    @Test
    fun a_redial_on_the_same_carrier_retires_the_stale_session_at_once() = runTest {
        // The watch's socket died without a FIN and it dialed again: the camera now has two LAN sessions
        // for one viewer. Media must go to the new one immediately, not to the zombie for the next few seconds.
        val cam = CameraSide(this)
        val first = Events()
        val l1 = link(first)
        val d1 = FakeDialer(CarrierKind.LAN, cam)
        l1.setDialer(CarrierKind.LAN, d1)
        l1.start()
        settle()
        assertEquals(1, cam.toViewer.sessions.live().size)
        d1.reachable = false   // the old process is gone; only the new dial exists

        val second = Events()
        val l2 = link(second)
        l2.setDialer(CarrierKind.LAN, FakeDialer(CarrierKind.LAN, cam))
        l2.start()
        settle()

        val live = cam.toViewer.sessions.live()
        assertEquals(1, live.size)
        cam.toViewer.sendMedia(Stream.VIDEO, MediaFlags.KEYFRAME, 7L, ByteArray(10), 0, 10)
        settle()
        assertEquals(listOf(7L), second.media.toList())
        assertTrue(first.media.isEmpty())
    }

    @Test
    fun a_throwing_listener_does_not_break_the_conversation() = runTest {
        val cam = CameraSide(this)
        val ev = object : PeerListener {
            val got = CopyOnWriteArrayList<String>()
            override fun onMessage(data: ByteArray, offset: Int, length: Int) {
                got += String(data, offset, length)
                error("boom")
            }
        }
        val l = PeerLink(camera, viewer, group, backgroundScope, Clock { testScheduler.currentTime }, ev, policy = RetryPolicy(jitter = 0.0))
        l.setDialer(CarrierKind.LAN, FakeDialer(CarrierKind.LAN, cam))
        l.start()
        settle()
        repeat(3) { cam.toViewer.send("m$it".toByteArray()) }
        settle()
        assertEquals(listOf("m0", "m1", "m2"), ev.got.toList())
        assertEquals(LinkState.Phase.UP, l.state.value.phase)
    }
}
