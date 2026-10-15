package app.heat.core.host

import app.heat.core.link.DeviceId
import app.heat.core.model.AlertKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowMotionTest {
    private val a = DeviceId.random()
    private val b = DeviceId.random()
    private val c = DeviceId.random()

    @Test
    fun motion_on_another_camera_puts_it_on_screen() {
        assertTrue(FollowMotion().shouldSwitch(true, a, b, AlertKind.MOTION, 10_000))
    }

    @Test
    fun the_camera_already_shown_never_switches_to_itself() {
        assertFalse(FollowMotion().shouldSwitch(true, a, a, AlertKind.MOTION, 10_000))
    }

    @Test
    fun it_can_be_switched_off_and_only_movement_counts() {
        assertFalse(FollowMotion().shouldSwitch(false, a, b, AlertKind.MOTION, 10_000))
        for (k in listOf(AlertKind.LOW_BATTERY, AlertKind.OVERHEAT, AlertKind.CAMERA_ERROR, AlertKind.TAMPER)) {
            assertFalse("$k", FollowMotion().shouldSwitch(true, a, b, k, 10_000))
        }
    }

    @Test
    fun two_cameras_alerting_together_do_not_make_the_picture_dance() {
        val f = FollowMotion(dwellMs = 2_500)
        assertTrue(f.shouldSwitch(true, a, b, AlertKind.MOTION, 10_000))
        assertFalse(f.shouldSwitch(true, b, c, AlertKind.MOTION, 10_400))     // too soon after the last jump
        assertTrue(f.shouldSwitch(true, b, c, AlertKind.MOTION, 12_600))      // after the dwell, it may
    }

    @Test
    fun choosing_by_hand_is_respected_for_a_moment() {
        val f = FollowMotion(dwellMs = 2_500)
        f.noteManualSwitch(10_000)
        assertFalse(f.shouldSwitch(true, a, b, AlertKind.MOTION, 11_000))
        assertTrue(f.shouldSwitch(true, a, b, AlertKind.MOTION, 12_600))
    }
}
