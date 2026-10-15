package app.heat.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln

class ZoomSmootherTest {

    private val frame = 1f / 60f

    private fun ZoomSmoother.run(seconds: Float, dt: Float = frame): List<Float> {
        val out = ArrayList<Float>()
        var t = 0f
        while (t < seconds) { out += step(dt); t += dt }
        return out
    }

    @Test
    fun it_arrives_exactly_and_stays() {
        val s = ZoomSmoother(1f, 0.5f, 10f)
        s.setTarget(4f)
        s.run(3f)
        assertEquals(4f, s.zoom, 1e-3f)
        assertTrue(s.settled)
        assertEquals(4f, s.step(0.016f), 1e-3f)
    }

    @Test
    fun it_never_passes_the_target_in_either_direction() {
        for ((from, to) in listOf(1f to 8f, 8f to 1f, 0.5f to 10f, 10f to 0.5f, 1f to 1.05f)) {
            val s = ZoomSmoother(from, 0.5f, 10f)
            s.setTarget(to)
            val path = s.run(4f)
            if (to > from) assertTrue("$from->$to overshot", path.all { it <= to + 1e-3f })
            else assertTrue("$from->$to overshot", path.all { it >= to - 1e-3f })
            // And it only ever moves toward the target.
            val diffs = path.zipWithNext { a, b -> b - a }
            assertTrue("$from->$to went backwards", diffs.all { if (to > from) it >= -1e-6f else it <= 1e-6f })
        }
    }

    @Test
    fun a_tiny_move_catches_up_in_about_a_tenth_of_a_second_so_it_feels_attached_to_the_hand() {
        val s = ZoomSmoother(2f, 0.5f, 10f)
        s.setTarget(2.1f)
        s.run(0.2f)
        assertEquals(2.1f, s.zoom, 0.02f)
    }

    @Test
    fun a_long_journey_takes_longer_than_a_short_one_but_stays_well_under_two_seconds() {
        fun secondsToSettle(from: Float, to: Float): Float {
            val s = ZoomSmoother(from, 0.5f, 10f)
            s.setTarget(to)
            var t = 0f
            while (abs(ln(s.zoom / to)) > 0.02f && t < 5f) { s.step(frame); t += frame }
            return t
        }
        val short = secondsToSettle(1f, 1.3f)
        val medium = secondsToSettle(1f, 0.5f)
        val long = secondsToSettle(0.5f, 10f)
        assertTrue("$short < $medium < $long", short < medium && medium < long)
        assertTrue("long journey $long s", long < 1.3f)
        assertTrue("medium journey $medium s", medium > 0.15f)
    }

    @Test
    fun the_path_has_no_kinks_the_speed_changes_gradually_frame_to_frame() {
        val s = ZoomSmoother(0.5f, 0.5f, 10f)
        s.setTarget(10f)
        val path = s.run(1.5f)
        val logs = path.map { ln(it.toDouble()) }
        val speed = logs.zipWithNext { a, b -> (b - a) * 60 }
        val jerk = speed.zipWithNext { a, b -> abs(b - a) }
        // Acceleration per frame stays small relative to the top speed: smooth, not stepped.
        assertTrue("max accel/frame ${jerk.max()} vs peak speed ${speed.max()}", jerk.max() < speed.max() * 0.2)
    }

    @Test
    fun a_new_target_in_mid_flight_bends_the_path_without_a_seam() {
        val s = ZoomSmoother(1f, 0.5f, 10f)
        s.setTarget(8f)
        s.run(0.2f)
        val posBefore = ln(s.zoom.toDouble())
        val speedBefore = s.velocity
        s.setTarget(0.5f)                                  // changed their mind completely
        val first = s.step(frame)
        // Position keeps following the old velocity for the frame, and the velocity itself changes only gradually.
        assertEquals(posBefore + speedBefore * frame, ln(first.toDouble()), 0.02)
        assertTrue("velocity jumped from $speedBefore to ${s.velocity}", abs(s.velocity - speedBefore) < 1.5f)
        s.run(3f)
        assertEquals(0.5f, s.zoom, 1e-3f)
    }

    @Test
    fun tracking_a_moving_target_stays_close_and_in_step() {
        val s = ZoomSmoother(1f, 0.5f, 10f)
        var target = 1.0
        var worst = 0.0
        repeat(120) {
            target *= Math.exp(1.2 * frame)                // a pinch: 1.2 ln-units per second
            s.setTarget(target.toFloat().coerceAtMost(10f))
            s.step(frame)
            worst = maxOf(worst, abs(ln(s.zoom.toDouble()) - ln(minOf(target, 10.0))))
        }
        assertTrue("lag $worst", worst < 0.12)
    }

    @Test
    fun a_stalled_frame_cannot_make_it_unstable() {
        val s = ZoomSmoother(1f, 0.5f, 10f)
        s.setTarget(6f)
        s.step(0.016f)
        val z = s.step(2.5f)                                // the camera hiccuped for two and a half seconds
        assertTrue(z in 1f..6.001f)
        assertEquals(6f, s.step(5f), 1e-3f)
    }

    @Test
    fun targets_are_clamped_and_snap_skips_the_animation() {
        val s = ZoomSmoother(1f, 0.5f, 10f)
        s.setTarget(99f)
        assertEquals(10f, s.target, 1e-3f)
        s.setTarget(0.01f)
        assertEquals(0.5f, s.target, 1e-3f)
        s.snapTo(3f)
        assertEquals(3f, s.zoom, 1e-4f)
        assertTrue(s.settled)
    }

    @Test
    fun zero_or_negative_time_does_nothing() {
        val s = ZoomSmoother(2f, 0.5f, 10f)
        s.setTarget(5f)
        assertEquals(2f, s.step(0f), 1e-5f)
        assertEquals(2f, s.step(-1f), 1e-5f)
    }
}
