package app.heat.core.link

import app.heat.core.testing.MemoryLink
import app.heat.core.util.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
class SessionTest {

    private val group = GroupKey.generate()
    private val camera = DeviceId.random()
    private val viewer = DeviceId.random()

    private class Recorder : SessionHandler {
        val controls = CopyOnWriteArrayList<String>()
        val media = CopyOnWriteArrayList<Triple<Int, Int, Long>>()
        val closed = CopyOnWriteArrayList<Pair<CloseReason, String?>>()
        @Volatile var dropped = 0
        val errors = CopyOnWriteArrayList<Throwable>()
        override fun onControl(data: ByteArray, offset: Int, length: Int) {
            controls += String(data, offset, length)
        }
        override fun onMedia(stream: Int, flags: Int, ptsUs: Long, data: ByteArray, offset: Int, length: Int) {
            media += Triple(stream, flags, ptsUs)
        }
        override fun onMediaDropped(stream: Int) { dropped++ }
        override fun onClosed(reason: CloseReason, detail: String?) { closed += reason to detail }
        override fun onHandlerError(error: Throwable) { errors += error }
    }

    private class Pair2(val viewer: Session, val camera: Session, val vr: Recorder, val cr: Recorder)

    private suspend fun TestScope.connect(
        link: MemoryLink = MemoryLink(),
        config: SessionConfig = SessionConfig()
    ): Pair2 {
        val clock = Clock { testScheduler.currentTime }
        val serverDeferred = async { Handshake.server(link.b, camera, group) }
        val clientResult = Handshake.client(link.a, viewer, group, expect = camera)
        val serverResult = serverDeferred.await()
        val vr = Recorder()
        val cr = Recorder()
        val v = Session(camera, true, link.a, clientResult, vr, config, clock)
        val c = Session(viewer, false, link.b, serverResult, cr, config, clock)
        v.start(backgroundScope)
        c.start(backgroundScope)
        return Pair2(v, c, vr, cr)
    }

    @Test
    fun handshake_identifies_both_peers() = runTest {
        val link = MemoryLink()
        val server = async { Handshake.server(link.b, camera, group) }
        val client = Handshake.client(link.a, viewer, group)
        assertEquals(camera, client.peer)
        assertEquals(viewer, server.await().peer)
    }

    @Test
    fun wrong_group_key_fails_with_AUTH_on_both_sides() = runTest {
        val link = MemoryLink()
        val server = async { runCatching { Handshake.server(link.b, camera, group) } }
        val client = runCatching { Handshake.client(link.a, viewer, GroupKey.generate()) }
        val c = client.exceptionOrNull() as HandshakeException
        val s = server.await().exceptionOrNull() as HandshakeException
        assertEquals(HandshakeFailure.AUTH, c.failure)
        assertEquals(HandshakeFailure.AUTH, s.failure)
    }

    @Test
    fun pinned_peer_identity_is_enforced() = runTest {
        val link = MemoryLink()
        val server = async { runCatching { Handshake.server(link.b, camera, group) } }
        val client = runCatching { Handshake.client(link.a, viewer, group, expect = DeviceId.random()) }
        assertEquals(HandshakeFailure.AUTH, (client.exceptionOrNull() as HandshakeException).failure)
        server.await()
    }

    @Test
    fun handshake_survives_one_byte_at_a_time_delivery() = runTest {
        val link = MemoryLink()
        link.faultsA.maxChunk = 1
        link.faultsB.maxChunk = 1
        val server = async { Handshake.server(link.b, camera, group) }
        val client = Handshake.client(link.a, viewer, group)
        assertEquals(camera, client.peer)
        server.await()
    }

    @Test
    fun silent_server_times_out_instead_of_hanging() = runTest {
        val link = MemoryLink()
        val result = runCatching { Handshake.client(link.a, viewer, group, timeoutMs = 1_000) }
        val e = result.exceptionOrNull() as HandshakeException
        assertEquals(HandshakeFailure.TIMEOUT, e.failure)
    }

