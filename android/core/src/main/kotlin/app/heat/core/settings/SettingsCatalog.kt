package app.heat.core.settings

import app.heat.core.camera.LensStrategy
import app.heat.core.model.AntiFlicker
import app.heat.core.model.CameraConfig
import app.heat.core.model.CameraSteps
import app.heat.core.model.Facing
import app.heat.core.model.HapticProfile
import app.heat.core.model.NightMode
import app.heat.core.model.StreamQuality
import app.heat.core.model.WhiteBalance

/** What this particular camera can do; some settings only exist where the hardware supports them. */
data class Capabilities(
    val hasTorch: Boolean = true,
    val manualFocus: Boolean = true,
    val manualExposure: Boolean = true,
    val lensStrategy: LensStrategy = LensStrategy.LOGICAL
)

enum class Section(val title: String, val blurb: String, val keywords: List<String>) {
    CAMERA("Camera", "Lens, exposure, focus, colour", listOf("lens", "picture", "image", "photo", "video")),
    DETECTION("Detection", "What counts as movement", listOf("motion", "alarm", "watch", "trigger")),
    VIDEO("Video to your watch", "Quality and sound", listOf("stream", "picture", "connection", "bandwidth", "speed")),
    ALERTS("Alerts", "How you are told", listOf("notification", "notify", "warning", "ring")),
    POWER("Power", "Battery and heat", listOf("battery", "energy", "charge", "temperature", "hot")),
    ABOUT("This camera", "Name, pairing, diagnostics", listOf("info", "status", "version", "device"))
}

/** How a setting is edited. Positions and values are always read from and written to a [CameraConfig]. */
sealed interface SettingKind {
    class Toggle(val read: (CameraConfig) -> Boolean, val write: (CameraConfig, Boolean) -> CameraConfig) : SettingKind

    /** One of a short list of named options. */
    class Choice(val labels: List<String>, val read: (CameraConfig) -> Int, val write: (CameraConfig, Int) -> CameraConfig) : SettingKind

    class Slider(
        val range: IntRange, val step: Int, val format: (Int) -> String,
        val read: (CameraConfig) -> Int, val write: (CameraConfig, Int) -> CameraConfig
    ) : SettingKind

    class Stepper(
        val range: IntRange, val step: Int, val format: (Int) -> String,
        val read: (CameraConfig) -> Int, val write: (CameraConfig, Int) -> CameraConfig
    ) : SettingKind

    /** Read-only text whose value the screen supplies, keyed by [key]. */
    class Info(val key: String) : SettingKind

    /** Free text (the camera's name). */
    class Text(val read: (CameraConfig) -> String, val write: (CameraConfig, String) -> CameraConfig) : SettingKind
}

class SettingDef(
    val id: String,
    val section: Section,
    val title: String,
    val kind: SettingKind,
    val hint: String? = null,
    /** Other words a person might use for this: "brightness" for exposure, "buzz" for haptics. */
    val keywords: List<String> = emptyList(),
    /** Whether the setting exists right now (hardware, or another setting being on). */
    val visible: (CameraConfig, Capabilities) -> Boolean = { _, _ -> true }
)

/**
 * Every setting the camera has, described once. The phone's settings screen draws it as sections and as search
 * results; the watch lists the same entries. Adding a setting here puts it on both, findable by name, by its
 * synonyms and by its section, with no screen code to forget.
 */
object SettingsCatalog {
    private fun cap(s: String) = s.lowercase().replaceFirstChar(Char::uppercase)

    private fun percent(n: Int) = "$n%"
    private fun seconds(n: Int) = "${n}s"
    private fun neverOr(suffix: String): (Int) -> String = { if (it == 0) "Never" else "$it$suffix" }

