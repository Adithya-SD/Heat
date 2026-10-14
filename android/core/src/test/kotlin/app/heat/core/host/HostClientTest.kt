package app.heat.core.host

import app.heat.core.link.Acceptor
import app.heat.core.link.ByteDuplex
import app.heat.core.link.CarrierKind
import app.heat.core.link.DeviceId
import app.heat.core.link.Dialer
import app.heat.core.link.GroupKey
import app.heat.core.link.LinkState
import app.heat.core.link.MediaFlags
import app.heat.core.model.AlertKind
import app.heat.core.model.CameraConfig
import app.heat.core.model.Msg
import app.heat.core.model.Tier
import app.heat.core.testing.MemoryLink
import app.heat.core.util.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** A whole camera and its viewers talking to each other in virtual time, with a fake lens behind the camera. */
@OptIn(ExperimentalCoroutinesApi::class)
class HostClientTest {
    private val group = GroupKey.generate()
    private val camera = DeviceId.random()

    private class FakeBackend : CameraBackend {
        val configs = CopyOnWriteArrayList<Pair<CameraConfig, CameraConfig?>>()
        val armed = CopyOnWriteArrayList<Boolean>()
        val streaming = CopyOnWriteArrayList<Triple<Boolean, Boolean, Tier>>()
        val keyframes = AtomicInteger()
        val snaps = AtomicInteger()
        val identifies = AtomicInteger()
        override fun onConfig(new: CameraConfig, old: CameraConfig?) { configs += new to old }
        override fun onArmed(armed: Boolean) { this.armed += armed }
        override fun onStreaming(video: Boolean, audio: Boolean, tier: Tier) { streaming += Triple(video, audio, tier) }
        override fun requestKeyframe() { keyframes.incrementAndGet() }
        override fun snap() { snaps.incrementAndGet() }
        override fun identify() { identifies.incrementAndGet() }
    }

    private inner class FakeDialer(private val acceptor: Acceptor) : Dialer {
        override val kind = CarrierKind.LAN
        val links = CopyOnWriteArrayList<MemoryLink>()
        @Volatile var reachable = true
        override suspend fun dial(): ByteDuplex {
            if (!reachable) throw IOException("unreachable")
            val l = MemoryLink()
            links += l
            acceptor.accept(kind, l.b)
            return l.a
        }
    }

    private inner class Rig(val scope: TestScope) {
        val clock = Clock { scope.testScheduler.currentTime }
        val backend = FakeBackend()
        val persisted = CopyOnWriteArrayList<Long>()
        val host = CameraHost({ "Porch" }, scope.backgroundScope, clock, CameraConfig(), 0, backend, persist = { _, rev -> persisted += rev })
        val acceptor = Acceptor(camera, group, scope.backgroundScope, handler = host.acceptHandler())

        fun client(): Pair<CameraClient, FakeDialer> {
            val d = FakeDialer(acceptor)
            val c = CameraClient(camera, "Watch", DeviceId.random(), group, scope.backgroundScope, clock)
            c.link.setDialer(CarrierKind.LAN, d)
            c.start()
            return c to d
        }

        fun settle(ms: Long = 400) { scope.advanceTimeBy(ms); scope.runCurrent() }
    }

    @Test
    fun a_viewer_receives_hello_config_and_status_on_connect() = runTest {
        val r = Rig(this)
        val (c, _) = r.client()
        r.settle()
        assertEquals("Porch", c.hello.value?.name)
        assertEquals(CameraConfig(), c.config.value)
        assertNotNull(c.status.value)
        assertEquals(LinkState.Phase.UP, c.linkState.value.phase)
        assertEquals(1, r.host.viewerCount.value)
    }

    @Test
    fun an_edit_reaches_the_camera_its_backend_and_storage_then_settles() = runTest {
        val r = Rig(this)
        val (c, _) = r.client()
        r.settle()
        c.edit { it.copy(detect = it.detect.copy(sensitivity = 80)) }
        assertEquals(80, c.config.value!!.detect.sensitivity)   // instantly, before any network
        r.settle()
        assertEquals(80, r.host.config.value.detect.sensitivity)
        val (new, old) = r.backend.configs.last()
        assertEquals(80, new.detect.sensitivity)
        assertEquals(50, old!!.detect.sensitivity)
        assertTrue(r.persisted.isNotEmpty())
        assertEquals(80, c.config.value!!.detect.sensitivity)
    }

    @Test
    fun a_slider_drag_ends_on_the_last_value_everywhere() = runTest {
        val r = Rig(this)
        val (c, _) = r.client()
        r.settle()
        for (v in 10..90 step 2) {
            c.edit { it.copy(detect = it.detect.copy(sensitivity = v)) }
            r.settle(15)
        }
        r.settle(500)
        assertEquals(90, r.host.config.value.detect.sensitivity)
        assertEquals(90, c.config.value!!.detect.sensitivity)
    }

