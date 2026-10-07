package app.heat.core.control

import app.heat.core.model.ConfigSync
import app.heat.core.model.Msg
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

/**
 * What the rotating bezel (or crown) currently adjusts.
 *
 * The same six modes in the same order as the earlier app. That order is muscle
 * memory, and there is no good reason to disturb it.
 */
enum class BezelMode(val displayName: String) {
    ZOOM("Zoom"),
    TRACKER("Tracker"),
    SENSITIVITY("Sensitivity"),
    MUDDINESS("Muddiness"),
    EXPOSURE("Exposure"),
    FLASH("Flashlight")
}

/** Feedback the controller asks for. Implemented on the watch with the vibrator. */
interface Haptics {
    /** A light tick: mode change, tracker nudge. */
    fun tick()

    /** A value stepped by one detent. */
    fun stepped()

    /** A committed action. */
    fun action()
}

/**
 * Rotary input and tracker placement for ONE camera, ported from the earlier
 * app's bezel behaviour (detent sizes, step sizes, log-scale zoom, the
 * tap-to-aim / turn-to-size tracker flow, the readout text).
 *
 * What changed underneath: the earlier app kept its own "optimistic" copies of
 * sensitivity, muddiness and exposure to stop quick turns reading a stale echo
 * (two fast detents both started from the same old number, so the second did
 * nothing). Here every detent steps from [ConfigSync.effective], which already
 * has this viewer's un-acknowledged edits laid over the camera's state, so
 * that whole class of bug is gone by construction and there is no 2-second
 * "ignore the phone" window to get wrong.
 *
 * Bound to one camera's [sync]: it cannot edit any other camera.
 */
