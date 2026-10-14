package app.heat.core.control

import app.heat.core.model.CameraConfig
import app.heat.core.model.ConfigHost
import app.heat.core.model.ConfigSync
import app.heat.core.model.Facing
import app.heat.core.model.Msg
import app.heat.core.model.NightMode
import app.heat.core.model.WhiteBalance
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
        var ticks = 0; var steps = 0; var actions = 0; var limits = 0
        override fun tick() { ticks++ }
        override fun stepped() { steps++ }
        override fun action() { actions++ }
        override fun limit() { limits++ }
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

        val cfg: CameraConfig get() = sync.effective!!
        fun echo() { sync.onRemote(host.state(lastSeq)) }
        fun turn(clicks: Int, per: Float = 1f) = repeat(clicks) { bezel.onDetents(per) }
        fun mode(m: BezelMode) = bezel.setMode(m)
    }

    private val everything = Msg.Status(armed = false, hasTorch = true, manualFocus = true, manualExposure = true)

    // ── the list ─────────────────────────────────────────────────────

    @Test
    fun the_first_six_modes_keep_the_original_order_and_the_new_ones_follow() {
        assertEquals(
            listOf(
                "Zoom", "Tracker", "Sensitivity", "Muddiness", "Exposure", "Flashlight",
                "Focus", "White balance", "ISO", "Shutter", "Night", "Lock exposure", "Camera"
            ),
            BezelMode.entries.map { it.displayName }
        )
    }

    @Test
    fun cycling_wraps_both_ways_and_buzzes_each_time() = runTest {
        val r = Rig(this)
        r.status = everything
        repeat(BezelMode.entries.size) { r.bezel.cycleMode() }
        assertEquals(BezelMode.ZOOM, r.bezel.mode.value)
        assertEquals(BezelMode.entries.size, r.buzz.ticks)
        r.bezel.cycleMode(-1)
        assertEquals(BezelMode.CAMERA, r.bezel.mode.value)
    }

    @Test
    fun cycling_skips_modes_this_camera_does_not_have() = runTest {
        val r = Rig(this)
        r.status = Msg.Status(armed = false)                       // no torch, no manual focus, no manual exposure
        val seen = ArrayList<BezelMode>()
        repeat(BezelMode.entries.size) { r.bezel.cycleMode(); seen += r.bezel.mode.value }
        assertFalse(BezelMode.FLASH in seen)
        assertFalse(BezelMode.FOCUS in seen)
        assertFalse(BezelMode.ISO in seen)
        assertFalse(BezelMode.SHUTTER in seen)
        assertTrue(BezelMode.EXPOSURE in seen && BezelMode.WHITE_BALANCE in seen && BezelMode.TRACKER in seen)
    }

    // ── no cooldown: every detent counts ─────────────────────────────

    @Test
    fun a_fast_spin_that_arrives_as_one_big_event_applies_every_detent_in_it() = runTest {
        val r = Rig(this)
        r.mode(BezelMode.SENSITIVITY)
        r.bezel.onDetents(3f)
        assertEquals(65, r.cfg.detect.sensitivity)
        assertEquals(1, r.sent.size)                               // one edit, not three
    }

    @Test
    fun a_stream_of_single_clicks_loses_none_even_before_the_camera_has_echoed_anything() = runTest {
        val r = Rig(this)
        r.mode(BezelMode.MUDDINESS)
        r.deliver = false                                          // slow link: the camera has heard nothing yet
        r.turn(7)
        assertEquals(70, r.cfg.stream.muddiness)
        r.turn(3)
        assertEquals(100, r.cfg.stream.muddiness)
    }

    @Test
    fun a_late_echo_in_the_middle_of_a_spin_does_not_rewind_it() = runTest {
        val r = Rig(this)
        r.mode(BezelMode.SENSITIVITY)
        r.turn(2)
        r.echo()                                                   // camera confirms the first two...
        r.turn(2)                                                  // ...while two more are still in flight
        r.deliver = true
        assertEquals(70, r.cfg.detect.sensitivity)
    }

    @Test
    fun fractions_of_a_detent_add_up_and_nothing_is_thrown_away() = runTest {
        val r = Rig(this)
        r.mode(BezelMode.SENSITIVITY)
        r.bezel.onDetents(0.4f)
        assertTrue(r.sent.isEmpty())
        r.bezel.onDetents(0.4f)
        assertTrue(r.sent.isEmpty())
        r.bezel.onDetents(0.4f)                                    // 1.2 total: one detent, 0.2 carried
        assertEquals(55, r.cfg.detect.sensitivity)
        r.bezel.onDetents(0.9f)                                    // 0.2 + 0.9 = 1.1: another
        assertEquals(60, r.cfg.detect.sensitivity)
    }

    @Test
    fun turning_back_is_felt_at_once_the_carry_from_the_other_way_is_dropped() = runTest {
        val r = Rig(this)
        r.mode(BezelMode.SENSITIVITY)
        r.bezel.onDetents(0.6f)
        r.bezel.onDetents(-0.6f)
        assertTrue(r.sent.isEmpty())
        r.bezel.onDetents(-0.6f)                                   // -1.2 since the reversal
        assertEquals(45, r.cfg.detect.sensitivity)
    }

    @Test
    fun garbage_input_is_ignored() = runTest {
        val r = Rig(this)
        r.mode(BezelMode.SENSITIVITY)
        r.bezel.onDetents(Float.NaN)
        r.bezel.onDetents(Float.POSITIVE_INFINITY)
        r.bezel.onDetents(0f)
        assertTrue(r.sent.isEmpty())
    }

    @Test
    fun the_end_of_a_range_buzzes_once_not_on_every_further_click() = runTest {
        val r = Rig(this)
        r.mode(BezelMode.SENSITIVITY)
        r.turn(30)                                                 // 50 -> 100 in ten clicks, twenty more at the stop
        assertEquals(100, r.cfg.detect.sensitivity)
        assertEquals(10, r.buzz.steps)
        assertEquals(1, r.buzz.limits)
        r.turn(2, per = -1f)
        assertEquals(90, r.cfg.detect.sensitivity)
    }

    // ── the modes ────────────────────────────────────────────────────

    @Test
    fun sensitivity_steps_by_5_muddiness_by_10_and_exposure_by_1_within_their_ranges() = runTest {
        val r = Rig(this)
        r.mode(BezelMode.SENSITIVITY); r.turn(3); assertEquals(65, r.cfg.detect.sensitivity)
        r.turn(80, per = -1f); assertEquals(1, r.cfg.detect.sensitivity)
        r.mode(BezelMode.MUDDINESS); r.turn(4); assertEquals(40, r.cfg.stream.muddiness)
        r.mode(BezelMode.EXPOSURE); r.turn(2); assertEquals(2, r.cfg.camera.exposure)
        r.turn(40); assertEquals(12, r.cfg.camera.exposure)
        r.turn(40, per = -1f); assertEquals(-12, r.cfg.camera.exposure)
    }

    @Test
    fun flashlight_is_a_switch_turn_right_on_left_off() = runTest {
        val r = Rig(this)
        r.mode(BezelMode.FLASH)
        r.turn(1)
        assertTrue(r.cfg.camera.torch)
        r.sent.clear()
        r.turn(1)                                                  // already on: nothing to send
        assertTrue(r.sent.isEmpty())
        r.turn(1, per = -1f)
        assertFalse(r.cfg.camera.torch)
    }

    @Test
    fun zoom_is_logarithmic_and_covers_the_whole_range_in_24_detents() = runTest {
        val r = Rig(this)
        r.turn(1)
        val first = r.cfg.camera.zoom
        assertTrue("first step $first", first > 1.0f && first < 1.15f)
        r.turn(1)
        assertEquals(first.toDouble(), (r.cfg.camera.zoom / first).toDouble(), 0.002)   // same ratio each detent
        r.turn(60)
        assertEquals(10f, r.cfg.camera.zoom, 0.01f)
        r.turn(80, per = -1f)
        assertEquals(0.5f, r.cfg.camera.zoom, 0.01f)
    }

    @Test
    fun zoom_names_the_lens_or_says_digital() = runTest {
        val r = Rig(this)
        r.status = Msg.Status(armed = false, lensLabel = "1x", digitalZoom = false)
        r.turn(1)
        assertTrue(r.bezel.overlay.value!!.endsWith("1x lens"))
        r.status = Msg.Status(armed = false, lensLabel = "1x", digitalZoom = true)
        r.turn(1)
        assertTrue(r.bezel.overlay.value!!.endsWith("digital"))
    }

    @Test
    fun the_new_controls_step_through_their_lists_and_stop_at_the_ends() = runTest {
        val r = Rig(this)

        r.mode(BezelMode.FOCUS)
        r.turn(1); assertEquals(0, r.cfg.camera.focus)             // auto -> far
        r.turn(3); assertEquals(15, r.cfg.camera.focus)
        r.turn(100); assertEquals(100, r.cfg.camera.focus)
        r.turn(100, per = -1f); assertEquals(-1, r.cfg.camera.focus)

        r.mode(BezelMode.WHITE_BALANCE)
        r.turn(2); assertEquals(WhiteBalance.CLOUDY, r.cfg.camera.whiteBalance)
        r.turn(9); assertEquals(WhiteBalance.FLUORESCENT, r.cfg.camera.whiteBalance)

        r.mode(BezelMode.ISO)
        r.turn(3); assertEquals(400, r.cfg.camera.iso)
        r.turn(1, per = -3f); assertEquals(0, r.cfg.camera.iso)    // back to auto

        r.mode(BezelMode.SHUTTER)
        r.turn(5); assertEquals(125, r.cfg.camera.shutterDenom)

        r.mode(BezelMode.NIGHT)
        r.turn(5); assertEquals(NightMode.NIGHT, r.cfg.camera.night)
        r.turn(1, per = -1f); assertEquals(NightMode.DAY, r.cfg.camera.night)

        r.mode(BezelMode.LOCK)
        r.turn(1); assertTrue(r.cfg.camera.lockExposure)
        r.turn(1, per = -1f); assertFalse(r.cfg.camera.lockExposure)

        r.mode(BezelMode.CAMERA)
        r.turn(1); assertEquals(Facing.FRONT, r.cfg.camera.facing)
        r.turn(1, per = -1f); assertEquals(Facing.REAR, r.cfg.camera.facing)
    }

    // ── what the menu shows ──────────────────────────────────────────

    @Test
    fun the_menu_chip_text_and_gauge_describe_every_mode() {
        val c = CameraConfig()
        assertEquals("1.0x", BezelController.describe(BezelMode.ZOOM, c))
        assertEquals("32%", BezelController.describe(BezelMode.TRACKER, c))
        assertEquals("Auto", BezelController.describe(BezelMode.FOCUS, c))
        assertEquals("Auto", BezelController.describe(BezelMode.ISO, c))
        assertEquals("Auto", BezelController.describe(BezelMode.SHUTTER, c))
        val m = c.copy(camera = c.camera.copy(exposure = 3, iso = 800, shutterDenom = 125, torch = true))
        assertEquals("+3", BezelController.describe(BezelMode.EXPOSURE, m))
        assertEquals("800", BezelController.describe(BezelMode.ISO, m))
        assertEquals("1/125", BezelController.describe(BezelMode.SHUTTER, m))
        assertEquals("On", BezelController.describe(BezelMode.FLASH, m))

        for (mode in BezelMode.entries) {
            val f = BezelController.fraction(mode, c)
            assertTrue("$mode fraction $f", f in 0f..1f)
        }
        assertEquals(0.5f, BezelController.fraction(BezelMode.EXPOSURE, c), 0.001f)
        assertEquals(0f, BezelController.fraction(BezelMode.ZOOM, c.copy(camera = c.camera.copy(zoom = 0.5f))), 0.001f)
        assertEquals(1f, BezelController.fraction(BezelMode.ZOOM, c.copy(camera = c.camera.copy(zoom = 10f))), 0.001f)
    }

    @Test
    fun the_readout_hint_disappears_by_itself() = runTest {
        val r = Rig(this)
        r.mode(BezelMode.TRACKER)
        assertTrue(r.bezel.overlay.value!!.contains("Drag"))
        advanceTimeBy(2_000); runCurrent()
        assertTrue(r.bezel.overlay.value != null)
        advanceTimeBy(400); runCurrent()
        assertNull(r.bezel.overlay.value)
    }

    // ── tracker ──────────────────────────────────────────────────────

    @Test
    fun placing_the_tracker_hides_the_buttons_and_leaving_shows_them_again() = runTest {
        val r = Rig(this)
        assertFalse(r.bezel.placing.value)
        r.mode(BezelMode.TRACKER)
        assertTrue(r.bezel.placing.value)
        r.bezel.cycleMode()
        assertFalse(r.bezel.placing.value)
    }

    @Test
    fun the_bezel_sizes_the_tracker_live_around_its_own_centre() = runTest {
        val r = Rig(this)
        r.mode(BezelMode.TRACKER)
        val before = r.cfg.detect.zone
        r.turn(2)
        val grown = r.cfg.detect.zone
        assertEquals(before.width + 0.08f, grown.width, 0.001f)
        assertEquals(before.x + before.width / 2, grown.x + grown.width / 2, 0.001f)
        assertEquals(2, r.sent.size)                               // sent as it happened: nothing to commit later
        r.turn(100, per = -1f)
        assertEquals(Zone.MIN_SIZE, r.cfg.detect.zone.width, 0.001f)
        r.turn(100)
        assertEquals(0.95f, r.cfg.detect.zone.width, 0.001f)
    }

    @Test
    fun a_finger_moves_the_tracker_live_and_it_never_leaves_the_frame() = runTest {
        val r = Rig(this)
        r.mode(BezelMode.TRACKER)
        r.bezel.moveZone(0.8f, 0.3f)
        val z = r.cfg.detect.zone
        assertEquals(0.8f, z.x + z.width / 2, 0.001f)
        assertEquals(0.3f, z.y + z.height / 2, 0.001f)

        r.bezel.moveZone(5f, -5f)
        val edge = r.cfg.detect.zone
        assertTrue(edge.x >= 0f && edge.y >= 0f && edge.right <= 1.0001f && edge.bottom <= 1.0001f)
    }

    @Test
    fun a_touch_outside_the_tracker_mode_cannot_move_the_box() = runTest {
        val r = Rig(this)
        val before = r.cfg.detect.zone
        r.bezel.moveZone(0.9f, 0.9f)
        assertEquals(before, r.cfg.detect.zone)
        assertTrue(r.sent.isEmpty())
    }

    @Test
    fun the_camera_echo_arriving_mid_aim_does_not_yank_the_box_back() = runTest {
        val r = Rig(this)
        r.mode(BezelMode.TRACKER)
        r.deliver = false
        r.bezel.moveZone(0.7f, 0.7f)
        val mine = r.cfg.detect.zone
        r.sync.onRemote(Msg.ConfigState(9, 0, CameraConfig()))     // an old echo, before our edit was applied
        assertEquals(mine, r.cfg.detect.zone)
    }

    @Test
    fun nothing_happens_before_the_camera_has_reported() = runTest {
        val sync = ConfigSync()
        val sent = ArrayList<Msg>()
        val b = BezelController(sync, { null }, { sent += it }, Buzz(), backgroundScope)
        b.setMode(BezelMode.SENSITIVITY)
        b.onDetents(5f)
        b.setMode(BezelMode.TRACKER)
        b.moveZone(0.5f, 0.5f)
        assertTrue(sent.isEmpty())
    }
}