    @Test
    fun garbage_before_handshake_is_a_protocol_error_not_a_crash() = runTest {
        val link = MemoryLink()
        val server = async { runCatching { Handshake.server(link.b, camera, group) } }
        val junk = ByteArray(64) { 0x7F }
        link.a.write(junk, 0, junk.size)
        val e = server.await().exceptionOrNull() as HandshakeException
        assertTrue(e.failure == HandshakeFailure.PROTOCOL || e.failure == HandshakeFailure.TIMEOUT)
    }

    @Test
    fun control_messages_arrive_in_order() = runTest {
        val p = connect()
        repeat(500) { p.viewer.sendControl("m$it".toByteArray()) }
        advanceTimeBy(500)
        runCurrent()
        assertEquals(500, p.cr.controls.size)
        assertEquals((0 until 500).map { "m$it" }, p.cr.controls.toList())
    }

    @Test
    fun ordering_and_integrity_survive_one_byte_chunks() = runTest {
        val link = MemoryLink()
        link.faultsA.maxChunk = 1
        link.faultsB.maxChunk = 3
        val p = connect(link)
        repeat(50) { p.viewer.sendControl("hello-$it".toByteArray()) }
        repeat(50) { p.camera.sendControl("back-$it".toByteArray()) }
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals((0 until 50).map { "hello-$it" }, p.cr.controls.toList())
        assertEquals((0 until 50).map { "back-$it" }, p.vr.controls.toList())
    }

    @Test
    fun a_flipped_bit_kills_the_session_as_CORRUPT_and_delivers_nothing_wrong() = runTest {
        val link = MemoryLink()
        val p = connect(link)
        p.viewer.sendControl("good".toByteArray())
        advanceTimeBy(100); runCurrent()
        assertEquals(listOf("good"), p.cr.controls.toList())

        // Corrupt the next byte the viewer writes (inside the next record).
        link.faultsA.flipAtByte = link.faultsA.written + 8
        p.viewer.sendControl("tampered".toByteArray())
        advanceTimeBy(100); runCurrent()
        assertEquals(listOf("good"), p.cr.controls.toList())
        assertEquals(CloseReason.CORRUPT, p.cr.closed.single().first)
    }

