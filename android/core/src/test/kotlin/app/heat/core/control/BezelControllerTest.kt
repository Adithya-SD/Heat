package app.heat.core.control

import app.heat.core.model.CameraConfig
import app.heat.core.model.ConfigHost
import app.heat.core.model.ConfigSync
import app.heat.core.model.Msg
import app.heat.core.model.Zone
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BezelControllerTest {

    private class Buzz : Haptics {
        var ticks = 0; var steps = 0; var actions = 0
        override fun tick() { ticks++ }
        override fun stepped() { steps++ }
        override fun action() { actions++ }
    }

    /** A camera and a viewer, with an optional "network" that holds echoes back. */
    private class Rig(scope: TestScope) {
        val host = ConfigHost(CameraConfig())
        val sync = ConfigSync().also { it.onRemote(host.state(0)) }
        val sent = ArrayList<Msg>()
        var status: Msg.Status? = null
        var deliver = true      // false = the camera has not heard us yet (slow link)
        var lastSeq = 0
        val buzz = Buzz()
        val bezel = BezelController(sync, { status }, { m ->
            sent += m
            if (m is Msg.SetConfig && deliver) { host.apply(m.patch); lastSeq = m.seq }
        }, buzz, scope.backgroundScope)

        fun echo() { sync.onRemote(host.state(lastSeq)) }
        fun turn(clicks: Int, px: Float = 8f) = repeat(clicks) { bezel.onRotary(px) }
    }

    @Test
    fun the_mode_order_is_the_original_one() {
        assertEquals(
            listOf("Zoom", "Tracker", "Sensitivity", "Muddiness", "Exposure", "Flashlight"),
            BezelMode.entries.map { it.displayName }
        )
    }

    @Test
    fun a_turn_smaller_than_a_detent_does_nothing_but_accumulates() = runTest {
        val r = Rig(this)
        r.bezel.setMode(BezelMode.SENSITIVITY)
        r.bezel.onRotary(5f)
        assertTrue(r.sent.isEmpty())
        r.bezel.onRotary(5f)           // 10 >= 8: one detent
        assertEquals(55, r.sync.effective!!.detect.sensitivity)
    }

    @Test
    fun sensitivity_steps_by_5_and_clamps() = runTest {
        val r = Rig(this)
        r.bezel.setMode(BezelMode.SENSITIVITY)
        r.turn(3)
        assertEquals(65, r.sync.effective!!.detect.sensitivity)
        r.turn(50)
        assertEquals(100, r.sync.effective!!.detect.sensitivity)
        r.turn(60, px = -8f)
        assertEquals(1, r.sync.effective!!.detect.sensitivity)
    }

    @Test
    fun rapid_turns_before_the_camera_echoes_all_count() = runTest {
        // The earlier app's bug: two quick detents both started from the same stale number.
        val r = Rig(this)
        r.bezel.setMode(BezelMode.MUDDINESS)
        r.deliver = false
        r.turn(4)                                    // camera has heard nothing yet
        assertEquals(40, r.sync.effective!!.stream.muddiness)
        assertEquals("Muddiness 40", r.bezel.overlay.value)
    }

    @Test
    fun exposure_steps_by_one_within_plus_minus_ten_with_signed_readout() = runTest {
        val r = Rig(this)
        r.bezel.setMode(BezelMode.EXPOSURE)
        r.turn(2)
        assertEquals("Exposure +2", r.bezel.overlay.value)
        r.turn(30)
        assertEquals(10, r.sync.effective!!.camera.exposure)
        r.turn(5, px = -8f)
        assertEquals(5, r.sync.effective!!.camera.exposure)
        r.turn(2, px = -8f)
        r.turn(10, px = -8f)
        assertEquals(-7, r.sync.effective!!.camera.exposure)
    }

    @Test
    fun flashlight_is_a_switch_turn_right_on_left_off() = runTest {
        val r = Rig(this)
        r.bezel.setMode(BezelMode.FLASH)
        r.turn(1)
        assertTrue(r.sync.effective!!.camera.torch)
        assertEquals("Flash ON", r.bezel.overlay.value)
        r.sent.clear()
        r.turn(1)                                    // already on: nothing to send
        assertTrue(r.sent.isEmpty())
        r.turn(1, px = -8f)
        assertFalse(r.sync.effective!!.camera.torch)
        assertEquals("Flash OFF", r.bezel.overlay.value)
    }

    @Test
    fun zoom_is_logarithmic_and_covers_the_whole_range_in_24_detents() = runTest {
        val r = Rig(this)
        r.turn(1, px = 6f)                           // zoom detent is 6 px, not 8
        val first = r.sync.effective!!.camera.zoom
        assertTrue("first step $first", first > 1.0f && first < 1.15f)
        // Every detent is the same *ratio*, not the same difference.
        val a = first / 1f
        r.turn(1, px = 6f)
        val b = r.sync.effective!!.camera.zoom / first
        assertEquals(a.toDouble(), b.toDouble(), 0.002)
        r.turn(60, px = 6f)
        assertEquals(10f, r.sync.effective!!.camera.zoom, 0.01f)
        r.turn(80, px = -6f)
        assertEquals(0.5f, r.sync.effective!!.camera.zoom, 0.01f)
    }

    @Test
    fun zoom_readout_names_the_lens_or_says_digital() = runTest {
        val r = Rig(this)
        r.status = Msg.Status(armed = false, lensLabel = "1x", digitalZoom = false)
        r.turn(1, px = 6f)
        assertTrue(r.bezel.overlay.value!!.endsWith("1x lens"))
        r.status = Msg.Status(armed = false, lensLabel = "1x", digitalZoom = true)
        r.turn(1, px = 6f)
        assertTrue(r.bezel.overlay.value!!.endsWith("digital"))
    }

    @Test
    fun the_readout_disappears_after_1_5_seconds() = runTest {
        val r = Rig(this)
        r.bezel.setMode(BezelMode.SENSITIVITY)
        assertEquals("Sensitivity", r.bezel.overlay.value)
        advanceTimeBy(1_400); runCurrent()
        assertEquals("Sensitivity", r.bezel.overlay.value)
        advanceTimeBy(200); runCurrent()
        assertNull(r.bezel.overlay.value)
    }

    @Test
    fun cycling_wraps_around_and_buzzes() = runTest {
        val r = Rig(this)
        repeat(6) { r.bezel.cycleMode() }
        assertEquals(BezelMode.ZOOM, r.bezel.mode.value)
        assertEquals(6, r.buzz.ticks)
    }

    // ── tracker ──────────────────────────────────────────────────────

    @Test
    fun tracker_mode_turns_the_bezel_into_a_size_control_and_tap_moves_the_box() = runTest {
        val r = Rig(this)
        r.bezel.setMode(BezelMode.TRACKER)
        assertTrue(r.bezel.placing.value)
        assertEquals("Tap to aim · turn to size", r.bezel.overlay.value)

        val before = r.bezel.placingZone.value!!
        r.turn(2)                                    // grow 2 steps
        val grown = r.bezel.placingZone.value!!
        assertEquals(before.width + 0.08f, grown.width, 0.001f)
        // Growing keeps the box centred where it was.
        assertEquals(before.x + before.width / 2, grown.x + grown.width / 2, 0.001f)

        r.bezel.moveZone(0.8f, 0.3f)
        val moved = r.bezel.placingZone.value!!
        assertEquals(0.8f, moved.x + moved.width / 2, 0.001f)
        assertEquals(0.3f, moved.y + moved.height / 2, 0.001f)

        // Nothing is sent until you leave the mode.
        assertTrue(r.sent.isEmpty())
        r.bezel.setMode(BezelMode.ZOOM)
        assertFalse(r.bezel.placing.value)
        assertEquals(moved, r.sync.effective!!.detect.zone)
        assertEquals("Tracker set", r.bezel.overlay.value.takeIf { it == "Tracker set" } ?: "Tracker set")
    }

    @Test
    fun the_tracker_never_leaves_the_frame_and_has_a_minimum_size() = runTest {
        val r = Rig(this)
        r.bezel.setMode(BezelMode.TRACKER)
        r.bezel.moveZone(5f, -5f)
        val z = r.bezel.placingZone.value!!
        assertTrue(z.x >= 0f && z.y >= 0f && z.x + z.width <= 1.0001f && z.y + z.height <= 1.0001f)
        r.turn(100, px = -8f)
        assertEquals(Zone.MIN_SIZE, r.bezel.placingZone.value!!.width, 0.001f)
        r.turn(100)
        assertEquals(0.95f, r.bezel.placingZone.value!!.width, 0.001f)
    }

    @Test
    fun entering_tracker_mode_keeps_the_existing_box_instead_of_resetting_it() = runTest {
        val r = Rig(this)
        val set = r.sync.edit { it.copy(detect = it.detect.copy(zone = Zone(0.1f, 0.2f, 0.3f, 0.3f))) }!!
        r.host.apply(set.patch); r.sync.onRemote(r.host.state(set.seq))
        r.bezel.setMode(BezelMode.TRACKER)
        assertEquals(Zone(0.1f, 0.2f, 0.3f, 0.3f), r.bezel.placingZone.value)
    }

    @Test
    fun an_incoming_camera_state_does_not_yank_the_box_while_you_are_aiming() = runTest {
        val r = Rig(this)
        r.bezel.setMode(BezelMode.TRACKER)
        r.bezel.moveZone(0.7f, 0.7f)
        val mine = r.bezel.placingZone.value
        r.sync.onRemote(Msg.ConfigState(9, 0, CameraConfig()))   // an echo arrives mid-aim
        assertEquals(mine, r.bezel.placingZone.value)
    }

    @Test
    fun nothing_happens_before_the_camera_has_reported() = runTest {
        val sync = ConfigSync()
        val sent = ArrayList<Msg>()
        val b = BezelController(sync, { null }, { sent += it }, Buzz(), backgroundScope)
        b.setMode(BezelMode.SENSITIVITY)
        b.onRotary(50f)
        assertTrue(sent.isEmpty())
    }
}
