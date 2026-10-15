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
    fun a_room_viewer_does_not_know_the_cameras_id_and_still_connects() = runTest {
        val r = Rig(this)
        val d = FakeDialer(r.acceptor)
        // the id given here is only a local name: nothing like the camera's real one
        val c = CameraClient(DeviceId.random(), "Browser", DeviceId.random(), group, r.scope.backgroundScope, r.clock, pin = false)
        c.link.setDialer(CarrierKind.LAN, d)
        c.start()
        r.settle()
        assertEquals(LinkState.Phase.UP, c.linkState.value.phase)
        assertEquals("Porch", c.hello.value?.name)
        // while a client held to a different id is refused
        val strict = CameraClient(DeviceId.random(), "Other", DeviceId.random(), group, r.scope.backgroundScope, r.clock)
        strict.link.setDialer(CarrierKind.LAN, FakeDialer(r.acceptor))
        strict.start()
        r.settle()
        assertFalse(strict.linkState.value.phase == LinkState.Phase.UP)
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

    // ── hub: one camera relays several hosts ─────────────────────────

    @Test
    fun a_relayed_alert_carries_the_host_it_came_from_and_two_hosts_with_the_same_alert_number_are_both_heard() = runTest {
        val r = Rig(this)
        val (c, _) = r.client()
        val got = CopyOnWriteArrayList<Msg.Alert>()
        val job = backgroundScope.launch(Dispatchers.Unconfined) { c.alerts.collect { got += it } }
        r.settle()
        r.host.raiseAlert(AlertKind.MOTION, 3f, 0, "ABCD2345", "Garage")
        r.host.raiseAlert(AlertKind.MOTION, 3f, 0, "WXYZ6789", "Porch door")
        r.settle()
        assertEquals(listOf("Garage", "Porch door"), got.map { it.hostName })
        assertEquals(listOf("ABCD2345", "WXYZ6789"), got.map { it.hostId })
        job.cancel()
    }

    @Test
    fun the_host_list_reaches_a_viewer_on_connect_and_on_change_and_a_pick_comes_back() = runTest {
        val r = Rig(this)
        val picks = CopyOnWriteArrayList<Pair<String, Boolean?>>()
        r.host.onPick = { id, lock -> picks += id to lock }
        r.host.setHosts(app.heat.core.model.Msg.Hosts(listOf(app.heat.core.model.HostInfo("self", "Phone", live = true)), "self", false))
        val (c, _) = r.client()
        r.settle()
        assertEquals(listOf("Phone"), c.hosts.value?.hosts?.map { it.name })
        r.host.setHosts(app.heat.core.model.Msg.Hosts(
            listOf(app.heat.core.model.HostInfo("self", "Phone", live = true), app.heat.core.model.HostInfo("ABCD2345", "Garage", live = true)), "ABCD2345", true
        ))
        r.settle()
        assertEquals("ABCD2345", c.hosts.value?.shown)
        assertTrue(c.hosts.value?.locked == true)
        c.pickHost("self", lock = false)
        r.settle()
        assertEquals(listOf("self" to false), picks.toList())
    }

    @Test
    fun arming_goes_to_the_relayed_host_for_the_hub_receiver_and_to_this_camera_for_everyone_else() = runTest {
        val r = Rig(this)
        val d = FakeDialer(r.acceptor)
        val hubClient = CameraClient(camera, "Watch", DeviceId.random(), group, r.scope.backgroundScope, r.clock, role = app.heat.core.model.Role.HUB)
        hubClient.link.setDialer(CarrierKind.LAN, d); hubClient.start()
        val (plain, _) = r.client()
        r.settle()
        val forwarded = CopyOnWriteArrayList<Boolean>()
        r.host.armHook = { armed, _ -> forwarded += armed; true }
        hubClient.arm(true, 0)
        r.settle()
        assertEquals(listOf(true), forwarded.toList())
        assertFalse("this camera must not arm when the hub's arm was forwarded", r.host.armed.value)
        plain.arm(true, 0)
        r.settle()
        assertEquals("a browser arms the phone itself", listOf(true), forwarded.toList())
        assertTrue(r.host.armed.value)
    }

    @Test
    fun a_relayed_picture_goes_to_the_hub_receiver_only_and_the_cameras_own_to_everyone_else() = runTest {
        val r = Rig(this)
        val hubGot = AtomicInteger(); val plainGot = AtomicInteger()
        val hubClient = CameraClient(camera, "Watch", DeviceId.random(), group, r.scope.backgroundScope, r.clock, role = app.heat.core.model.Role.HUB)
        hubClient.link.setDialer(CarrierKind.LAN, FakeDialer(r.acceptor)); hubClient.videoSink = VideoSink { _, _, _, _, _ -> hubGot.incrementAndGet() }; hubClient.start()
        val (plain, _) = r.client(); plain.videoSink = VideoSink { _, _, _, _, _ -> plainGot.incrementAndGet() }
        r.settle()
        hubClient.watch(video = true); plain.watch(video = true)
        r.settle()
        val frame = ByteArray(40)
        r.host.sendVideo(MediaFlags.KEYFRAME, 1, frame, 0, frame.size, CameraHost.Audience.HUB)
        r.settle()
        assertEquals(1, hubGot.get()); assertEquals(0, plainGot.get())
        r.host.sendVideo(MediaFlags.KEYFRAME, 2, frame, 0, frame.size, CameraHost.Audience.NOT_HUB)
        r.settle()
        assertEquals(1, hubGot.get()); assertEquals(1, plainGot.get())
    }

    // ── people in the room ───────────────────────────────────────────

    @Test
    fun everyone_connected_sees_who_else_is_connected() = runTest {
        val r = Rig(this)
        val (a, _) = r.client(); val (b, _) = r.client()
        r.settle(1_500)
        assertEquals(2, a.roster.value.size)
        assertEquals(2, b.roster.value.size)
        assertEquals(setOf("Watch"), a.roster.value.map { it.name }.toSet())
    }

    @Test
    fun any_viewer_can_remove_another_who_is_told_and_turned_away_if_they_come_back() = runTest {
        val r = Rig(this)
        fun mk(id: DeviceId): CameraClient {
            val c = CameraClient(camera, "Phone", id, group, r.scope.backgroundScope, r.clock)
            c.link.setDialer(CarrierKind.LAN, FakeDialer(r.acceptor)); c.start(); return c
        }
        val ida = DeviceId.random(); val idb = DeviceId.random()
        val a = mk(ida); val b = mk(idb)
        val told = CopyOnWriteArrayList<String>()
        val job = backgroundScope.launch(Dispatchers.Unconfined) { b.kicked.collect { told += it } }
        r.settle(1_500)
        assertEquals(2, r.host.viewerCount.value)
        a.kick(idb.hex)
        r.settle(2_000)
        assertEquals("the removed viewer is told", 1, told.size)
        assertTrue("and its link is stopped", b.linkState.value.phase != LinkState.Phase.UP)
        assertEquals(1, r.host.viewerCount.value)
        // coming back with the same identity is turned away
        val b2 = mk(idb)
        r.settle(3_000)
        assertEquals(1, r.host.viewerCount.value)
        job.cancel()
    }

    @Test
    fun the_hub_receiver_edits_and_sees_the_relayed_hosts_settings_while_others_keep_this_cameras() = runTest {
        val r = Rig(this)
        val hubClient = CameraClient(camera, "Watch", DeviceId.random(), group, r.scope.backgroundScope, r.clock, role = app.heat.core.model.Role.HUB)
        hubClient.link.setDialer(CarrierKind.LAN, FakeDialer(r.acceptor)); hubClient.start()
        val (plain, _) = r.client()
        r.settle()
        var remote = CameraConfig().let { it.copy(label = "Garage", detect = it.detect.copy(sensitivity = 77)) }
        val applied = CopyOnWriteArrayList<kotlinx.serialization.json.JsonObject>()
        r.host.configProxy = object : CameraHost.ConfigProxy {
            override fun apply(patch: kotlinx.serialization.json.JsonObject) { applied += patch; remote = app.heat.core.model.ConfigPatch.apply(remote, patch) ?: remote }
            override fun current() = remote
        }
        r.host.refreshConfig()
        r.settle()
        assertEquals("Garage", hubClient.config.value?.label)
        assertEquals(CameraConfig().label, plain.config.value?.label)
        hubClient.edit { it.copy(detect = it.detect.copy(sensitivity = 33)) }
        r.settle()
        assertEquals(1, applied.size)
        assertEquals(33, hubClient.config.value?.detect?.sensitivity)
        assertEquals("this camera's own settings are untouched", CameraConfig().detect.sensitivity, r.host.config.value.detect.sensitivity)
        plain.edit { it.copy(detect = it.detect.copy(sensitivity = 12)) }
        r.settle()
        assertEquals("a browser still edits this camera", 12, r.host.config.value.detect.sensitivity)
        assertEquals(1, applied.size)
    }

    @Test
    fun removing_a_host_calls_the_platform() = runTest {
        val r = Rig(this)
        val (a, _) = r.client()
        val removed = AtomicInteger()
        r.host.onRemoved = { removed.incrementAndGet() }
        r.settle()
        a.removeHost()
        r.settle()
        assertEquals(1, removed.get())
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

    @Test
    fun a_viewer_that_joins_after_an_alert_still_hears_about_it_once() = runTest {
        val r = Rig(this)
        r.host.raiseAlert(AlertKind.MOTION, 30f)           // nobody is connected yet
        r.settle()
        val (c, d) = r.client()
        val got = CopyOnWriteArrayList<Long>()
        val job = backgroundScope.launch(Dispatchers.Unconfined) { c.alerts.collect { got += it.id } }
        r.settle(5_000)
        assertEquals("the alert from before it joined", 1, got.size)

        // A carrier change brings the session up again; the same alert must not be delivered a second time.
        d.links.last().cut()
        r.settle(8_000)
        assertEquals("still once", 1, got.size)
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
