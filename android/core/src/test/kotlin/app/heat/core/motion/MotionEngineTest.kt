package app.heat.core.motion

import app.heat.core.model.DetectSettings
import app.heat.core.model.NightMode
import app.heat.core.model.Zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/**
 * Behavioural tests for [MotionEngine], driven with generated frames: the only
 * way to prove the night guards work before the engine ever sees a lens.
 * Ported from the earlier app, where these cases were verified against real
 * hardware behaviour, and extended for the new settings.
 */
class MotionEngineTest {

    private val w = 320
    private val h = 240
    private lateinit var engine: MotionEngine

    private fun newEngine() = MotionEngine().apply {
        setEnabled(true)
        setSensitivity(50)
        setCooldownSeconds(0)
        setNightMode(NightMode.DAY)
        setZone(Zone(0f, 0f, 1f, 1f))
    }

    @Before
    fun setUp() {
        engine = newEngine()
    }

    private fun flat(value: Int) = ByteArray(w * h) { value.toByte() }

    private fun withNoise(base: Int, amplitude: Int, rng: Random) = ByteArray(w * h) {
        (base + rng.nextInt(-amplitude, amplitude + 1)).coerceIn(0, 255).toByte()
    }

    private fun ByteArray.rect(x0: Int, y0: Int, x1: Int, y1: Int, value: Int) = apply {
        for (yy in y0 until y1) for (xx in x0 until x1) this[yy * w + xx] = value.toByte()
    }

    private fun feed(frame: ByteArray, t: Long) = engine.analyze(frame, w, h, w, 1, t)

    private fun settle(frameFactory: (Int) -> ByteArray, frames: Int = 40, startT: Long = 0L) {
        for (i in 0 until frames) feed(frameFactory(i), startT + i * 33L)
    }

    @Test
    fun static_scene_never_triggers() {
        settle({ flat(120) })
        var triggers = 0
        for (i in 0 until 60) if (feed(flat(120), 2_000L + i * 33L).triggered) triggers++
        assertEquals("A perfectly still scene must never trigger", 0, triggers)
    }

    @Test
    fun sensor_noise_alone_never_triggers() {
        val rng = Random(1234)
        settle({ withNoise(40, 6, rng) })
        var triggers = 0
        for (i in 0 until 80) if (feed(withNoise(40, 6, rng), 3_000L + i * 33L).triggered) triggers++
        assertEquals("Sensor grain must stay below the learned noise floor", 0, triggers)
    }

    @Test
    fun moving_object_triggers() {
        settle({ flat(100) })
        var triggered = false
        for (i in 0 until 12) {
            val f = flat(100).rect(40 + i * 8, 60, 40 + i * 8 + 60, 180, 220)
            if (feed(f, 5_000L + i * 33L).triggered) triggered = true
        }
        assertTrue("A large moving object must trigger", triggered)
    }

    @Test
    fun uniform_brightness_jump_does_not_trigger() {
        settle({ flat(100) })
        var triggers = 0
        var level = 100
        for (i in 0 until 20) {
            level = (level + 6).coerceAtMost(220)
            if (feed(flat(level), 6_000L + i * 33L).triggered) triggers++
        }
        assertEquals("A global illumination change must be rejected", 0, triggers)
    }

    @Test
    fun with_light_rejection_off_a_brightness_jump_is_motion() {
        engine.setRejectLightChanges(false)
        settle({ flat(100) })
        var triggered = false
        var level = 100
        for (i in 0 until 20) {
            level = (level + 12).coerceAtMost(240)
            if (feed(flat(level), 6_000L + i * 33L).triggered) triggered = true
        }
        assertTrue("without rejection a global change counts as movement", triggered)
    }

    @Test
    fun single_frame_pop_does_not_trigger_with_persistence() {
        engine.setPersistenceFrames(3)
        settle({ flat(100) })
        val r1 = feed(flat(100).rect(100, 80, 220, 200, 250), 7_000L)
        val r2 = feed(flat(100), 7_033L)
        assertFalse("A one-frame pop must not trigger", r1.triggered || r2.triggered)
    }

    private val rightHalf = Zone(0.5f, 0f, 0.5f, 1f)

    @Test
    fun motion_outside_the_tracker_is_ignored() {
        engine.setZone(rightHalf)
        settle({ flat(100) })
        var triggers = 0
        for (i in 0 until 15) {
            val f = flat(100).rect(10, 40 + i * 4, 140, 120 + i * 4, 230)
            if (feed(f, 8_000L + i * 33L).triggered) triggers++
        }
        assertEquals("Motion outside the tracker must be discarded", 0, triggers)
    }

