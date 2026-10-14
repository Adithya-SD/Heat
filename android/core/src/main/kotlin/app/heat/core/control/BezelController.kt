package app.heat.core.control

import app.heat.core.model.CameraConfig
import app.heat.core.model.CameraSteps
import app.heat.core.model.ConfigSync
import app.heat.core.model.Facing
import app.heat.core.model.Msg
import app.heat.core.model.NightMode
import app.heat.core.model.WhiteBalance
import app.heat.core.model.Zone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * What the rotating bezel (or crown) currently adjusts. Tapping the menu on the watch walks this list.
 *
 * The first six are the earlier app's modes in the earlier app's order; that order is muscle memory.
 */
enum class BezelMode(val displayName: String) {
    ZOOM("Zoom"),
    TRACKER("Tracker"),
    SENSITIVITY("Sensitivity"),
    MUDDINESS("Muddiness"),
    EXPOSURE("Exposure"),
    FLASH("Flashlight"),
    FOCUS("Focus"),
    WHITE_BALANCE("White balance"),
    ISO("ISO"),
    SHUTTER("Shutter"),
    NIGHT("Night"),
    LOCK("Lock exposure"),
    CAMERA("Camera")
}

/** Feedback the controller asks for. Implemented on the watch with the vibrator. */
interface Haptics {
    /** A light tick: mode change, tracker nudge. */
    fun tick()

    /** A value stepped by one detent. */
    fun stepped()

    /** A committed action. */
    fun action()

    /** The value hit the end of its range; turning further does nothing. */
    fun limit() = tick()
}

/**
 * Rotary input for ONE camera.
 *
 * **Every detent counts.** Input arrives as a signed number of detents (positive is clockwise). There is no
 * cooldown, no debounce and no "ignore the camera's echo for a while" window: a detent is added to a running
 * total, whole detents are applied immediately (several at once if the bezel is spun fast), and only the
 * fraction left over is carried to the next event. Reversing direction discards the carry so the first click
 * the other way is felt at once.
 *
 * Each detent steps from [ConfigSync.effective], which is the camera's last state with this viewer's own
 * unacknowledged edits laid over it, so two quick turns never both start from the same stale number.
 *
 * The tracker is a mode like any other, but placing it is touch: the box edits live (nothing to commit),
 * dragging or tapping puts its centre where the finger is, and the bezel sizes it.
 *
 * Bound to one camera's [sync]: it cannot edit any other camera.
 */
