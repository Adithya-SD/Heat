package app.heat.core.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ConfigTest {

    @Test
    fun diff_of_equal_configs_is_empty() {
        assertTrue(ConfigPatch.diff(CameraConfig(), CameraConfig()).isEmpty())
    }

    @Test
    fun diff_then_apply_reproduces_the_target() {
        val a = CameraConfig()
        val b = a.copy(
            label = "Porch",
            camera = a.camera.copy(zoom = 3f, torch = true, lensId = "2"),
            detect = a.detect.copy(sensitivity = 80, zone = Zone(0.1f, 0.2f, 0.5f, 0.4f))
        )
        val patch = ConfigPatch.diff(a, b)
        assertEquals(b, ConfigPatch.apply(a, patch))
    }

    @Test
    fun a_patch_is_minimal() {
        val a = CameraConfig()
        val patch = ConfigPatch.diff(a, a.copy(detect = a.detect.copy(sensitivity = 70)))
        assertEquals(setOf("detect.sensitivity"), ConfigPatch.paths(patch))
    }

    @Test
    fun setting_a_value_back_to_its_default_still_produces_a_patch() {
        val changed = CameraConfig().copy(detect = DetectSettings(sensitivity = 90))
        val patch = ConfigPatch.diff(changed, CameraConfig())
        assertEquals(CameraConfig(), ConfigPatch.apply(changed, patch))
    }

    @Test
    fun nullable_fields_round_trip() {
        val a = CameraConfig().copy(camera = CameraSettings(lensId = "3"))
        val b = a.copy(camera = a.camera.copy(lensId = null))
        assertEquals(b, ConfigPatch.apply(a, ConfigPatch.diff(a, b)))
    }

    @Test
    fun out_of_range_values_are_clamped_never_stored() {
        val patch = buildJsonObject {
            put("detect", buildJsonObject { put("sensitivity", 5000); put("cooldownSec", -9) })
            put("camera", buildJsonObject { put("zoom", 99.0) })
            put("label", "x".repeat(200))
        }
        val c = ConfigPatch.apply(CameraConfig(), patch)!!
        assertEquals(100, c.detect.sensitivity)
        assertEquals(0, c.detect.cooldownSec)
        assertEquals(10f, c.camera.zoom)
        assertEquals(24, c.label.length)
    }

    @Test
    fun a_zone_dragged_off_screen_is_pulled_back_in() {
        val z = Zone(0.9f, 0.9f, 0.5f, 0.5f).sanitized()
        assertTrue(z.x + z.width <= 1.0001f && z.y + z.height <= 1.0001f)
    }

    @Test
    fun malformed_patch_is_rejected_whole_not_half_applied() {
        val bad = buildJsonObject {
            put("detect", buildJsonObject { put("sensitivity", 99); put("persistence", "banana") })
        }
        // The valid half (sensitivity = 99) must not leak through when the other half is garbage.
        assertNull(ConfigPatch.apply(CameraConfig(), bad))
    }

    @Test
    fun garbage_enum_values_fall_back_to_defaults() {
        val patch = buildJsonObject { put("camera", buildJsonObject { put("night", "SIDEWAYS") }) }
        val c = ConfigPatch.apply(CameraConfig(), patch)!!
        assertEquals(NightMode.AUTO, c.camera.night)
    }

    @Test
    fun apply_to_all_strips_per_camera_paths_only() {
        val a = CameraConfig()
        val b = a.copy(
            label = "Garage",
            camera = a.camera.copy(zoom = 2f, torch = true, night = NightMode.NIGHT),
            detect = a.detect.copy(sensitivity = 75, zone = Zone(0.2f, 0.2f, 0.3f, 0.3f), cooldownSec = 30)
        )
        val shared = ConfigPatch.withoutPerCamera(ConfigPatch.diff(a, b))
        assertEquals(setOf("camera.night", "detect.sensitivity", "detect.cooldownSec"), ConfigPatch.paths(shared))
    }

    @Test
    fun random_edits_always_diff_and_apply_exactly() {
        val rnd = Random(42)
        var c = CameraConfig()
        repeat(500) {
            val next = c.copy(
                detect = c.detect.copy(
                    sensitivity = rnd.nextInt(1, 101),
                    cooldownSec = rnd.nextInt(0, 601),
                    zone = Zone(rnd.nextFloat() * 0.6f, rnd.nextFloat() * 0.6f, 0.1f + rnd.nextFloat() * 0.3f, 0.1f + rnd.nextFloat() * 0.3f)
                ),
                camera = c.camera.copy(zoom = 0.5f + rnd.nextFloat() * 9f, night = NightMode.entries[rnd.nextInt(3)])
            ).sanitized()
            assertEquals(next, ConfigPatch.apply(c, ConfigPatch.diff(c, next)))
            c = next
        }
    }

    // ── messages ─────────────────────────────────────────────────────

    @Test
    fun messages_round_trip() {
        val msgs = listOf<Msg>(
            Msg.Hello("Porch", Role.CAMERA, "1.0", listOf("h264")),
            Msg.SetConfig(7, buildJsonObject { put("label", "x") }),
            Msg.Arm(true, 10),
            Msg.Watch(video = true, audio = false, tier = Tier.HIGH),
            Msg.NeedKey, Msg.Snap, Msg.Identify,
            Msg.ConfigState(3, 7, CameraConfig()),
            Msg.Status(armed = true, battery = 80, lenses = listOf(LensInfo("0", "1x", 1f))),
            Msg.Alert(9, AlertKind.MOTION, 1234L, 12.5f, 1)
        )
        for (m in msgs) assertEquals(m, MsgCodec.decode(MsgCodec.encode(m)))
    }

    @Test
    fun unknown_message_types_and_garbage_are_ignored_not_fatal() {
        assertNull(MsgCodec.decode("""{"t":"from-the-future","x":1}""".toByteArray()))
        assertNull(MsgCodec.decode("not json".toByteArray()))
        assertNull(MsgCodec.decode(ByteArray(0)))
        // unknown extra fields on known messages are fine
        val m = MsgCodec.decode("""{"t":"arm","armed":true,"newField":42}""".toByteArray())
        assertEquals(Msg.Arm(true), m)
    }

    // ── sync ─────────────────────────────────────────────────────────

    private fun hostAndViewer(): Pair<ConfigHost, ConfigSync> {
        val host = ConfigHost(CameraConfig())
        val sync = ConfigSync()
        sync.onRemote(host.state(0))
        return host to sync
    }

    @Test
    fun edits_show_instantly_and_settle_when_acknowledged() {
        val (host, sync) = hostAndViewer()
        val set = sync.edit { it.copy(detect = it.detect.copy(sensitivity = 80)) }!!
        assertEquals(80, sync.effective!!.detect.sensitivity)   // optimistic

        assertTrue(host.apply(set.patch))
        sync.onRemote(host.state(set.seq))
        assertEquals(80, sync.effective!!.detect.sensitivity)
        assertEquals(1L, host.rev)
    }

    @Test
    fun a_slider_drag_of_many_edits_ends_on_the_last_value() {
        val (host, sync) = hostAndViewer()
        var lastSeq = 0
        for (v in 10..90 step 5) {
            val set = sync.edit { it.copy(detect = it.detect.copy(sensitivity = v)) }!!
            host.apply(set.patch)
            lastSeq = set.seq
        }
        sync.onRemote(host.state(lastSeq))
        assertEquals(90, sync.effective!!.detect.sensitivity)
        assertEquals(90, host.config.detect.sensitivity)
    }

    @Test
    fun the_camera_clamping_a_value_corrects_the_viewer() {
        val (host, sync) = hostAndViewer()
        val set = sync.edit { it.copy(camera = it.camera.copy(zoom = 7f)) }!!
        host.apply(set.patch)
        host.replace(host.config.copy(camera = host.config.camera.copy(zoom = 4f))) // e.g. lens limit
        sync.onRemote(host.state(set.seq))
        assertEquals(4f, sync.effective!!.camera.zoom)
    }

    @Test
    fun a_noop_edit_sends_nothing() {
        val (_, sync) = hostAndViewer()
        assertNull(sync.edit { it })
    }

    @Test
    fun editing_before_the_camera_has_reported_sends_nothing() {
        assertNull(ConfigSync().edit { it.copy(label = "x") })
    }

    @Test
    fun unacknowledged_edits_are_resent_after_a_reconnect_and_not_lost() {
        val (host, sync) = hostAndViewer()
        sync.edit { it.copy(detect = it.detect.copy(sensitivity = 33)) }  // lost in transit
        sync.edit { it.copy(detect = it.detect.copy(cooldownSec = 44)) }  // lost in transit

        val resend = sync.onSessionUp()
        assertEquals(listOf(1, 2), resend.map { it.seq })
        resend.forEach { host.apply(it.patch) }
        sync.onRemote(host.state(2))
        assertEquals(33, host.config.detect.sensitivity)
        assertEquals(44, host.config.detect.cooldownSec)
        assertEquals(33, sync.effective!!.detect.sensitivity)
    }

    @Test
    fun a_camera_restart_with_a_lower_revision_does_not_wedge_the_viewer() {
        val sync = ConfigSync()
        sync.onRemote(Msg.ConfigState(57, 0, CameraConfig()))
        sync.onRemote(Msg.ConfigState(0, 0, CameraConfig(label = "After restart")))
        assertEquals("After restart", sync.effective!!.label)
    }

    @Test
    fun another_viewers_change_shows_up_under_my_pending_edit() {
        val (host, sync) = hostAndViewer()
        val mine = sync.edit { it.copy(detect = it.detect.copy(sensitivity = 61)) }!!
        host.apply(buildJsonObject { put("label", "Renamed by someone else") })
        sync.onRemote(host.state(0)) // my edit not acked yet
        assertEquals("Renamed by someone else", sync.effective!!.label)
        assertEquals(61, sync.effective!!.detect.sensitivity)
        host.apply(mine.patch)
        sync.onRemote(host.state(mine.seq))
        assertEquals(61, sync.effective!!.detect.sensitivity)
    }

    @Test
    fun invalid_patches_are_ignored_by_the_host() {
        val host = ConfigHost(CameraConfig())
        val before = host.config
        val rejected = !host.apply(JsonObject(mapOf("detect" to JsonPrimitive("not-an-object"))))
        assertTrue(rejected)
        assertEquals(before, host.config)
    }
}