    val all: List<SettingDef> = listOf(
        // ── camera ──
        SettingDef("camera.facing", Section.CAMERA, "Lens", SettingKind.Choice(
            listOf("Rear", "Front"), { it.camera.facing.ordinal }, { c, i -> c.copy(camera = c.camera.copy(facing = Facing.entries[i])) }
        ), keywords = listOf("camera", "selfie", "back", "front", "flip", "switch")),
        SettingDef("camera.night", Section.CAMERA, "Night handling", SettingKind.Choice(
            NightMode.entries.map { cap(it.name) }, { it.camera.night.ordinal }, { c, i -> c.copy(camera = c.camera.copy(night = NightMode.entries[i])) }
        ), hint = "Auto switches by itself as the room darkens", keywords = listOf("dark", "low light", "evening", "day", "moon")),
        SettingDef("camera.exposure", Section.CAMERA, "Exposure", SettingKind.Slider(
            -12..12, 1, { if (it > 0) "+$it" else "$it" }, { it.camera.exposure }, { c, v -> c.copy(camera = c.camera.copy(exposure = v)) }
        ), keywords = listOf("brightness", "bright", "dark", "ev", "light level")),
        SettingDef("camera.torch", Section.CAMERA, "Flashlight", SettingKind.Toggle(
            { it.camera.torch }, { c, on -> c.copy(camera = c.camera.copy(torch = on)) }
        ), keywords = listOf("torch", "flash", "light", "lamp"), visible = { _, caps -> caps.hasTorch }),
        SettingDef("camera.lock", Section.CAMERA, "Lock exposure", SettingKind.Toggle(
            { it.camera.lockExposure }, { c, on -> c.copy(camera = c.camera.copy(lockExposure = on)) }
        ), hint = "Stops auto-exposure steps being read as movement", keywords = listOf("freeze", "fixed", "auto exposure", "lock white balance")),
        SettingDef("camera.whiteBalance", Section.CAMERA, "White balance", SettingKind.Choice(
            WhiteBalance.entries.map { cap(it.name) }, { it.camera.whiteBalance.ordinal },
            { c, i -> c.copy(camera = c.camera.copy(whiteBalance = WhiteBalance.entries[i])) }
        ), keywords = listOf("colour", "color", "tint", "warm", "cool", "temperature", "tungsten", "daylight")),
        SettingDef("camera.focus", Section.CAMERA, "Focus", SettingKind.Choice(
            CameraSteps.FOCUS.map { when (it) { -1 -> "Auto"; 0 -> "Far"; 100 -> "Near"; else -> "$it%" } },
            { CameraSteps.nearest(CameraSteps.FOCUS, it.camera.focus) },
            { c, i -> c.copy(camera = c.camera.copy(focus = CameraSteps.FOCUS[i])) }
        ), hint = "Auto follows what moves; Far to Near fixes it", keywords = listOf("sharp", "blur", "autofocus", "distance"),
            visible = { _, caps -> caps.manualFocus }),
        SettingDef("camera.iso", Section.CAMERA, "ISO", SettingKind.Choice(
            CameraSteps.ISO.map { if (it == 0) "Auto" else "$it" }, { CameraSteps.nearest(CameraSteps.ISO, it.camera.iso) },
            { c, i -> c.copy(camera = c.camera.copy(iso = CameraSteps.ISO[i])) }
        ), hint = "Setting ISO or shutter takes exposure into manual", keywords = listOf("sensitivity", "grain", "noise", "gain"),
            visible = { _, caps -> caps.manualExposure }),
        SettingDef("camera.shutter", Section.CAMERA, "Shutter speed", SettingKind.Choice(
            CameraSteps.SHUTTER.map { if (it == 0) "Auto" else "1/$it" }, { CameraSteps.nearest(CameraSteps.SHUTTER, it.camera.shutterDenom) },
            { c, i -> c.copy(camera = c.camera.copy(shutterDenom = CameraSteps.SHUTTER[i])) }
        ), keywords = listOf("exposure time", "motion blur", "fast", "slow"), visible = { _, caps -> caps.manualExposure }),
        SettingDef("camera.antiFlicker", Section.CAMERA, "Anti-flicker", SettingKind.Choice(
            listOf("Auto", "50 Hz", "60 Hz", "Off"), { it.camera.antiFlicker.ordinal },
            { c, i -> c.copy(camera = c.camera.copy(antiFlicker = AntiFlicker.entries[i])) }
        ), hint = "Match your mains frequency if you see banding", keywords = listOf("banding", "stripes", "mains", "lights", "flickering")),
        SettingDef("camera.blend", Section.CAMERA, "Blend lenses", SettingKind.Toggle(
            { it.camera.blendLenses }, { c, on -> c.copy(camera = c.camera.copy(blendLenses = on)) }
        ), hint = "Cross-fade between lenses while zooming", keywords = listOf("transition", "smooth", "ultrawide", "telephoto"),
            visible = { _, caps -> caps.lensStrategy == LensStrategy.BLEND }),

        // ── detection ──
        SettingDef("detect.sensitivity", Section.DETECTION, "Sensitivity", SettingKind.Slider(
            1..100, 5, ::percent, { it.detect.sensitivity }, { c, v -> c.copy(detect = c.detect.copy(sensitivity = v)) }
        ), hint = "Higher notices smaller, fainter movement", keywords = listOf("threshold", "motion", "trigger", "detect")),
        SettingDef("detect.cooldown", Section.DETECTION, "Quiet time between alerts", SettingKind.Stepper(
            0..600, 5, ::seconds, { it.detect.cooldownSec }, { c, v -> c.copy(detect = c.detect.copy(cooldownSec = v)) }
        ), keywords = listOf("cooldown", "repeat", "gap", "spam", "delay")),
        SettingDef("detect.persistence", Section.DETECTION, "Frames to confirm", SettingKind.Stepper(
            1..10, 1, { "$it" }, { it.detect.persistence }, { c, v -> c.copy(detect = c.detect.copy(persistence = v)) }
        ), hint = "Higher means fewer false alarms, a little slower", keywords = listOf("false alarm", "false positive", "debounce")),
        SettingDef("detect.rejectLight", Section.DETECTION, "Ignore lighting changes", SettingKind.Toggle(
            { it.detect.rejectLightChanges }, { c, on -> c.copy(detect = c.detect.copy(rejectLightChanges = on)) }
        ), hint = "Headlights and light switches", keywords = listOf("headlights", "light", "switch", "shadow", "false alarm")),
        SettingDef("detect.armDelay", Section.DETECTION, "Arm delay", SettingKind.Stepper(
            0..60, 5, ::seconds, { it.detect.armDelaySec }, { c, v -> c.copy(detect = c.detect.copy(armDelaySec = v)) }
        ), hint = "Time to leave the room", keywords = listOf("countdown", "timer", "leave", "wait")),
        SettingDef("detect.tamper", Section.DETECTION, "Tamper alert", SettingKind.Toggle(
            { it.detect.tamper }, { c, on -> c.copy(detect = c.detect.copy(tamper = on)) }
        ), hint = "Tell me if the phone is moved or knocked", keywords = listOf("knock", "move", "pick up", "theft", "shake")),

        // ── video ──
        SettingDef("stream.quality", Section.VIDEO, "Quality", SettingKind.Choice(
            listOf("Auto", "Low", "Med", "High", "Max"), { it.stream.quality.ordinal },
            { c, i -> c.copy(stream = c.stream.copy(quality = StreamQuality.entries[i])) }
        ), hint = "Auto follows the connection", keywords = listOf("resolution", "bitrate", "sharpness", "bandwidth")),
        SettingDef("stream.muddiness", Section.VIDEO, "Muddiness", SettingKind.Slider(
            0..100, 5, { "$it" }, { it.stream.muddiness }, { c, v -> c.copy(stream = c.stream.copy(muddiness = v)) }
        ), hint = "Softer picture, smoother motion on a weak link", keywords = listOf("soft", "blur", "smooth", "weak", "lag", "stutter")),
        SettingDef("stream.audio", Section.VIDEO, "Allow microphone", SettingKind.Toggle(
            { it.stream.allowAudio }, { c, on -> c.copy(stream = c.stream.copy(allowAudio = on)) }
        ), keywords = listOf("mic", "sound", "audio", "listen", "hear")),

        // ── alerts ──
        SettingDef("alerts.sound", Section.ALERTS, "Sound on this phone", SettingKind.Toggle(
            { it.alerts.phoneSound }, { c, on -> c.copy(alerts = c.alerts.copy(phoneSound = on)) }
        ), keywords = listOf("alarm", "ring", "beep", "siren", "loud")),
        SettingDef("alerts.haptics", Section.ALERTS, "Watch haptics", SettingKind.Choice(
            HapticProfile.entries.map { cap(it.name) }, { it.alerts.watchHaptics.ordinal },
            { c, i -> c.copy(alerts = c.alerts.copy(watchHaptics = HapticProfile.entries[i])) }
        ), keywords = listOf("vibrate", "vibration", "buzz", "wrist", "tap")),
        SettingDef("alerts.quiet", Section.ALERTS, "Quiet hours", SettingKind.Toggle(
            { it.alerts.quietFromMin != null },
            { c, on -> c.copy(alerts = c.alerts.copy(quietFromMin = if (on) 22 * 60 else null, quietToMin = if (on) 7 * 60 else null)) }
        ), hint = "Motion alerts are muted between the times below", keywords = listOf("night", "sleep", "mute", "do not disturb", "silence", "schedule")),
        SettingDef("alerts.quietFrom", Section.ALERTS, "Quiet from", SettingKind.Stepper(
            0..1425, 30, ::clock, { it.alerts.quietFromMin ?: 0 }, { c, v -> c.copy(alerts = c.alerts.copy(quietFromMin = v)) }
        ), visible = { c, _ -> c.alerts.quietFromMin != null }, keywords = listOf("start", "begin", "bedtime")),
        SettingDef("alerts.quietTo", Section.ALERTS, "Quiet until", SettingKind.Stepper(
            0..1425, 30, ::clock, { it.alerts.quietToMin ?: 0 }, { c, v -> c.copy(alerts = c.alerts.copy(quietToMin = v)) }
        ), visible = { c, _ -> c.alerts.quietFromMin != null }, keywords = listOf("end", "wake", "morning")),

        // ── power ──
        SettingDef("power.blackout", Section.POWER, "Black screen while armed", SettingKind.Toggle(
            { it.power.blackoutWhileArmed }, { c, on -> c.copy(power = c.power.copy(blackoutWhileArmed = on)) }
        ), hint = "Saves battery and heat", keywords = listOf("screen off", "oled", "dim", "display")),
        SettingDef("power.stop", Section.POWER, "Stop below battery", SettingKind.Stepper(
            0..50, 5, neverOr("%"), { it.power.lowBatteryStopPercent }, { c, v -> c.copy(power = c.power.copy(lowBatteryStopPercent = v)) }
        ), keywords = listOf("disarm", "shut off", "low battery", "cut off")),
        SettingDef("power.warn", Section.POWER, "Warn below battery", SettingKind.Stepper(
            0..80, 5, neverOr("%"), { it.power.lowBatteryWarnPercent }, { c, v -> c.copy(power = c.power.copy(lowBatteryWarnPercent = v)) }
        ), keywords = listOf("low battery", "reminder", "charge")),

        // ── about ──
        SettingDef("about.name", Section.ABOUT, "Name", SettingKind.Text(
            { it.label }, { c, v -> c.copy(label = v) }
        ), hint = "Shown on the watch", keywords = listOf("rename", "label", "title", "room")),
        SettingDef("about.pairing", Section.ABOUT, "Pairing code", SettingKind.Info("pairing"), keywords = listOf("key", "pair", "watch", "security", "match")),
        SettingDef("about.wifi", Section.ABOUT, "Wi-Fi address", SettingKind.Info("wifi"), keywords = listOf("ip", "network", "lan", "address", "connection")),
        SettingDef("about.stream", Section.ABOUT, "Stream", SettingKind.Info("stream"), keywords = listOf("bitrate", "resolution", "kbps", "encoder", "bluetooth")),
        SettingDef("about.camera", Section.ABOUT, "Camera diagnostics", SettingKind.Info("camera"), keywords = listOf("lens", "debug", "lenses", "plan", "ids", "problem"))
    )

    /** The entries that exist for this camera and its current settings. */
    fun visible(config: CameraConfig, caps: Capabilities): List<SettingDef> = all.filter { it.visible(config, caps) }

    fun bySection(config: CameraConfig, caps: Capabilities): Map<Section, List<SettingDef>> =
        visible(config, caps).groupBy { it.section }

    private fun clock(min: Int): String = "%02d:%02d".format(min / 60, min % 60)
}