    @Test
    fun a_change_made_on_the_camera_itself_shows_up_on_the_viewer() = runTest {
        val r = Rig(this)
        val (c, _) = r.client()
        r.settle()
        r.host.editLocally { it.copy(label = "Garage") }
        r.settle()
        assertEquals("Garage", c.config.value!!.label)
    }

    @Test
    fun two_viewers_see_each_others_changes() = runTest {
        val r = Rig(this)
        val (a, _) = r.client()
        val (b, _) = r.client()
        r.settle()
        a.edit { it.copy(label = "From A") }
        r.settle()
        assertEquals("From A", b.config.value!!.label)
        b.edit { it.copy(detect = it.detect.copy(cooldownSec = 33)) }
        r.settle()
        assertEquals(33, a.config.value!!.detect.cooldownSec)
        assertEquals("From A", a.config.value!!.label)
    }

    // ── arming ───────────────────────────────────────────────────────

    @Test
    fun arming_counts_down_then_arms_and_everyone_is_told() = runTest {
        val r = Rig(this)
        val (c, _) = r.client()
        r.settle()
        c.arm(true, delaySec = 5)
        r.settle(1_500)
        assertTrue("armingIn=${c.status.value!!.armingIn}", c.status.value!!.armingIn in 1..5)
        assertFalse(c.status.value!!.armed)
        assertTrue(r.backend.armed.isEmpty())
        r.settle(5_000)
        assertTrue(c.status.value!!.armed)
        assertEquals(listOf(true), r.backend.armed.toList())
        assertTrue(r.host.armed.value)
    }

    @Test
    fun disarming_during_the_countdown_cancels_it() = runTest {
        val r = Rig(this)
        val (c, _) = r.client()
        r.settle()
        c.arm(true, delaySec = 10)
        r.settle(2_000)
        c.arm(false)
        r.settle(15_000)
        assertFalse(c.status.value!!.armed)
        assertTrue(r.backend.armed.isEmpty())
    }

    @Test
    fun arm_with_no_delay_is_immediate_and_disarm_stops_detection() = runTest {
        val r = Rig(this)
        val (c, _) = r.client()
        r.settle()
        c.arm(true, delaySec = 0)
        r.settle()
        c.arm(false)
        r.settle()
        assertEquals(listOf(true, false), r.backend.armed.toList())
    }

    // ── video ────────────────────────────────────────────────────────

    @Test
    fun watching_starts_the_stream_and_asks_for_a_keyframe_and_stopping_stops_it() = runTest {
        val r = Rig(this)
        val (c, _) = r.client()
        val got = CopyOnWriteArrayList<Long>()
        c.videoSink = VideoSink { _, pts, _, _, _ -> got += pts }
        r.settle()
        c.watch(video = true, tier = Tier.HIGH)
        r.settle()
        assertEquals(Triple(true, false, Tier.HIGH), r.backend.streaming.last())
        assertTrue(r.backend.keyframes.get() >= 1)

        val frame = ByteArray(2000)
        assertEquals(1, r.host.sendVideo(MediaFlags.KEYFRAME, 100, frame, 0, frame.size))
        r.settle()
        assertEquals(listOf(100L), got.toList())

        c.watch(video = false)
        r.settle()
        assertEquals(Triple(false, false, Tier.MAX), r.backend.streaming.last())
        assertEquals(0, r.host.sendVideo(MediaFlags.KEYFRAME, 101, frame, 0, frame.size))
    }

    @Test
    fun a_viewer_that_vanishes_stops_costing_the_camera_anything() = runTest {
        val r = Rig(this)
        val (c, d) = r.client()
        r.settle()
        c.watch(video = true)
        r.settle()
        assertTrue(r.backend.streaming.last().first)
        d.reachable = false
        d.links.last().cut()
        r.settle(3_000)
        assertFalse("camera must stop encoding when nobody is watching", r.backend.streaming.last().first)
    }

    @Test
    fun the_stream_tier_is_the_most_modest_of_everyone_watching() = runTest {
        val r = Rig(this)
        val (a, _) = r.client()
        val (b, _) = r.client()
        r.settle()
        a.watch(video = true, tier = Tier.MAX)
        b.watch(video = true, tier = Tier.LOW)
        r.settle()
        assertEquals(Tier.LOW, r.backend.streaming.last().third)
    }

    @Test
    fun keyframe_requests_are_rate_limited() = runTest {
        val r = Rig(this)
        val (c, _) = r.client()
        r.settle()
        val before = r.backend.keyframes.get()
        repeat(20) { c.needKeyframe() }
        r.settle(100)
        assertTrue("keyframes=${r.backend.keyframes.get() - before}", r.backend.keyframes.get() - before <= 2)
    }

    // ── alerts ───────────────────────────────────────────────────────