class BezelController(
    private val sync: ConfigSync,
    /** Latest status from the camera, for the lens readout and for which modes exist on this camera. */
    private val status: () -> Msg.Status?,
    private val send: (Msg) -> Unit,
    private val haptics: Haptics,
    private val scope: CoroutineScope
) {
    private val _mode = MutableStateFlow(BezelMode.ZOOM)
    val mode: StateFlow<BezelMode> = _mode.asStateFlow()

    /** Extra words for the centre of the screen (which lens, how to place the tracker); clears itself. */
    private val _overlay = MutableStateFlow<String?>(null)
    val overlay: StateFlow<String?> = _overlay.asStateFlow()

    /** True while the tracker is being placed: the UI hides every button so nothing is in the way. */
    private val _placing = MutableStateFlow(false)
    val placing: StateFlow<Boolean> = _placing.asStateFlow()

    private var overlayJob: Job? = null
    private var carry = 0f
    private var atLimit = false

    // ── Mode ─────────────────────────────────────────────────────────

    /** Next ([direction] = 1) or previous (-1) mode that exists on this camera. */
    fun cycleMode(direction: Int = 1) {
        val all = BezelMode.entries
        val step = if (direction >= 0) 1 else -1
        var i = _mode.value.ordinal
        repeat(all.size) {
            i = (i + step + all.size) % all.size
            if (isAvailable(all[i], status())) {
                setMode(all[i])
                haptics.tick()
                return
            }
        }
    }

    fun setMode(mode: BezelMode) {
        _mode.value = mode
        _placing.value = mode == BezelMode.TRACKER
        carry = 0f
        atLimit = false
        if (mode == BezelMode.TRACKER) showOverlay("Drag to place · turn to size", TRACKER_HINT_MS) else clearOverlay()
    }

    // ── Rotary ───────────────────────────────────────────────────────

    /** [detents] is signed: positive is clockwise. Fractions are carried; nothing is ever thrown away. */
    fun onDetents(detents: Float) {
        if (detents == 0f || detents.isNaN() || detents.isInfinite()) return
        if (carry != 0f && (carry > 0f) != (detents > 0f)) carry = 0f
        carry += detents
        val whole = carry.toInt()
        if (whole == 0) return
        carry -= whole
        applySteps(whole)
    }

    private fun applySteps(n: Int) {
        val current = sync.effective ?: return
        var changed = false
        fun edit(f: (CameraConfig) -> CameraConfig) {
            sync.edit(f)?.let { send(it); changed = true }
        }
        val cam = current.camera

        when (_mode.value) {
            BezelMode.ZOOM -> {
                val next = steppedZoom(cam.zoom, n)
                if (abs(next - cam.zoom) > 0.0005f) {
                    edit { it.copy(camera = it.camera.copy(zoom = next)) }
                    val st = status()
                    // Names what is actually producing the picture: "digital" means a crop of the main sensor.
                    showOverlay(
                        if (st?.digitalZoom == true) "digital" else "${st?.lensLabel.orEmpty().ifBlank { if (next < 0.75f) "0.5x" else "1x" }} lens",
                        OVERLAY_MS
                    )
                }
            }

            BezelMode.TRACKER -> {
                val z = current.detect.zone
                val size = (z.width + n * SIZE_STEP).coerceIn(Zone.MIN_SIZE, MAX_TRACKER)
                if (abs(size - z.width) > 0.0005f) {
                    val cx = z.x + z.width / 2f
                    val cy = z.y + z.height / 2f
                    val grown = Zone(cx - size / 2f, cy - size / 2f, size, size).sanitized()
                    edit { it.copy(detect = it.detect.copy(zone = grown)) }
                }
            }

            BezelMode.SENSITIVITY -> {
                val next = (current.detect.sensitivity + n * SENSITIVITY_STEP).coerceIn(1, 100)
                if (next != current.detect.sensitivity) edit { it.copy(detect = it.detect.copy(sensitivity = next)) }
            }

            BezelMode.MUDDINESS -> {
                val next = (current.stream.muddiness + n * MUDDINESS_STEP).coerceIn(0, 100)
                if (next != current.stream.muddiness) edit { it.copy(stream = it.stream.copy(muddiness = next)) }
            }

            BezelMode.EXPOSURE -> {
                val next = (cam.exposure + n).coerceIn(-12, 12)
                if (next != cam.exposure) edit { it.copy(camera = it.camera.copy(exposure = next)) }
            }

            BezelMode.FLASH -> {
                val on = n > 0
                if (on != cam.torch) edit { it.copy(camera = it.camera.copy(torch = on)) }
            }

            BezelMode.FOCUS -> {
                val list = CameraSteps.FOCUS
                val i = (CameraSteps.nearest(list, cam.focus) + n).coerceIn(0, list.lastIndex)
                if (list[i] != cam.focus) edit { it.copy(camera = it.camera.copy(focus = list[i])) }
            }

            BezelMode.WHITE_BALANCE -> {
                val all = WhiteBalance.entries
                val next = all[(cam.whiteBalance.ordinal + n).coerceIn(0, all.lastIndex)]
                if (next != cam.whiteBalance) edit { it.copy(camera = it.camera.copy(whiteBalance = next)) }
            }

            BezelMode.ISO -> {
                val list = CameraSteps.ISO
                val i = (CameraSteps.nearest(list, cam.iso) + n).coerceIn(0, list.lastIndex)
                if (list[i] != cam.iso) edit { it.copy(camera = it.camera.copy(iso = list[i])) }
            }

            BezelMode.SHUTTER -> {
                val list = CameraSteps.SHUTTER
                val i = (CameraSteps.nearest(list, cam.shutterDenom) + n).coerceIn(0, list.lastIndex)
                if (list[i] != cam.shutterDenom) edit { it.copy(camera = it.camera.copy(shutterDenom = list[i])) }
            }

            BezelMode.NIGHT -> {
                val all = NightMode.entries
                val next = all[(cam.night.ordinal + n).coerceIn(0, all.lastIndex)]
                if (next != cam.night) edit { it.copy(camera = it.camera.copy(night = next)) }
            }

            BezelMode.LOCK -> {
                val on = n > 0
                if (on != cam.lockExposure) edit { it.copy(camera = it.camera.copy(lockExposure = on)) }
            }

            BezelMode.CAMERA -> {
                val next = if (n > 0) Facing.FRONT else Facing.REAR
                if (next != cam.facing) edit { it.copy(camera = it.camera.copy(facing = next)) }
            }
        }

        if (changed) {
            atLimit = false
            haptics.stepped()
        } else if (!atLimit) {
            // Say it once when the end is reached, not on every further click.
            atLimit = true
            haptics.limit()
        }
    }

    /**
     * [n] detents along a logarithmic zoom curve over [MIN_ZOOM]..[MAX_ZOOM]. A linear step feels enormous
     * at 0.5x and invisible at 10x; stepping the logarithm makes every detent feel the same size across the
     * whole range. Walking below ~0.75x reaches the ultrawide lens by itself.
     */
    private fun steppedZoom(current: Float, n: Int): Float {
        val lnMin = ln(MIN_ZOOM.toDouble())
        val lnMax = ln(MAX_ZOOM.toDouble())
        val slider = ((ln(current.coerceIn(MIN_ZOOM, MAX_ZOOM).toDouble()) - lnMin) / (lnMax - lnMin)).coerceIn(0.0, 1.0)
        val next = (slider + n * (1.0 / ZOOM_DETENTS)).coerceIn(0.0, 1.0)
        return exp(lnMin + next * (lnMax - lnMin)).toFloat()
    }

    // ── Tracker placement ────────────────────────────────────────────

    /**
     * Centre the tracker on a point, as fractions of the sensor frame. Live: the camera follows the finger.
     * Only while the tracker is the current mode, so a stray touch elsewhere can never move it.
     */
    fun moveZone(nx: Float, ny: Float) {
        if (_mode.value != BezelMode.TRACKER) return
        val z = sync.effective?.detect?.zone ?: return
        val moved = z.copy(x = nx - z.width / 2f, y = ny - z.height / 2f).sanitized()
        if (moved == z) return
        sync.edit { it.copy(detect = it.detect.copy(zone = moved)) }?.let(send)
    }

    // ── internals ────────────────────────────────────────────────────

    private fun showOverlay(text: String, ms: Long) {
        _overlay.value = text
        overlayJob?.cancel()
        overlayJob = scope.launch {
            delay(ms)
            _overlay.value = null
        }
    }

    private fun clearOverlay() {
        overlayJob?.cancel()
        _overlay.value = null
    }

    companion object {
        const val ZOOM_DETENTS = 24.0
        const val SENSITIVITY_STEP = 5
        const val MUDDINESS_STEP = 10
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 10f
        const val SIZE_STEP = 0.04f
        const val MAX_TRACKER = 0.95f
        const val OVERLAY_MS = 1200L
        const val TRACKER_HINT_MS = 2200L

        /** Does this mode exist on this camera? With no status yet, everything does. */
        fun isAvailable(mode: BezelMode, status: Msg.Status?): Boolean {
            if (status == null) return true
            return when (mode) {
                BezelMode.FLASH -> status.hasTorch
                BezelMode.FOCUS -> status.manualFocus
                BezelMode.ISO, BezelMode.SHUTTER -> status.manualExposure
                else -> true
            }
        }

        /** The mode's current value as words for the menu chip. */
        fun describe(mode: BezelMode, c: CameraConfig): String = when (mode) {
            BezelMode.ZOOM -> "%.1fx".format(c.camera.zoom)
            BezelMode.TRACKER -> "${(c.detect.zone.width * 100).roundToInt()}%"
            BezelMode.SENSITIVITY -> "${c.detect.sensitivity}%"
            BezelMode.MUDDINESS -> "${c.stream.muddiness}"
            BezelMode.EXPOSURE -> if (c.camera.exposure > 0) "+${c.camera.exposure}" else "${c.camera.exposure}"
            BezelMode.FLASH -> if (c.camera.torch) "On" else "Off"
            BezelMode.FOCUS -> when (c.camera.focus) { -1 -> "Auto"; 0 -> "Far"; 100 -> "Near"; else -> "${c.camera.focus}%" }
            BezelMode.WHITE_BALANCE -> when (c.camera.whiteBalance) {
                WhiteBalance.AUTO -> "Auto"; WhiteBalance.DAYLIGHT -> "Daylight"; WhiteBalance.CLOUDY -> "Cloudy"
                WhiteBalance.TUNGSTEN -> "Tungsten"; WhiteBalance.FLUORESCENT -> "Fluorescent"
            }
            BezelMode.ISO -> if (c.camera.iso == 0) "Auto" else "${c.camera.iso}"
            BezelMode.SHUTTER -> if (c.camera.shutterDenom == 0) "Auto" else "1/${c.camera.shutterDenom}"
            BezelMode.NIGHT -> c.camera.night.name.lowercase().replaceFirstChar(Char::uppercase)
            BezelMode.LOCK -> if (c.camera.lockExposure) "On" else "Off"
            BezelMode.CAMERA -> if (c.camera.facing == Facing.FRONT) "Front" else "Rear"
        }

        /** Where the mode's value sits in its range, 0..1, for the gauge round the bezel. */
        fun fraction(mode: BezelMode, c: CameraConfig): Float {
            fun index(list: List<Int>, v: Int) = CameraSteps.nearest(list, v).toFloat() / list.lastIndex
            return when (mode) {
                BezelMode.ZOOM -> {
                    val lo = ln(MIN_ZOOM.toDouble()); val hi = ln(MAX_ZOOM.toDouble())
                    ((ln(c.camera.zoom.toDouble()) - lo) / (hi - lo)).toFloat()
                }
                BezelMode.TRACKER -> (c.detect.zone.width - Zone.MIN_SIZE) / (MAX_TRACKER - Zone.MIN_SIZE)
                BezelMode.SENSITIVITY -> c.detect.sensitivity / 100f
                BezelMode.MUDDINESS -> c.stream.muddiness / 100f
                BezelMode.EXPOSURE -> (c.camera.exposure + 12) / 24f
                BezelMode.FLASH -> if (c.camera.torch) 1f else 0f
                BezelMode.FOCUS -> index(CameraSteps.FOCUS, c.camera.focus)
                BezelMode.WHITE_BALANCE -> c.camera.whiteBalance.ordinal.toFloat() / WhiteBalance.entries.lastIndex
                BezelMode.ISO -> index(CameraSteps.ISO, c.camera.iso)
                BezelMode.SHUTTER -> index(CameraSteps.SHUTTER, c.camera.shutterDenom)
                BezelMode.NIGHT -> c.camera.night.ordinal.toFloat() / NightMode.entries.lastIndex
                BezelMode.LOCK -> if (c.camera.lockExposure) 1f else 0f
                BezelMode.CAMERA -> if (c.camera.facing == Facing.FRONT) 1f else 0f
            }.coerceIn(0f, 1f)
        }
    }
}
