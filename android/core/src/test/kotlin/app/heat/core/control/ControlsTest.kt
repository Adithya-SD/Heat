package app.heat.core.control

import app.heat.core.model.CameraConfig
import app.heat.core.model.Facing
import app.heat.core.model.NightMode
import app.heat.core.model.WhiteBalance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlsTest {
    private val base = CameraConfig()

    @Test
    fun every_control_round_trips_every_position() {
        for (c in Controls.all) {
            for (i in 0 until c.size) {
                val cfg = c.write(base, i)
                assertEquals("${c.mode} position $i", i, c.read(cfg))
            }
        }
    }

    @Test
    fun positions_are_clamped_not_rejected() {
        val exposure = Controls.detentFor(BezelMode.EXPOSURE)!!
        assertEquals(-12, exposure.write(base, -5).camera.exposure)
        assertEquals(12, exposure.write(base, 999).camera.exposure)
    }

    @Test
    fun the_default_config_reads_as_the_resting_position() {
        assertEquals("0", Controls.detentFor(BezelMode.EXPOSURE)!!.let { it.labels[it.read(base)] })
        assertEquals("Auto", Controls.detentFor(BezelMode.FOCUS)!!.let { it.labels[it.read(base)] })
        assertEquals("Auto", Controls.detentFor(BezelMode.ISO)!!.let { it.labels[it.read(base)] })
        assertEquals("Auto", Controls.detentFor(BezelMode.SHUTTER)!!.let { it.labels[it.read(base)] })
        assertEquals("Auto", Controls.detentFor(BezelMode.WHITE_BALANCE)!!.let { it.labels[it.read(base)] })
        assertEquals("50%", Controls.detentFor(BezelMode.SENSITIVITY)!!.let { it.labels[it.read(base)] })
    }

    @Test
    fun values_are_worded_the_way_the_watch_words_them() {
        // The phone's ruler and the watch's menu chip must agree on every word.
        for (c in Controls.all) {
            val cfg = c.write(base, c.size - 1)
            val onWatch = BezelController.describe(c.mode, cfg)
            val onPhone = c.labels[c.read(cfg)]
            when (c.mode) {
                BezelMode.SENSITIVITY, BezelMode.MUDDINESS, BezelMode.FOCUS -> Unit   // the watch shows exact values, the ruler its detents
                else -> assertEquals("${c.mode}", onWatch, onPhone)
            }
        }
    }

    @Test
    fun a_value_that_is_between_detents_reads_as_the_nearest_one() {
        val iso = Controls.detentFor(BezelMode.ISO)!!
        val cfg = base.copy(camera = base.camera.copy(iso = 640))
        assertEquals("800", iso.labels[iso.read(cfg)])
        val sens = Controls.detentFor(BezelMode.SENSITIVITY)!!
        val s = base.copy(detect = base.detect.copy(sensitivity = 52))
        assertEquals("50%", sens.labels[sens.read(s)])
    }

    @Test
    fun the_switches_flip_the_right_things() {
        val flash = Controls.switchFor(BezelMode.FLASH)!!
        assertTrue(flash.read(flash.write(base, true)))
        assertTrue(flash.write(base, true).camera.torch)
        val lock = Controls.switchFor(BezelMode.LOCK)!!
        assertTrue(lock.write(base, true).camera.lockExposure)
        val cam = Controls.switchFor(BezelMode.CAMERA)!!
        assertEquals(Facing.FRONT, cam.write(base, true).camera.facing)
        assertFalse(cam.read(base))
    }

    @Test
    fun every_mode_but_zoom_and_tracker_is_a_ruler_or_a_switch() {
        for (m in BezelMode.entries) {
            if (m == BezelMode.ZOOM || m == BezelMode.TRACKER) continue
            assertNotNull("$m has no control", Controls.detentFor(m) ?: Controls.switchFor(m))
        }
    }

    @Test
    fun the_discrete_lists_match_the_enums_they_edit() {
        assertEquals(WhiteBalance.entries.size, Controls.detentFor(BezelMode.WHITE_BALANCE)!!.size)
        assertEquals(NightMode.entries.size, Controls.detentFor(BezelMode.NIGHT)!!.size)
    }
}
