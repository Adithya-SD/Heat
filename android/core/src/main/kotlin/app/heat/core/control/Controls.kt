package app.heat.core.control

import app.heat.core.model.CameraConfig
import app.heat.core.model.CameraSteps
import app.heat.core.model.Facing
import app.heat.core.model.NightMode
import app.heat.core.model.WhiteBalance

/**
 * A control that moves along a row of discrete positions: exposure steps, a white balance, a shutter speed.
 *
 * This is the one description of "what can this setting be set to" that the phone's ruler and the watch's
 * bezel both read, so a value is worded and stepped identically on both. Zoom is not here (it is continuous and
 * has its own ruler); neither are the switches (flashlight, lock, camera), which are [Switch]es.
 */
class DetentControl(
    val mode: BezelMode,
    /** One label per position, in order. */
    val labels: List<String>,
    /** Which position the config is at. */
    val read: (CameraConfig) -> Int,
    /** The config with the position set. Out-of-range positions are clamped. */
    private val set: (CameraConfig, Int) -> CameraConfig
) {
    val size: Int get() = labels.size

    fun write(c: CameraConfig, index: Int): CameraConfig = set(c, index.coerceIn(0, labels.lastIndex))
}

/** An on/off setting. */
class Switch(val mode: BezelMode, val read: (CameraConfig) -> Boolean, val write: (CameraConfig, Boolean) -> CameraConfig)

object Controls {
    private fun cap(s: String) = s.lowercase().replaceFirstChar(Char::uppercase)

    private val exposure = (-12..12).toList()
    private val sensitivitySteps = listOf(1) + (5..100 step 5).toList()
    private val muddiness = (0..100 step 10).toList()

    private fun nearest(list: List<Int>, v: Int) = CameraSteps.nearest(list, v)

    val all: List<DetentControl> = listOf(
        DetentControl(
            BezelMode.EXPOSURE,
            exposure.map { if (it > 0) "+$it" else "$it" },
            { it.camera.exposure + 12 },
            { c, i -> c.copy(camera = c.camera.copy(exposure = i - 12)) }
        ),
        DetentControl(
            BezelMode.FOCUS,
            CameraSteps.FOCUS.map { when (it) { -1 -> "Auto"; 0 -> "Far"; 100 -> "Near"; else -> "$it" } },
            { nearest(CameraSteps.FOCUS, it.camera.focus) },
            { c, i -> c.copy(camera = c.camera.copy(focus = CameraSteps.FOCUS[i])) }
        ),
        DetentControl(
            BezelMode.WHITE_BALANCE,
            WhiteBalance.entries.map { cap(it.name) },
            { it.camera.whiteBalance.ordinal },
            { c, i -> c.copy(camera = c.camera.copy(whiteBalance = WhiteBalance.entries[i])) }
        ),
        DetentControl(
            BezelMode.ISO,
            CameraSteps.ISO.map { if (it == 0) "Auto" else "$it" },
            { nearest(CameraSteps.ISO, it.camera.iso) },
            { c, i -> c.copy(camera = c.camera.copy(iso = CameraSteps.ISO[i])) }
        ),
        DetentControl(
            BezelMode.SHUTTER,
            CameraSteps.SHUTTER.map { if (it == 0) "Auto" else "1/$it" },
            { nearest(CameraSteps.SHUTTER, it.camera.shutterDenom) },
            { c, i -> c.copy(camera = c.camera.copy(shutterDenom = CameraSteps.SHUTTER[i])) }
        ),
        DetentControl(
            BezelMode.NIGHT,
            NightMode.entries.map { cap(it.name) },
            { it.camera.night.ordinal },
            { c, i -> c.copy(camera = c.camera.copy(night = NightMode.entries[i])) }
        ),
        DetentControl(
            BezelMode.SENSITIVITY,
            sensitivitySteps.map { "$it%" },
            { nearest(sensitivitySteps, it.detect.sensitivity) },
            { c, i -> c.copy(detect = c.detect.copy(sensitivity = sensitivitySteps[i])) }
        ),
        DetentControl(
            BezelMode.MUDDINESS,
            muddiness.map { "$it" },
            { nearest(muddiness, it.stream.muddiness) },
            { c, i -> c.copy(stream = c.stream.copy(muddiness = muddiness[i])) }
        )
    )

    val switches: List<Switch> = listOf(
        Switch(BezelMode.FLASH, { it.camera.torch }, { c, on -> c.copy(camera = c.camera.copy(torch = on)) }),
        Switch(BezelMode.LOCK, { it.camera.lockExposure }, { c, on -> c.copy(camera = c.camera.copy(lockExposure = on)) }),
        Switch(BezelMode.CAMERA, { it.camera.facing == Facing.FRONT }, { c, front ->
            c.copy(camera = c.camera.copy(facing = if (front) Facing.FRONT else Facing.REAR))
        })
    )

    fun detentFor(mode: BezelMode): DetentControl? = all.firstOrNull { it.mode == mode }
    fun switchFor(mode: BezelMode): Switch? = switches.firstOrNull { it.mode == mode }
}
