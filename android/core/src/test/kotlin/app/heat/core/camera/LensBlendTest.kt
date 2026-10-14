package app.heat.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LensBlendTest {
    private val uw = Lens("2", 0.5f)
    private val main = Lens("0", 1f)
    private val tele = Lens("3", 3f)
    private val blend = LensBlend(listOf(tele, uw, main))      // deliberately out of order

    @Test
    fun lenses_are_ordered_and_the_range_is_from_the_widest_to_the_longest() {
        assertEquals(listOf("2", "0", "3"), blend.lenses.map { it.id })
        assertEquals(0.5f, blend.minZoom, 1e-6f)
        assertEquals(3f, blend.maxZoom, 1e-6f)
    }

    @Test
    fun at_rest_on_a_lens_there_is_one_full_frame_layer() {
        for ((z, idx) in listOf(0.5f to 0, 1f to 1, 3f to 2)) {
            val p = blend.plan(z)
            assertEquals("zoom $z", 1, p.layers.size)
            assertEquals(idx, p.layers[0].lens)
            assertEquals(1f, p.layers[0].scale, 1e-3f)
            assertEquals(1f, p.layers[0].alpha, 1e-6f)
        }
    }

    @Test
    fun between_lenses_the_primary_is_always_at_least_full_frame_and_fully_opaque() {
        var z = 0.5f
        while (z <= 12f) {
            val l = blend.plan(z).layers[0]
            assertTrue("zoom $z scale ${l.scale}", l.scale >= 0.999f)
            assertEquals(1f, l.alpha, 1e-6f)
            z *= 1.01f
        }
    }

    @Test
    fun the_incoming_lens_is_a_window_that_grows_and_fades_in_and_fills_the_frame_exactly_at_its_ratio() {
        var lastAlpha = -1f
        var lastScale = 0f
        var z = 0.78f
        while (z < 0.9995f) {                               // approaching the main lens from the ultrawide
            val l = blend.plan(z).layers
            if (l.size == 2) {
                val s = l[1]
                assertEquals(1, s.lens)
                assertTrue("window must be inside the frame: ${s.scale}", s.scale <= 1.0001f)
                assertTrue("alpha must only rise: $lastAlpha -> ${s.alpha}", s.alpha >= lastAlpha - 1e-6f)
                assertTrue("window must only grow: $lastScale -> ${s.scale}", s.scale >= lastScale - 1e-6f)
                lastAlpha = s.alpha; lastScale = s.scale
            }
            z *= 1.002f
        }
        val last = blend.plan(0.9995f).layers[1]
        assertEquals(1f, last.scale, 2e-3f)
        assertTrue("almost opaque at the boundary: ${last.alpha}", last.alpha > 0.995f)
    }

    @Test
    fun a_lens_change_cannot_pop_the_picture_is_the_same_either_side_of_the_boundary() {
        for (ratio in listOf(1f, 3f)) {
            val below = blend.plan(ratio * 0.9995f).layers.last()
            val above = blend.plan(ratio * 1.0005f).layers.first()
            // Just below: the incoming lens is a full-size, opaque layer on top. Just above: it is the primary.
            assertEquals(above.lens, below.lens)
            assertEquals(above.scale, below.scale, 2e-3f)
            assertTrue(below.alpha > 0.995f && above.alpha == 1f)
        }
    }

    @Test
    fun going_down_it_is_the_same_rule_read_backwards_the_old_lens_shrinks_and_fades_away() {
        // 1.0 -> 0.8: the ultrawide is the primary (zooming out into place), the main lens is a shrinking, fading window.
        val at1 = blend.plan(1f)
        assertEquals(1, at1.layers.size)
        val mid = blend.plan(0.85f).layers
        assertEquals(0, mid[0].lens)
        assertEquals(1.7f, mid[0].scale, 1e-3f)              // ultrawide still magnified: it zooms OUT as z falls
        assertEquals(1, mid[1].lens)
        assertEquals(0.85f, mid[1].scale, 1e-3f)             // main lens shrinks inside the frame
        val low = blend.plan(0.78f).layers
        assertTrue(low.size == 1 || low[1].alpha < 0.02f)    // and has faded out before the ultrawide is at 1x
    }

    @Test
    fun only_the_lenses_needed_are_awake_and_the_ones_a_zoom_will_cross_wake_early() {
        assertEquals(setOf(1), blend.plan(1f).warm)                          // resting on the main lens: just it
        assertEquals(setOf(0), blend.plan(0.5f).warm)
        assertEquals(setOf(1, 2), blend.plan(2.2f).warm)                     // the telephoto is about to appear
        assertEquals(setOf(0, 1, 2), blend.plan(0.5f, target = 5f).warm)     // a tap on 5x from 0.5x crosses all three
        assertEquals(setOf(1, 2), blend.plan(1f, target = 3f).warm)
        assertTrue(0 !in blend.plan(1f, target = 3f).warm)                   // the ultrawide is not needed going up
    }

    @Test
    fun below_the_widest_lens_the_view_is_held_at_its_limit() {
        val p = blend.plan(0.2f)
        assertEquals(0, p.layers[0].lens)
        assertEquals(1f, p.layers[0].scale, 1e-3f)
    }

    @Test
    fun a_single_lens_phone_just_magnifies() {
        val one = LensBlend(listOf(Lens("0", 1f)))
        val p = one.plan(4f)
        assertEquals(1, p.layers.size)
        assertEquals(4f, p.layers[0].scale, 1e-3f)
        assertEquals(setOf(0), p.warm)
    }
}
