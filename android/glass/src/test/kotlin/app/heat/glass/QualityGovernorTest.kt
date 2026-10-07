package app.heat.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QualityGovernorTest {

    private fun QualityGovernor.feed(frames: Int, jankEvery: Int): Int? {
        var last: Int? = null
        repeat(frames) { i -> onFrame(jankEvery > 0 && i % jankEvery == 0)?.let { last = it } }
        return last
    }

    @Test
    fun smooth_frames_never_lower_quality() {
        val g = QualityGovernor(maxQuality = 2)
        assertNull(g.feed(900, jankEvery = 0))
        assertEquals(2, g.quality)
    }

    @Test
    fun sustained_jank_steps_down_one_tier_per_window() {
        val g = QualityGovernor(maxQuality = 2)
        assertEquals(1, g.feed(90, jankEvery = 3))
        assertEquals(0, g.feed(90, jankEvery = 3))
        assertNull(g.feed(90, jankEvery = 3)) // already at the floor
        assertEquals(0, g.quality)
    }

    @Test
    fun an_occasional_hitch_is_tolerated() {
        val g = QualityGovernor(maxQuality = 2)
        assertNull(g.feed(900, jankEvery = 30)) // ~3% jank
        assertEquals(2, g.quality)
    }

    @Test
    fun it_climbs_back_only_after_a_long_clean_run() {
        val g = QualityGovernor(maxQuality = 2)
        g.feed(90, jankEvery = 3)
        assertEquals(1, g.quality)
        // 5 clean windows are not enough after a drop: the bar was raised.
        assertNull(g.feed(90 * 5, jankEvery = 0))
        assertEquals(1, g.quality)
        assertEquals(2, g.feed(90 * 10, jankEvery = 0))
    }

    @Test
    fun it_never_climbs_past_the_device_maximum() {
        val g = QualityGovernor(maxQuality = 1)
        g.feed(90 * 100, jankEvery = 0)
        assertEquals(1, g.quality)
    }
}
