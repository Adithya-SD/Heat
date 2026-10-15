package app.heat.core.motion

import app.heat.core.model.DetectSettings
import app.heat.core.model.NightMode
import app.heat.core.model.Zone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Frame-differencing motion engine built for low light and zero steady-state
 * allocation. Pure Kotlin so it is tested on the JVM with generated frames:
 * a phone left in a dark room is the target, and no emulator can reproduce
 * sensor grain or a headlight sweep.
 *
 * ## One pass, then a tiny grid
 * A single sequential pass over the Y plane box-averages it into a small
 * fixed grid of cells. That pass *is* the blur and the subsample at once.
 * Everything afterwards works on ~1.7k cells instead of ~300k pixels, so the
 * per-frame cost stops depending on camera resolution. Two grids ping-pong,
 * so after warm-up the hot path allocates nothing. Rows are indexed through
 * `rowStride` (cameras pad their rows; indexing as `y * width` shears the
 * image and wrecks the differencing).
 *
 * ## Three guards that make it survive the dark
 *  - **Noise floor.** Sensor grain scales with ISO, which scales with
 *    darkness. The engine tracks a running estimate of frame-to-frame noise
 *    while the scene is quiet and sets the per-cell trigger *above* it, so
 *    grain can never cross the line. A fixed threshold cannot do this.
 *  - **Global illumination rejection.** A headlight sweep, a light switch or
 *    the camera's own auto-exposure step shift every cell the same way at
 *    once. Subtracting the *median* signed delta cancels that while a person
 *    (who changes some cells and not others) survives it. The median, not the
 *    mean: the mean is dragged by anything large entering frame, which then
 *    lifts the untouched background over the threshold too.
 *  - **Persistence.** Real motion lasts longer than one frame. A hit counter
 *    that ramps on detection and decays on quiet frames discards single-frame
 *    pops yet tolerates the occasional dip that walking pace produces.
 */
class MotionEngine {

    // ── Tunables ─────────────────────────────────────────────────────

    private val dayGridWidth = 48
    private val dayGridHeight = 36

    /**
     * Coarser grid at night. Bigger cells average more pixels: noise falls with
     * the square root of the sample count while real motion, being spatially
     * correlated, does not.
     */
    private val nightGridWidth = 32
    private val nightGridHeight = 24

    private val nightEnterLuma = 55f
    private val nightExitLuma = 75f // hysteresis, so dusk does not oscillate

    private val noiseMultiplier = 4.0f
    private val absoluteFloorDay = 8f
    private val absoluteFloorNight = 5f
    private val noiseAlpha = 0.05f
    private val calibrationFrames = 12

    // ── Configuration ────────────────────────────────────────────────

    private var zone: Zone = Zone()
    private var sensitivity: Int = 50
    private var cooldownMs: Long = 0L
    private var nightMode: NightMode = NightMode.AUTO
    private var persistenceFrames = 2
    private var rejectLightChanges = true
    private var enabled = false

    // ── Grid state (ping-pong, allocated once) ───────────────────────

    private var gridW = 0
    private var gridH = 0
    private var gridA = IntArray(0)
    private var gridB = IntArray(0)
    private var useA = true
    private var inZone = BooleanArray(0)
    private var zoneDirty = true
    private var sums = IntArray(0)
    private var counts = IntArray(0)
    private val deltaHistogram = IntArray(511) // bin i == delta (i - 255)

    // ── Adaptive state ───────────────────────────────────────────────

    private var framesSeen = 0
    private var noiseEstimate = 0f
    private var sceneLuma = 128f
    private var nightActive = false
    private var consecutiveHits = 0
    private var lastTriggerMs = 0L
    private var inEpisode = false
    private var escalation = 0

    /** Outcome of one frame. Mutable and reused so the loop stays allocation-free; copy what you keep. */
    class Result {
        /** Motion crossed the threshold on this frame (edge-triggered, subject to cooldown). */
        var triggered = false
            internal set

        /** Fraction of tracked cells that changed, 0..100. */
        var changedPercent = 0f
            internal set

        /** Mean scene luminance, 0..255. */
        var luma = 0f
            internal set

        /** True while the night profile is engaged. */
        var night = false
            internal set

        /** Rises while motion keeps repeating inside one episode; 0 for the first hit. */
        var escalation = 0
            internal set

        /** True until the noise floor has settled. */
        var calibrating = false
            internal set
    }

    private val result = Result()