    @Test
    fun cutting_the_link_closes_both_sides_exactly_once() = runTest {
        val link = MemoryLink()
        val p = connect(link)
        link.cut()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, p.vr.closed.size)
        assertEquals(1, p.cr.closed.size)
        assertFalse(p.viewer.sendControl("x".toByteArray()))
    }

    @Test
    fun silent_partition_is_detected_by_timeout() = runTest {
        val link = MemoryLink()
        val p = connect(link)
        advanceTimeBy(3_000); runCurrent()
        assertTrue(p.vr.closed.isEmpty())

        link.faultsA.blackhole = true
        link.faultsB.blackhole = true
        advanceTimeBy(8_000); runCurrent()
        assertEquals(CloseReason.TIMEOUT, p.vr.closed.single().first)
        assertEquals(CloseReason.TIMEOUT, p.cr.closed.single().first)
    }

    @Test
    fun healthy_idle_session_stays_up_via_pings_and_measures_rtt() = runTest {
        val link = MemoryLink()
        link.faultsA.latencyMs = 20
        link.faultsB.latencyMs = 20
        val p = connect(link)
        advanceTimeBy(60_000); runCurrent()
        assertTrue(p.vr.closed.isEmpty() && p.cr.closed.isEmpty())
        assertTrue("rtt=${p.viewer.rttMs}", p.viewer.rttMs in 30..80)
    }

    @Test
    fun a_stuck_write_is_detected_even_while_the_peer_keeps_talking() = runTest {
        val link = MemoryLink(capacityChunks = 4)
        val p = connect(link)
        link.faultsA.stalled = true   // viewer can no longer write
        p.viewer.sendControl("x".toByteArray())
        advanceTimeBy(8_000); runCurrent()
        assertEquals(CloseReason.TIMEOUT, p.vr.closed.single().first)
    }

    @Test
    fun graceful_close_tells_the_peer() = runTest {
        val p = connect()
        p.viewer.close()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(CloseReason.LOCAL, p.vr.closed.single().first)
        assertEquals(CloseReason.PEER_BYE, p.cr.closed.single().first)
    }

    @Test
    fun a_throwing_handler_does_not_kill_the_session() = runTest {
        val link = MemoryLink()
        val clock = Clock { testScheduler.currentTime }
        val s = async { Handshake.server(link.b, camera, group) }
        val cr = Handshake.client(link.a, viewer, group)
        val sr = s.await()
        val explosive = object : SessionHandler {
            var seen = 0
            var errors = 0
            override fun onControl(data: ByteArray, offset: Int, length: Int) { seen++; error("boom") }
            override fun onClosed(reason: CloseReason, detail: String?) { fail("must not close") }
            override fun onHandlerError(error: Throwable) { errors++ }
        }
        val v = Session(camera, true, link.a, cr, Recorder(), SessionConfig(), clock)
        val c = Session(viewer, false, link.b, sr, explosive, SessionConfig(), clock)
        v.start(backgroundScope); c.start(backgroundScope)
        repeat(5) { v.sendControl(byteArrayOf(1)) }
        advanceTimeBy(500); runCurrent()
        assertEquals(5, explosive.seen)
        assertEquals(5, explosive.errors)
        assertTrue(c.isOpen)
    }

    @Test
    fun control_queue_overflow_ends_the_session_instead_of_growing_forever() = runTest {
        val link = MemoryLink(capacityChunks = 2)
        val p = connect(link, SessionConfig(controlQueue = 16))
        link.faultsA.stalled = true
        var accepted = 0
        repeat(100) { if (p.viewer.sendControl(ByteArray(8))) accepted++ }
        advanceTimeBy(100); runCurrent()
        assertTrue(accepted < 100)
        assertEquals(CloseReason.OVERLOAD, p.vr.closed.single().first)
    }

    // ── media ────────────────────────────────────────────────────────

    @Test
    fun media_delivers_keyframes_and_deltas_in_order_when_the_link_keeps_up() = runTest {
        val p = connect()
        val frame = ByteArray(1000)
        for (i in 0 until 30) {
            val flags = if (i % 10 == 0) MediaFlags.KEYFRAME else 0
            p.viewer.sendMedia(Stream.VIDEO, flags, i * 33_000L, frame, 0, frame.size)
            advanceTimeBy(33); runCurrent()
        }
        advanceTimeBy(500); runCurrent()
        assertEquals((0 until 30).map { it * 33_000L }, p.cr.media.map { it.third })
        assertEquals(0, p.cr.dropped)
    }

    @Test
    fun a_slow_link_never_delivers_a_delta_whose_predecessor_was_dropped() = runTest {
        val link = MemoryLink(capacityChunks = 2)
        val p = connect(link)
        link.faultsA.stalled = true // writer is stuck on the first frame; everything after piles up

        val frame = ByteArray(2000)
        // key, then deltas faster than the link can drain
        for (i in 0 until 24) {
            val flags = if (i == 0 || i == 17) MediaFlags.KEYFRAME else 0
            p.viewer.sendMedia(Stream.VIDEO, flags, i.toLong(), frame, 0, frame.size)
            advanceTimeBy(5); runCurrent()
        }
        assertTrue("encoder must be asked for a keyframe", p.vr.dropped > 0)

        link.faultsA.stalled = false
        advanceTimeBy(1_000); runCurrent()

        // Whatever arrived must form a valid chain: every delta's predecessor chain starts at a delivered keyframe,
        // and nothing between two delivered frames was skipped without a keyframe in between.
        val got = p.cr.media.map { it.third.toInt() to (it.second and MediaFlags.KEYFRAME != 0) }
        assertTrue("some frames must get through", got.isNotEmpty())
        var last = -1
        for ((idx, key) in got) {
            if (!key) assertTrue("delta $idx with no keyframe before it", last >= 0)
            if (!key) assertEquals("gap before delta $idx", last + 1, idx)
            last = idx
        }
    }

    @Test
    fun a_keyframe_written_slowly_does_not_break_the_chain_it_started() = runTest {
        // The old single slot broke here: while a big keyframe is being written, the next frames arrive and used to
        // collide with each other. A short queue holds them in order, so the picture never freezes.
        val link = MemoryLink(capacityChunks = 2)
        val p = connect(link)
        link.faultsA.stalled = true
        val big = ByteArray(2000)
        p.viewer.sendMedia(Stream.VIDEO, MediaFlags.KEYFRAME, 0L, big, 0, big.size)
        advanceTimeBy(5); runCurrent()
        for (i in 1..5) {
            assertTrue(p.viewer.sendMedia(Stream.VIDEO, 0, i * 33_000L, big, 0, big.size))
            advanceTimeBy(33); runCurrent()
        }
        assertFalse("no break, so no keyframe request", p.viewer.awaitingKeyframe)
        assertEquals(0, p.vr.dropped)
        link.faultsA.stalled = false
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf(0L) + (1..5).map { it * 33_000L }, p.cr.media.map { it.third })
    }

    @Test
    fun video_resumes_after_a_drop_as_soon_as_a_keyframe_is_offered() = runTest {
        val link = MemoryLink(capacityChunks = 2)
        val p = connect(link)
        val frame = ByteArray(500)
        link.faultsA.stalled = true
        p.viewer.sendMedia(Stream.VIDEO, MediaFlags.KEYFRAME, 1, frame, 0, frame.size)
        advanceTimeBy(20); runCurrent()
        // The writer is stuck on the keyframe; deltas queue in order (up to the cap) and the next one breaks the chain.
        for (i in 2L..11L) assertTrue(p.viewer.sendMedia(Stream.VIDEO, 0, i, frame, 0, frame.size))
        assertFalse(p.viewer.awaitingKeyframe)
        p.viewer.sendMedia(Stream.VIDEO, 0, 12, frame, 0, frame.size) // chain broken here
        assertTrue(p.viewer.awaitingKeyframe)
        assertFalse(p.viewer.sendMedia(Stream.VIDEO, 0, 13, frame, 0, frame.size)) // refused
        link.faultsA.stalled = false
        assertTrue(p.viewer.sendMedia(Stream.VIDEO, MediaFlags.KEYFRAME, 5, frame, 0, frame.size))
        assertFalse(p.viewer.awaitingKeyframe)
        advanceTimeBy(500); runCurrent()
        assertTrue(p.cr.media.any { it.third == 5L })
    }

    @Test
    fun control_is_not_starved_by_saturating_video() = runTest {
        val link = MemoryLink(capacityChunks = 8)
        link.faultsA.latencyMs = 5
        val p = connect(link)
        val big = ByteArray(60_000)
        var t = 0L
        for (i in 0 until 20) {
            p.viewer.sendMedia(Stream.VIDEO, if (i == 0) MediaFlags.KEYFRAME else 0, t++, big, 0, big.size)
            if (i == 10) p.viewer.sendControl("disarm".toByteArray())
            advanceTimeBy(2); runCurrent()
        }
        advanceTimeBy(100); runCurrent()
        assertEquals(listOf("disarm"), p.cr.controls.toList())
    }

    @Test
    fun sessions_work_in_both_directions_at_once() = runTest {
        val p = connect()
        p.viewer.sendControl("to-camera".toByteArray())
        p.camera.sendControl("to-viewer".toByteArray())
        p.camera.sendMedia(Stream.VIDEO, MediaFlags.KEYFRAME, 7, ByteArray(10), 0, 10)
        advanceTimeBy(100); runCurrent()
        assertEquals(listOf("to-camera"), p.cr.controls.toList())
        assertEquals(listOf("to-viewer"), p.vr.controls.toList())
        assertNotNull(p.vr.media.singleOrNull())
    }
}
