package app.heat.core.settings

import app.heat.core.camera.LensStrategy
import app.heat.core.model.CameraConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    private val all = SettingsCatalog.all
    private fun find(q: String) = SettingsSearch.search(q, all).map { it.def.id }

    @Test
    fun an_empty_query_lists_everything_in_catalog_order() {
        assertEquals(all.map { it.id }, find(""))
        assertEquals(all.map { it.id }, find("   "))
    }

    @Test
    fun the_start_of_a_name_finds_it_first() {
        assertEquals("camera.exposure", find("expo").first())
        assertEquals("detect.sensitivity", find("sensi").first())
        assertEquals("stream.muddiness", find("mudd").first())
    }

    @Test
    fun a_word_people_would_use_finds_the_setting_that_has_a_different_name() {
        assertTrue("camera.exposure" in find("brightness"))
        assertTrue("alerts.haptics" in find("buzz"))
        assertTrue("alerts.haptics" in find("vibrate"))
        assertTrue("stream.audio" in find("mic"))
        assertTrue("camera.torch" in find("torch"))
        assertTrue("detect.tamper" in find("knock"))
        assertTrue("camera.night" in find("dark"))
    }

    @Test
    fun a_typo_is_forgiven_but_not_a_wild_guess() {
        assertEquals("camera.exposure", find("exposre").first())
        assertEquals("camera.whiteBalance", find("whte balence").first())
        assertTrue(find("zzzzzz").isEmpty())
    }

    @Test
    fun punctuation_and_spacing_do_not_matter() {
        assertEquals("about.wifi", find("wifi").first())
        assertEquals("about.wifi", find("wi-fi").first())
        assertTrue("camera.antiFlicker" in find("anti flicker"))
        assertTrue("camera.antiFlicker" in find("antiflicker"))
    }

    @Test
    fun every_word_of_the_query_has_to_find_something() {
        val both = find("quiet hours")
        assertTrue("alerts.quiet" in both)
        assertFalse("camera.exposure" in both)
        assertTrue(find("quiet banana").isEmpty())
    }

    @Test
    fun the_name_of_a_section_finds_its_settings() {
        val battery = find("battery")
        assertTrue("power.stop" in battery && "power.warn" in battery && "power.blackout" in battery)
        val power = find("power")
        assertTrue(power.filter { it.startsWith("power.") }.size >= 3)
    }

    @Test
    fun a_whole_word_beats_a_piece_of_one_which_beats_a_synonym() {
        // "light" is a whole word in "Ignore lighting changes"? no: it is the start of "lighting"; it is a synonym of the flashlight.
        val r = find("flash")
        assertEquals("camera.torch", r.first())
        val light = SettingsSearch.search("light", all)
        // "light" is a synonym of the flashlight and the start of "lighting": both are found, the more direct match first.
        assertTrue(light.map { it.def.id }.containsAll(listOf("camera.torch", "detect.rejectLight")))
        assertEquals("camera.focus", SettingsSearch.search("focus", all).first().def.id)
    }

    @Test
    fun the_matched_part_of_a_title_is_reported_for_highlighting() {
        val hit = SettingsSearch.search("expo", all).first()
        assertEquals("Exposure", hit.def.title)
        assertEquals(0..3, hit.titleMatch)
        val typo = SettingsSearch.search("exposre", all).first()
        assertNotNull(typo.titleMatch)
    }

    @Test
    fun settings_a_camera_cannot_do_do_not_appear() {
        val none = Capabilities(hasTorch = false, manualFocus = false, manualExposure = false, lensStrategy = LensStrategy.SWITCH)
        val ids = SettingsCatalog.visible(CameraConfig(), none).map { it.id }
        assertFalse("camera.torch" in ids)
        assertFalse("camera.focus" in ids)
        assertFalse("camera.iso" in ids)
        assertFalse("camera.shutter" in ids)
        assertFalse("camera.blend" in ids)
        val blend = SettingsCatalog.visible(CameraConfig(), Capabilities(lensStrategy = LensStrategy.BLEND)).map { it.id }
        assertTrue("camera.blend" in blend)
    }

    @Test
    fun the_quiet_times_only_show_when_quiet_hours_are_on() {
        val caps = Capabilities()
        val off = SettingsCatalog.visible(CameraConfig(), caps).map { it.id }
        assertFalse("alerts.quietFrom" in off)
        val quiet = SettingsCatalog.all.first { it.id == "alerts.quiet" }.kind as SettingKind.Toggle
        val on = quiet.write(CameraConfig(), true)
        val ids = SettingsCatalog.visible(on, caps).map { it.id }
        assertTrue("alerts.quietFrom" in ids && "alerts.quietTo" in ids)
    }

    @Test
    fun every_setting_reads_back_what_was_written() {
        val base = CameraConfig()
        for (d in all) {
            when (val k = d.kind) {
                is SettingKind.Toggle -> { assertTrue(d.id, k.read(k.write(base, true))); assertFalse(d.id, k.read(k.write(base, false))) }
                is SettingKind.Choice -> for (i in k.labels.indices) assertEquals("${d.id}[$i]", i, k.read(k.write(base, i)))
                is SettingKind.Slider -> for (v in listOf(k.range.first, k.range.last)) assertEquals(d.id, v, k.read(k.write(base, v)))
                is SettingKind.Stepper -> for (v in listOf(k.range.first, k.range.last)) assertEquals(d.id, v, k.read(k.write(base, v)))
                is SettingKind.Text -> assertEquals(d.id, "Porch", k.read(k.write(base, "Porch")))
                is SettingKind.Info -> Unit
            }
        }
    }

    @Test
    fun ids_are_unique_and_every_section_has_settings() {
        assertEquals(all.size, all.map { it.id }.toSet().size)
        for (s in Section.entries) assertTrue("$s is empty", all.any { it.section == s })
    }
}