class BezelController(
    private val sync: ConfigSync,
    /** Latest status from the camera, for the lens readout. */
    private val status: () -> Msg.Status?,
    private val send: (Msg) -> Unit,
    private val haptics: Haptics,
    private val scope: CoroutineScope
) {
    private val _mode = MutableStateFlow(BezelMode.ZOOM)
    val mode: StateFlow<BezelMode> = _mode.asStateFlow()

    /** Short readout of what the last turn did; clears itself after [OVERLAY_MS]. */
    private val _overlay = MutableStateFlow<String?>(null)
    val overlay: StateFlow<String?> = _overlay.asStateFlow()

    private val _placing = MutableStateFlow(false)
    val placing: StateFlow<Boolean> = _placing.asStateFlow()

    /** The tracker box while it is being aimed; null otherwise (then show the camera's own). */
    private val _placingZone = MutableStateFlow<Zone?>(null)
    val placingZone: StateFlow<Zone?> = _placingZone.asStateFlow()

    private var overlayJob: Job? = null
    private var remainder = 0f

    // ── Mode ─────────────────────────────────────────────────────────

    fun cycleMode() {
        val values = BezelMode.entries
        setMode(values[(_mode.value.ordinal + 1) % values.size])
        haptics.tick()
    }

    /**
     * Aiming the tracker is a bezel mode like any other, not its own button:
     * turn to size the box, tap to move it, exactly as the other modes already
     * work (turn to adjust, tap to act). Costs no new gesture and no space on
     * a small face.
     */
    fun setMode(mode: BezelMode) {
        val was = _mode.value
        _mode.value = mode
        remainder = 0f
        if (mode == BezelMode.TRACKER) {
            beginPlacement()
        } else {
            if (was == BezelMode.TRACKER) commitPlacement()
            showOverlay(mode.displayName)
        }
    }

    // ── Rotary ───────────────────────────────────────────────────────

    /** [deltaPixels]: scroll distance from the rotary event; positive = clockwise. */
    fun onRotary(deltaPixels: Float) {
        if (_placing.value) {
            resizeZone(deltaPixels)
            return
        }

        remainder += deltaPixels
        val threshold = if (_mode.value == BezelMode.ZOOM) ZOOM_DETENT else DETENT
        if (abs(remainder) < threshold) return
        val step = if (remainder > 0f) 1 else -1
        remainder = 0f

        val current = sync.effective ?: return

        // The readout is built from the value just sent, never from the camera's echo,
        // so it always shows where you are now, not where you were a round trip ago.
        val readout: String? = when (_mode.value) {
            BezelMode.TRACKER -> null // handled by the placing branch above

            BezelMode.ZOOM -> {
                val from = current.camera.zoom
                val next = steppedZoom(from, step)
                if (abs(next - from) > 0.001f) {
                    edit { it.copy(camera = it.camera.copy(zoom = next)) }
                    val st = status()
                    // Names what is actually producing the picture: "digital" means a crop of the main sensor.
                    val lens = if (st?.digitalZoom == true) {
                        "digital"
                    } else {
                        "${st?.lensLabel.orEmpty().ifBlank { if (next < 0.75f) "0.5x" else "1x" }} lens"
                    }
                    "Zoom ${"%.1f".format(next)}x · $lens"
                } else null
            }

            BezelMode.SENSITIVITY -> {
                val from = current.detect.sensitivity
                val next = (from + step * SENSITIVITY_STEP).coerceIn(1, 100)
                if (next != from) {
                    edit { it.copy(detect = it.detect.copy(sensitivity = next)) }
                    "Sensitivity $next%"
                } else null
            }

            BezelMode.MUDDINESS -> {
                val from = current.stream.muddiness
                val next = (from + step * MUDDINESS_STEP).coerceIn(0, 100)
                if (next != from) {
                    edit { it.copy(stream = it.stream.copy(muddiness = next)) }
                    "Muddiness $next"
                } else null
            }

            BezelMode.EXPOSURE -> {
                val from = current.camera.exposure
                val next = (from + step).coerceIn(-10, 10)
                if (next != from) {
                    edit { it.copy(camera = it.camera.copy(exposure = next)) }
                    "Exposure ${if (next > 0) "+" else ""}$next"
                } else null
            }

            BezelMode.FLASH -> {
                val next = step > 0
                if (next != current.camera.torch) {
                    edit { it.copy(camera = it.camera.copy(torch = next)) }
                    "Flash ${if (next) "ON" else "OFF"}"
                } else null
            }
        }

        if (readout != null) {
            haptics.stepped()
            showOverlay(readout)
        }
    }

    /**
     * One detent along a logarithmic zoom curve over [MIN_ZOOM]..[MAX_ZOOM]. A linear
     * step feels enormous at 0.5x and invisible at 10x; stepping the logarithm makes
     * every detent feel the same size across the whole range. Walking below ~0.75x
     * reaches the ultrawide lens by itself.
     */
    private fun steppedZoom(current: Float, step: Int): Float {
        val lnMin = ln(MIN_ZOOM.toDouble())
        val lnMax = ln(MAX_ZOOM.toDouble())
        val slider = ((ln(current.coerceIn(MIN_ZOOM, MAX_ZOOM).toDouble()) - lnMin) / (lnMax - lnMin)).coerceIn(0.0, 1.0)
        val next = (slider + step * (1.0 / ZOOM_DETENTS)).coerceIn(0.0, 1.0)
        return exp(lnMin + next * (lnMax - lnMin)).toFloat()
    }

    // ── Tracker placement ────────────────────────────────────────────

    /** Keeps the box exactly where and how big it already was: you can nudge it without resizing from scratch. */
    fun beginPlacement() {
        _placingZone.value = sync.effective?.detect?.zone
        _placing.value = true
        showOverlay("Tap to aim · turn to size")
    }

    fun commitPlacement() {
        if (!_placing.value) return
        val zone = _placingZone.value
        _placing.value = false
        _placingZone.value = null
        if (zone != null) edit { it.copy(detect = it.detect.copy(zone = zone)) }
        haptics.action()
        showOverlay("Tracker set")
    }

    /** Centre the tracker on a normalized point in the frame. Only while placing. */
    fun moveZone(nx: Float, ny: Float) {
        val z = _placingZone.value ?: return
        _placingZone.value = z.copy(x = nx - z.width / 2f, y = ny - z.height / 2f).sanitized()
        haptics.tick()
    }

    private fun resizeZone(deltaPixels: Float) {
        remainder += deltaPixels
        if (abs(remainder) < DETENT) return
        val grow = remainder > 0f
        remainder = 0f

        val z = _placingZone.value ?: return
        val size = (z.width + if (grow) SIZE_STEP else -SIZE_STEP).coerceIn(Zone.MIN_SIZE, 0.95f)
        val cx = z.x + z.width / 2f
        val cy = z.y + z.height / 2f
        _placingZone.value = Zone(cx - size / 2f, cy - size / 2f, size, size).sanitized()
        haptics.tick()
        showOverlay("Size ${(size * 100).toInt()}%")
    }

    // ── internals ────────────────────────────────────────────────────

    private fun edit(transform: (app.heat.core.model.CameraConfig) -> app.heat.core.model.CameraConfig) {
        sync.edit(transform)?.let(send)
    }

    private fun showOverlay(text: String) {
        _overlay.value = text
        overlayJob?.cancel()
        overlayJob = scope.launch {
            delay(OVERLAY_MS)
            _overlay.value = null
        }
    }

    companion object {
        const val DETENT = 8f
        const val ZOOM_DETENT = 6f
        const val ZOOM_DETENTS = 24.0
        const val SENSITIVITY_STEP = 5
        const val MUDDINESS_STEP = 10
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 10f
        const val SIZE_STEP = 0.04f
        const val OVERLAY_MS = 1500L
    }
}