    @Test
    fun motion_inside_the_tracker_triggers() {
        engine.setZone(rightHalf)
        settle({ flat(100) })
        var triggered = false
        for (i in 0 until 15) {
            val f = flat(100).rect(170, 40 + i * 4, 310, 120 + i * 4, 230)
            if (feed(f, 9_000L + i * 33L).triggered) triggered = true
        }
        assertTrue("Motion inside the tracker must trigger", triggered)
    }

    @Test
    fun row_stride_padding_is_handled() {
        val stride = w + 64
        val padded = ByteArray(stride * h) { 100 }
        val e = newEngine()
        repeat(40) { e.analyze(padded, w, h, stride, 1, it * 33L) }
        var triggers = 0
        repeat(40) { if (e.analyze(padded, w, h, stride, 1, 4_000L + it * 33L).triggered) triggers++ }
        assertEquals("A still padded frame must not trigger", 0, triggers)
    }

    @Test
    fun padded_frames_detect_motion_at_the_right_place() {
        // Same moving blob, but delivered with padded rows: must trigger exactly like the unpadded case.
        val stride = w + 64
        fun padded(src: ByteArray) = ByteArray(stride * h).also { for (r in 0 until h) System.arraycopy(src, r * w, it, r * stride, w) }
        val e = newEngine()
        repeat(40) { e.analyze(padded(flat(100)), w, h, stride, 1, it * 33L) }
        var triggered = false
        for (i in 0 until 12) {
            val f = padded(flat(100).rect(40 + i * 8, 60, 40 + i * 8 + 60, 180, 220))
            if (e.analyze(f, w, h, stride, 1, 5_000L + i * 33L).triggered) triggered = true
        }
        assertTrue(triggered)
    }

    @Test
    fun cooldown_suppresses_repeat_triggers() {
        engine.setCooldownSeconds(5)
        settle({ flat(100) })
        var triggers = 0
        for (i in 0 until 40) {
            val f = flat(100).rect(60 + (i % 10) * 12, 60, 60 + (i % 10) * 12 + 70, 190, 225)
            if (feed(f, 10_000L + i * 100L).triggered) triggers++
        }
        assertTrue("Cooldown must collapse continuous motion into few alerts, got $triggers", triggers <= 2)
    }

    @Test
    fun night_mode_engages_on_dark_scenes() {
        val dark = MotionEngine().apply { setEnabled(true); setNightMode(NightMode.AUTO) }
        assertTrue(dark.analyze(ByteArray(w * h) { 20 }, w, h, w, 1, 0L).night)
        val bright = MotionEngine().apply { setEnabled(true); setNightMode(NightMode.AUTO) }
        assertFalse(bright.analyze(ByteArray(w * h) { 200.toByte() }, w, h, w, 1, 0L).night)
    }

    @Test
    fun disabled_engine_never_triggers() {
        engine.setEnabled(false)
        var triggers = 0
        for (i in 0 until 30) {
            val f = flat(100).rect(40 + i * 8, 60, 40 + i * 8 + 60, 180, 220)
            if (feed(f, 11_000L + i * 33L).triggered) triggers++
        }
        assertEquals(0, triggers)
    }

    @Test
    fun configure_applies_all_detect_settings() {
        val e = MotionEngine().apply { setEnabled(true) }
        e.configure(DetectSettings(sensitivity = 80, cooldownSec = 0, persistence = 1, zone = rightHalf), NightMode.DAY)
        fun f(x: Int) = ByteArray(w * h) { 100 }.also { a -> for (yy in 40 until 160) for (xx in x until x + 100) a[yy * w + xx] = 230.toByte() }
        repeat(40) { e.analyze(ByteArray(w * h) { 100 }, w, h, w, 1, it * 33L) }
        var triggered = false
        for (i in 0 until 10) if (e.analyze(f(180 + i * 4), w, h, w, 1, 3_000L + i * 33L).triggered) triggered = true
        assertTrue(triggered)
    }

    @Test
    fun steady_state_analysis_allocates_nothing_that_grows() {
        // Not a profiler, but a regression guard: thousands of frames must not change the engine's footprint.
        val frame = flat(100)
        repeat(50) { feed(frame, it * 33L) }
        val rt = Runtime.getRuntime()
        System.gc()
        val before = rt.totalMemory() - rt.freeMemory()
        repeat(5_000) { feed(frame, 10_000L + it * 33L) }
        System.gc()
        val after = rt.totalMemory() - rt.freeMemory()
        assertTrue("memory grew by ${(after - before) / 1024} KB over 5000 frames", after - before < 2 * 1024 * 1024)
    }
}