    @Test
    fun an_alert_reaches_the_viewer() = runTest {
        val r = Rig(this)
        val (c, _) = r.client()
        val got = CopyOnWriteArrayList<Msg.Alert>()
        val job = backgroundScope.launch(Dispatchers.Unconfined) { c.alerts.collect { got += it } }
        r.settle()
        r.host.raiseAlert(AlertKind.MOTION, 12.5f, 1)
        r.settle()
        assertEquals(1, got.size)
        assertEquals(AlertKind.MOTION, got.single().kind)
        job.cancel()
    }

    @Test
    fun an_alert_raised_while_the_viewer_is_disconnected_arrives_exactly_once_on_return() = runTest {
        val r = Rig(this)
        val (c, d) = r.client()
        val got = CopyOnWriteArrayList<Long>()
        val job = backgroundScope.launch(Dispatchers.Unconfined) { c.alerts.collect { got += it.id } }
        r.settle()

        d.reachable = false
        d.links.last().cut()
        r.settle(500)
        r.host.raiseAlert(AlertKind.MOTION)
        r.host.raiseAlert(AlertKind.TAMPER)
        r.settle(3_000)
        assertTrue(got.isEmpty())

        d.reachable = true
        c.link.nudge()
        r.settle(8_000)
        assertEquals("both alerts, once each", 2, got.size)
        assertEquals(got.sorted(), got.toList())
        job.cancel()
    }

    // ── robustness ───────────────────────────────────────────────────

    @Test
    fun garbage_and_unknown_messages_from_a_viewer_are_ignored() = runTest {
        val r = Rig(this)
        val (c, _) = r.client()
        r.settle()
        c.link.send("not json at all".toByteArray())
        c.link.send("""{"t":"quantum-flux","x":1}""".toByteArray())
        c.link.send(ByteArray(0))
        r.settle()
        c.edit { it.copy(label = "still alive") }
        r.settle()
        assertEquals("still alive", r.host.config.value.label)
    }

    @Test
    fun snap_and_identify_reach_the_backend() = runTest {
        val r = Rig(this)
        val (c, _) = r.client()
        r.settle()
        c.snap(); c.identify()
        r.settle()
        assertEquals(1, r.backend.snaps.get())
        assertEquals(1, r.backend.identifies.get())
    }

    @Test
    fun a_keyframe_asked_for_inside_the_quiet_window_is_delayed_not_lost() = runTest {
        val r = Rig(this)
        val (c, _) = r.client()
        r.settle(1_000)
        val base = r.backend.keyframes.get()              // the link coming up already asked for one
        c.watch(video = true)
        r.settle(50)
        assertEquals(base + 1, r.backend.keyframes.get()) // attaching forces one at once

        // The decoder came up a moment later and asked again: inside the 300 ms window.
        c.needKeyframe(); c.needKeyframe(); c.needKeyframe()
        r.settle(50)
        assertEquals(base + 1, r.backend.keyframes.get()) // not a storm...
        r.settle(400)
        assertEquals(base + 2, r.backend.keyframes.get()) // ...but the viewer is not left without a picture

        r.settle(400)                                     // the delayed keyframe opened a new window; let it pass
        c.needKeyframe()
        r.settle(20)
        assertEquals(base + 3, r.backend.keyframes.get()) // outside the window it is immediate again
    }

    @Test
    fun a_new_picture_size_reaches_viewers_at_once_but_a_bitrate_change_does_not_chatter() = runTest {
        val r = Rig(this)
        val (c, _) = r.client()
        r.settle()
        r.host.updateStatus { it.copy(stream = app.heat.core.model.StreamInfo(640, 480, 30, 1_000_000, "avc", 90)) }
        r.settle(60)                                       // far less than the 1 s status tick
        assertEquals(640, c.status.value!!.stream!!.width)

        r.host.updateStatus { it.copy(stream = it.stream!!.copy(bitrate = 500_000)) }
        r.settle(60)
        assertEquals(1_000_000, c.status.value!!.stream!!.bitrate)   // bitrate rides the periodic tick

        r.host.updateStatus { it.copy(stream = app.heat.core.model.StreamInfo(320, 240, 15, 300_000, "avc", 90)) }
        r.settle(60)
        assertEquals(320, c.status.value!!.stream!!.width)
    }

    @Test
    fun a_viewer_cannot_be_confused_with_another_cameras_settings() = runTest {
        // Two cameras, one viewer object each: edits to one never appear on the other.
        val r1 = Rig(this)
        val r2 = Rig(this)
        val (c1, _) = r1.client()
        val (c2, _) = r2.client()
        r1.settle(); r2.settle()
        c1.edit { it.copy(label = "Camera One") }
        r1.settle(); r2.settle()
        assertEquals("Camera One", r1.host.config.value.label)
        assertEquals("Camera", r2.host.config.value.label)
        assertEquals("Camera", c2.config.value!!.label)
    }
}
