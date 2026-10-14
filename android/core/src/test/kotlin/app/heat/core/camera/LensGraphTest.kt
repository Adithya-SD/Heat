package app.heat.core.camera

import app.heat.core.model.Facing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LensGraphTest {

    private fun cam(id: String, f35: Float?, zoom: ClosedFloatingPointRange<Float> = 1f..10f, facing: Facing = Facing.REAR, usable: Boolean = true) =
        CameraProfile(id, facing, usable, zoomMin = zoom.start, zoomMax = zoom.endInclusive, focal35 = f35)

    /** Each lens its own camera id, none of them a logical camera: what many Samsung phones show to other apps. */
    private val separate = listOf(
        cam("0", 26f), cam("2", 13f, 1f..4f), cam("3", 78f, 1f..4f),
        cam("1", 24f, facing = Facing.FRONT), cam("4", null, usable = false)
    )

    /** One logical camera fronting all three lenses, zoom 0.5-10. */
    private val logical = listOf(
        CameraProfile("0", Facing.REAR, true, physicalIds = listOf("0", "2", "3"), zoomMin = 0.5f, zoomMax = 10f,
            physicalFocal35 = mapOf("0" to 26f, "2" to 13f, "3" to 78f)),
        cam("1", 24f, facing = Facing.FRONT)
    )

    @Test
    fun separate_lens_cameras_become_one_axis_with_the_main_lens_as_the_device_we_hold() {
        val g = LensGraph.build(separate, Facing.REAR, allowBlend = true)!!
        assertEquals(LensStrategy.SWITCH, g.strategy)
        assertEquals("0", g.deviceId)
        assertEquals(listOf("0.5", "1", "3"), g.lenses.map { it.label })
        assertEquals(mapOf("2" to 0.5f, "3" to 3f), g.switchTargets)
        assertEquals(0.5f, g.zoomMin, 1e-3f)
        assertEquals(10f, g.zoomMax, 1e-3f)
    }

    @Test
    fun a_logical_camera_that_covers_every_lens_is_blended_when_allowed_and_plain_when_not() {
        val blend = LensGraph.build(logical, Facing.REAR, allowBlend = true)!!
        assertEquals(LensStrategy.BLEND, blend.strategy)
        assertEquals("0", blend.deviceId)
        assertEquals(listOf("2", "0", "3"), blend.lenses.map { it.id })
        assertEquals(0.5f, blend.zoomMin, 1e-3f)

        val plain = LensGraph.build(logical, Facing.REAR, allowBlend = false)!!
        assertEquals(LensStrategy.LOGICAL, plain.strategy)
        assertEquals(blend.lenses, plain.lenses)
    }

    @Test
    fun the_same_lens_reported_twice_is_one_lens_and_the_logical_cameras_physical_id_wins() {
        val both = logical + cam("2", 13.2f, 1f..4f) + cam("3", 77f, 1f..4f)   // also exposed as standalone ids
        val g = LensGraph.build(both, Facing.REAR, allowBlend = true)!!
        assertEquals(3, g.lenses.size)
        assertEquals(LensStrategy.BLEND, g.strategy)
    }

    @Test
    fun a_logical_camera_that_stops_at_1x_leaves_the_ultrawide_to_be_switched_to() {
        val partial = listOf(
            CameraProfile("0", Facing.REAR, true, physicalIds = listOf("0", "2", "3"), zoomMin = 1f, zoomMax = 10f,
                physicalFocal35 = mapOf("0" to 26f, "2" to 13f, "3" to 78f))
        )
        val g = LensGraph.build(partial, Facing.REAR, allowBlend = true)!!
        assertEquals(LensStrategy.SWITCH, g.strategy)
        assertEquals("0", g.deviceId)
        assertTrue("2" in g.switchTargets)
    }

    @Test
    fun a_phone_with_one_lens_just_zooms() {
        val g = LensGraph.build(listOf(cam("0", 26f, 1f..8f)), Facing.REAR, allowBlend = true)!!
        assertEquals(LensStrategy.LOGICAL, g.strategy)
        assertEquals(1, g.lenses.size)
        assertEquals(8f, g.zoomMax, 1e-3f)
    }

    @Test
    fun cameras_that_report_no_focal_length_still_get_a_working_plan() {
        val g = LensGraph.build(listOf(cam("0", null, 1f..6f)), Facing.REAR, allowBlend = true)!!
        assertEquals(LensStrategy.LOGICAL, g.strategy)
        assertEquals("0", g.deviceId)
        assertTrue(g.note.contains("no focal"))
    }

    @Test
    fun each_side_of_the_phone_is_planned_separately_and_helpers_are_ignored() {
        val front = LensGraph.build(separate, Facing.FRONT, allowBlend = true)!!
        assertEquals("1", front.deviceId)
        assertEquals(1, front.lenses.size)
        assertNull(LensGraph.build(separate.filter { it.facing == Facing.REAR && !it.usable }, Facing.REAR, true))
    }

    @Test
    fun lens_buttons_are_named_the_way_phones_name_them() {
        assertEquals("0.5", LensGraph.labelFor(0.5f))
        assertEquals("0.5", LensGraph.labelFor(0.52f))
        assertEquals("1", LensGraph.labelFor(1.02f))
        assertEquals("3", LensGraph.labelFor(2.9f))
        assertEquals("5", LensGraph.labelFor(4.96f))
        assertEquals("1.5", LensGraph.labelFor(1.4f))
    }

    @Test
    fun the_lens_for_a_zoom_is_the_tightest_one_that_still_covers_it() {
        val g = LensGraph.build(separate, Facing.REAR, true)!!
        assertEquals("2", g.lensFor(0.5f).id)
        assertEquals("2", g.lensFor(0.9f).id)
        assertEquals("0", g.lensFor(1f).id)
        assertEquals("0", g.lensFor(2.9f).id)
        assertEquals("3", g.lensFor(3f).id)
        assertEquals("3", g.lensFor(9f).id)
        assertNotNull(g.lensFor(0.1f))
    }
}
