package app.heat.core.model

import kotlinx.serialization.Serializable

/**
 * Everything about one camera that a person can change.
 *
 * This is the single source of truth for a camera's behaviour. The camera
 * owns it; viewers (watch, other phones) edit it by sending small JSON patches
 * and render only what the camera echoes back. That one-way flow is what
 * makes "I changed camera B's setting but it landed on camera A" impossible:
 * a viewer is bound to one camera's session and has no way to address another.
 *
 * Every field has a default and a hard range ([sanitized]); a value from the
 * network or from disk can never put the app in a state the UI cannot show.
 */
@Serializable
data class CameraConfig(
    val label: String = "Camera",
    val camera: CameraSettings = CameraSettings(),
    val detect: DetectSettings = DetectSettings(),
    val stream: StreamSettings = StreamSettings(),
    val capture: CaptureSettings = CaptureSettings(),
    val power: PowerSettings = PowerSettings(),
    val alerts: AlertSettings = AlertSettings()
) {
    fun sanitized(): CameraConfig = copy(
        label = label.trim().take(24).ifBlank { "Camera" },
        camera = camera.sanitized(),
        detect = detect.sanitized(),
        stream = stream.sanitized(),
        capture = capture.sanitized(),
        power = power.sanitized(),
        alerts = alerts.sanitized()
    )

    companion object {
        /**
         * Paths that describe *this* camera's physical placement and must never be
         * copied to other cameras by "apply to all". Everything else may be.
         */
        val PER_CAMERA_PATHS: Set<String> = setOf(
            "label",
            "camera.facing", "camera.lensId", "camera.zoom", "camera.torch",
            "detect.zone"
        )
    }
}

@Serializable
enum class Facing { REAR, FRONT }

@Serializable
enum class NightMode { AUTO, DAY, NIGHT }

@Serializable
enum class AntiFlicker { AUTO, HZ50, HZ60, OFF }

@Serializable
data class CameraSettings(
    val facing: Facing = Facing.REAR,
    /** Physical camera id of the chosen lens, or null for the default main lens. */
    val lensId: String? = null,
    /** User-facing zoom ratio across every lens: 0.5x ... 10x. */
    val zoom: Float = 1f,
    /** Exposure compensation in the camera's own index steps. */
    val exposure: Int = 0,
    val torch: Boolean = false,
    val night: NightMode = NightMode.AUTO,
    /** Freeze exposure and white balance. Stops auto-exposure steps being read as motion. */
    val lockExposure: Boolean = false,
    val antiFlicker: AntiFlicker = AntiFlicker.AUTO
) {
    fun sanitized() = copy(
        zoom = zoom.coerceIn(0.5f, 10f),
        exposure = exposure.coerceIn(-12, 12)
    )
}

@Serializable
data class Zone(
    val x: Float = 0.34f,
    val y: Float = 0.34f,
    val width: Float = 0.32f,
    val height: Float = 0.32f
) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height

    /** Clamped into the frame, size preserved where possible. */
    fun sanitized(): Zone {
        val w = width.coerceIn(MIN_SIZE, 1f)
        val h = height.coerceIn(MIN_SIZE, 1f)
        return Zone(x.coerceIn(0f, 1f - w), y.coerceIn(0f, 1f - h), w, h)
    }

    companion object {
        const val MIN_SIZE = 0.06f
    }
}

@Serializable
data class DetectSettings(
    /** 1..100. Higher notices smaller or fainter movement. */
    val sensitivity: Int = 50,
    /** Quiet time after an alert before the next one may fire. */
    val cooldownSec: Int = 5,
    /** Consecutive frames that must agree before it counts as motion. Higher = fewer false alarms, slower. */
    val persistence: Int = 2,
    val zone: Zone = Zone(),
    /** Subtract whole-scene brightness shifts (headlights, light switches) before looking for movement. */
    val rejectLightChanges: Boolean = true,
    /** Alert when the phone itself is moved or knocked. */
    val tamper: Boolean = true,
    /** Seconds to wait after arming so you can leave the room. */
    val armDelaySec: Int = 10
) {
    fun sanitized() = copy(
        sensitivity = sensitivity.coerceIn(1, 100),
        cooldownSec = cooldownSec.coerceIn(0, 600),
        persistence = persistence.coerceIn(1, 10),
        zone = zone.sanitized(),
        armDelaySec = armDelaySec.coerceIn(0, 60)
    )
}

@Serializable
enum class StreamQuality {
    /** Pick the best the link, battery and temperature allow, continuously. */
    AUTO,
    LOW,
    BALANCED,
    HIGH,
    MAX
}

@Serializable
data class StreamSettings(
    /** Ceiling for live video. AUTO climbs and falls with the link; the others cap it. */
    val quality: StreamQuality = StreamQuality.AUTO,
    /** Allow the camera microphone to be streamed when a viewer asks for sound. */
    val allowAudio: Boolean = true
)  {
    fun sanitized() = this
}

@Serializable
enum class ClipQuality { SD, HD, FULL_HD }

@Serializable
data class CaptureSettings(
    val photoOnMotion: Boolean = true,
    val clipOnMotion: Boolean = true,
    /** Seconds of video kept from *before* the trigger. */
    val preRollSec: Int = 3,
    val postRollSec: Int = 10,
    val clipQuality: ClipQuality = ClipQuality.HD,
    /** Oldest events are deleted past this. */
    val maxStorageMb: Int = 2048,
    val keepDays: Int = 14,
    /** Also copy captures into the phone's gallery. */
    val saveToGallery: Boolean = false
) {
    fun sanitized() = copy(
        preRollSec = preRollSec.coerceIn(0, 10),
        postRollSec = postRollSec.coerceIn(3, 60),
        maxStorageMb = maxStorageMb.coerceIn(128, 32_768),
        keepDays = keepDays.coerceIn(1, 365)
    )
}

@Serializable
data class PowerSettings(
    /** Dim the phone screen to black while armed. Saves OLED power and heat. */
    val blackoutWhileArmed: Boolean = true,
    /** Disarm and stop streaming below this battery level unless charging. 0 = never. */
    val lowBatteryStopPercent: Int = 5,
    /** Warn the watch below this level. 0 = never. */
    val lowBatteryWarnPercent: Int = 20
) {
    fun sanitized() = copy(
        lowBatteryStopPercent = lowBatteryStopPercent.coerceIn(0, 50),
        lowBatteryWarnPercent = lowBatteryWarnPercent.coerceIn(0, 80)
    )
}

@Serializable
enum class HapticProfile { OFF, SOFT, NORMAL, STRONG }

@Serializable
data class AlertSettings(
    val phoneSound: Boolean = false,
    val watchHaptics: HapticProfile = HapticProfile.NORMAL,
    /** Quiet hours as minutes since midnight, or null for none. Tamper and safety alerts still come through. */
    val quietFromMin: Int? = null,
    val quietToMin: Int? = null
) {
    fun sanitized() = copy(
        quietFromMin = quietFromMin?.coerceIn(0, 1439),
        quietToMin = quietToMin?.coerceIn(0, 1439)
    )
}
