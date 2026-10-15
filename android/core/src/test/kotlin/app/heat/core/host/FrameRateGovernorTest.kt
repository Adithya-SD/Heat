package app.heat.core.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRateGovernorTest {
    private val px = 640 * 480

    @Test
    fun a_clear_road_earns_sixty_frames_after_a_short_wait() {
        val g = FrameRateGovernor()
        var t = 0L
        repeat(20) { g.update(6_000_000, px, 60, t); t += 1000 }
        assertEquals(60, g.fps)
    }

    @Test
    fun a_narrowing_road_drops_frames_at_once_and_in_steps() {
        val g = FrameRateGovernor()
        g.reset(60)
        assertEquals(60, g.update(6_000_000, px, 60, 0))
        // 640x480 at 30 fps needs about 640 kbps at 0.07 bits/pixel; 1.2 Mbps no longer pays for 60 but does for 45.
        assertEquals(45, g.update(1_000_000, px, 60, 1000))
        assertEquals(15, g.update(350_000, px, 60, 2000))
        assertEquals(5, g.update(60_000, px, 60, 3000))
    }

    @Test
    fun a_brief_recovery_does_not_climb_back_but_a_sustained_one_does() {
        val g = FrameRateGovernor()
        g.reset(15)
        var t = 0L
        repeat(3) { g.update(3_000_000, px, 60, t); t += 1000 }
        assertEquals(15, g.fps)                       // 3 s of good road is not yet enough
        repeat(3) { g.update(3_000_000, px, 60, t); t += 1000 }
        assertEquals(20, g.fps)                       // one tier at a time
        repeat(40) { g.update(3_000_000, px, 60, t); t += 1000 }
        assertEquals(60, g.fps)               // 3 Mbps affords 60 fps at 640x480 with margin
    }

    @Test
    fun the_camera_s_own_limit_is_respected() {
        val g = FrameRateGovernor()
        var t = 0L
        repeat(30) { g.update(6_000_000, px, 30, t); t += 1000 }
        assertEquals(30, g.fps)
    }

    @Test
    fun a_small_picture_stays_smooth_on_a_slow_link() {
        val g = FrameRateGovernor()
        g.reset(30)
        // 320x240 at 30 fps needs 161 kbps at 0.07, so a 250 kbps Bluetooth link still carries 30.
        assertTrue(g.update(250_000, 320 * 240, 30, 0) == 30)
    }
}