    // ── API ──────────────────────────────────────────────────────────

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        reset()
    }

    fun isEnabled(): Boolean = enabled

    fun setZone(value: Zone) {
        zone = value.sanitized()
        zoneDirty = true
    }

    fun setSensitivity(value: Int) {
        sensitivity = value.coerceIn(1, 100)
    }

    fun setCooldownSeconds(value: Int) {
        cooldownMs = value.coerceAtLeast(0) * 1000L
    }

    fun setNightMode(value: NightMode) {
        nightMode = value
    }

    fun setPersistenceFrames(value: Int) {
        persistenceFrames = value.coerceIn(1, 10)
    }

    fun setRejectLightChanges(value: Boolean) {
        rejectLightChanges = value
    }

    /** Applies everything the user can tune in one call. */
    fun configure(detect: DetectSettings, night: NightMode) {
        setSensitivity(detect.sensitivity)
        setCooldownSeconds(detect.cooldownSec)
        setPersistenceFrames(detect.persistence)
        setRejectLightChanges(detect.rejectLightChanges)
        setZone(detect.zone)
        setNightMode(night)
    }

    /** Drop all learned state. Call on camera restart or lens change. */
    fun reset() {
        framesSeen = 0
        noiseEstimate = 0f
        consecutiveHits = 0
        inEpisode = false
        escalation = 0
        useA = true
        java.util.Arrays.fill(gridA, 0)
        java.util.Arrays.fill(gridB, 0)
    }

    /**
     * Analyses one luminance plane.
     *
     * @param rowStride bytes per row, often larger than [width].
     * @param pixelStride bytes per pixel within a row; 1 for a Y plane.
     */
    fun analyze(
        y: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int = width,
        pixelStride: Int = 1,
        nowMs: Long = System.currentTimeMillis()
    ): Result {
        result.triggered = false
        result.escalation = 0
        if (!enabled || width <= 0 || height <= 0) {
            result.calibrating = false
            return result
        }

        val targetW = if (nightActive) nightGridWidth else dayGridWidth
        val targetH = if (nightActive) nightGridHeight else dayGridHeight
        ensureGrid(targetW, targetH)
        if (zoneDirty) rebuildZoneMap()

        val current = if (useA) gridA else gridB
        val previous = if (useA) gridB else gridA

        val lumaSum = downsample(y, width, height, rowStride, pixelStride, current)
        sceneLuma = lumaSum / (gridW * gridH).toFloat()
        updateNightProfile()

        result.luma = sceneLuma
        result.night = nightActive

        framesSeen++
        // The first frame after a grid change has no valid predecessor.
        if (framesSeen <= 1) {
            useA = !useA
            result.calibrating = true
            return result
        }

        // Subtract the median signed delta so any uniform brightness shift cancels out.
        val medianDelta = if (rejectLightChanges) medianDelta(current, previous) else 0f

        var absResidualSum = 0f
        val cells = gridW * gridH
        for (i in 0 until cells) absResidualSum += abs((current[i] - previous[i]) - medianDelta)
        val meanAbsResidual = absResidualSum / cells

        val calibrating = framesSeen <= calibrationFrames
        result.calibrating = calibrating

        val absoluteFloor = if (nightActive) absoluteFloorNight else absoluteFloorDay
        val cellThreshold = max(noiseMultiplier * noiseEstimate, absoluteFloor)

        var tracked = 0
        var changed = 0
        for (i in 0 until cells) {
            if (!inZone[i]) continue
            tracked++
            if (abs((current[i] - previous[i]) - medianDelta) > cellThreshold) changed++
        }
        result.changedPercent = if (tracked > 0) changed * 100f / tracked else 0f

        val hit = tracked > 0 && result.changedPercent > thresholdFor(sensitivity)

        // Learn noise only while the scene reads as quiet, so a person walking
        // through never teaches the engine to ignore people.
        if (!hit) {
            noiseEstimate =
                if (noiseEstimate == 0f) meanAbsResidual
                else noiseEstimate + noiseAlpha * (meanAbsResidual - noiseEstimate)
        }

        useA = !useA

        if (calibrating) {
            consecutiveHits = 0
            return result
        }

        if (hit) {
            // Ramp up on a hit, decay on a miss rather than resetting outright:
            // smooth motion at 30 fps changes a sliver of the frame per step and
            // will occasionally dip under threshold. Decay still starves a
            // one-frame pop, which never climbs past 1.
            consecutiveHits = (consecutiveHits + 1).coerceAtMost(persistenceFrames + 2)
            if (consecutiveHits >= persistenceFrames) {
                val sinceLast = nowMs - lastTriggerMs
                if (!inEpisode || sinceLast >= cooldownMs) {
                    lastTriggerMs = nowMs
                    escalation = if (inEpisode) (escalation + 1).coerceAtMost(MAX_ESCALATION) else 0
                    inEpisode = true
                    result.triggered = true
                    result.escalation = escalation
                }
            }
        } else {
            consecutiveHits = (consecutiveHits - 1).coerceAtLeast(0)
            // The episode ends once the scene has been quiet for two cooldowns.
            if (inEpisode && nowMs - lastTriggerMs > cooldownMs * 2) {
                inEpisode = false
                escalation = 0
            }
        }
        return result
    }

    // ── Internals ────────────────────────────────────────────────────

    /** One sequential pass over the Y plane, accumulating each pixel into its cell. */
    private fun downsample(
        y: ByteArray, width: Int, height: Int, rowStride: Int, pixelStride: Int, out: IntArray
    ): Long {
        java.util.Arrays.fill(sums, 0)
        java.util.Arrays.fill(counts, 0)

        // Fixed-point row/col to cell mapping avoids a divide per pixel.
        val xScale = (gridW.toLong() shl 16) / width
        val yScale = (gridH.toLong() shl 16) / height

        var total = 0L
        for (row in 0 until height) {
            val cellY = ((row * yScale) shr 16).toInt().coerceAtMost(gridH - 1)
            val rowBase = row * rowStride
            val cellRowBase = cellY * gridW
            var col = 0
            while (col < width) {
                val idx = rowBase + col * pixelStride
                if (idx >= y.size) break
                val v = y[idx].toInt() and 0xFF
                val cellX = ((col * xScale) shr 16).toInt().coerceAtMost(gridW - 1)
                val c = cellRowBase + cellX
                sums[c] += v
                counts[c]++
                total += v
                col++
            }
        }
        for (i in sums.indices) {
            val n = counts[i]
            out[i] = if (n > 0) sums[i] / n else 0
        }
        return total / max(1, (width.toLong() * height) / (gridW.toLong() * gridH))
    }

    private fun ensureGrid(w: Int, h: Int) {
        if (gridW == w && gridH == h && gridA.isNotEmpty()) return
        gridW = w
        gridH = h
        val n = w * h
        gridA = IntArray(n)
        gridB = IntArray(n)
        sums = IntArray(n)
        counts = IntArray(n)
        inZone = BooleanArray(n)
        zoneDirty = true
        framesSeen = 0
        consecutiveHits = 0
    }

    private fun rebuildZoneMap() {
        for (cy in 0 until gridH) {
            val fy = (cy + 0.5f) / gridH
            for (cx in 0 until gridW) {
                val fx = (cx + 0.5f) / gridW
                inZone[cy * gridW + cx] = fx >= zone.x && fx < zone.right && fy >= zone.y && fy < zone.bottom
            }
        }
        zoneDirty = false
    }

    private fun updateNightProfile() {
        nightActive = when (nightMode) {
            NightMode.DAY -> false
            NightMode.NIGHT -> true
            NightMode.AUTO -> when {
                nightActive && sceneLuma > nightExitLuma -> false
                !nightActive && sceneLuma < nightEnterLuma -> true
                else -> nightActive
            }
        }
    }

    /**
     * Sensitivity 1..100 to the share of tracked cells that must change.
     * Anchored so the middle of the slider (50) is ~2%: a person walking at
     * normal pace shifts only that much of the frame between two frames at 30
     * fps, so a steeper curve made mid-slider silently miss people and pushed
     * everyone to 100.
     */
    private fun thresholdFor(sensitivity: Int): Float {
        val s = sensitivity.coerceIn(1, 100) / 100f
        val curve = (1.0 - s).let { it * it * sqrt(it) } // ~pow(1 - s, 2.5)
        return (12f * curve.toFloat()) + 0.15f
    }

    /** Median of the per-cell signed delta via a 511-bin histogram: exact, no sort, no allocation. */
    private fun medianDelta(current: IntArray, previous: IntArray): Float {
        java.util.Arrays.fill(deltaHistogram, 0)
        val n = gridW * gridH
        for (i in 0 until n) {
            deltaHistogram[(current[i] - previous[i]).coerceIn(-255, 255) + 255]++
        }
        val target = n / 2
        var running = 0
        for (bin in deltaHistogram.indices) {
            running += deltaHistogram[bin]
            if (running > target) return (bin - 255).toFloat()
        }
        return 0f
    }

    /** Diagnostics for the tuning UI. */
    fun debugState(): String =
        "grid=${gridW}x$gridH luma=${sceneLuma.roundToInt()} night=$nightActive " +
            "noise=${"%.2f".format(noiseEstimate)} frames=$framesSeen"

    companion object {
        const val MAX_ESCALATION = 3
    }
}
