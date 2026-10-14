package app.heat.core.camera

import app.heat.core.model.Facing
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * What the platform tells us about one camera id, reduced to what lens planning needs. The Android layer fills
 * this from `CameraCharacteristics`; everything below it is plain Kotlin and tested without a phone.
 */
data class CameraProfile(
    val id: String,
    val facing: Facing,
    /** Can produce ordinary pictures (not a depth, IR or monochrome helper). */
    val usable: Boolean,
    /** For a logical multi-camera: the physical ids behind it. Empty for an ordinary camera. */
    val physicalIds: List<String> = emptyList(),
    val zoomMin: Float = 1f,
    val zoomMax: Float = 1f,
    /** 35mm-equivalent focal length of this camera, if the sensor and lens reported it. */
    val focal35: Float? = null,
    /** For a logical camera: each physical lens's own 35mm-equivalent focal length. */
    val physicalFocal35: Map<String, Float> = emptyMap()
)

/** How the picture is produced across lenses. */
enum class LensStrategy {
    /** One camera device. If it is a logical multi-camera the phone's own firmware moves between lenses. */
    LOGICAL,

    /** A logical camera with several physical streams open at once, cross-faded by the app. */
    BLEND,

    /** Separate camera devices per lens; changing lens means changing device. */
    SWITCH
}

data class LensEntry(
    /** The camera id that is this lens (a physical id, or a standalone camera's own id). */
    val id: String,
    /** Field of view relative to the main lens: 0.5, 1, 3... */
    val ratio: Float,
    /** What the lens button says: "0.5", "1", "3". */
    val label: String
)

/**
 * The cameras of one side of the phone, arranged along the zoom axis, and the plan for using them.
 *
 * Built by [build] from whatever the phone reports. The result is deliberately forgiving about what phones
 * expose: some give one logical camera spanning every lens, some expose each lens as its own camera id, some
 * only one lens at all. Each case gets a working strategy and a plain-language [note] the diagnostics page shows,
 * so when a phone behaves unexpectedly the reason is readable instead of guessed at.
 */
data class LensGraph(
    val strategy: LensStrategy,
    /** The camera device to open first (the main lens, or the logical camera). */
    val deviceId: String,
    /** Ascending by ratio. Never empty. */
    val lenses: List<LensEntry>,
    val zoomMin: Float,
    val zoomMax: Float,
    /** Standalone lens cameras reachable only by switching device, by lens id. Empty unless [strategy] is SWITCH. */
    val switchTargets: Map<String, Float> = emptyMap(),
    val note: String = ""
) {
    /** The tightest lens that still covers the view at [zoom]. */
    fun lensFor(zoom: Float): LensEntry = lenses.lastOrNull { it.ratio <= zoom * 1.0001f } ?: lenses.first()

    companion object {
        /**
         * Within about a third of the range either way is the same lens: phones expose a second front camera a quarter
         * wider than the first, and report slightly different focal lengths for one lens.
         */
        private const val SAME_LENS = 1.35f
        private const val MAIN_EQUIV_MM = 26f

        fun build(profiles: List<CameraProfile>, facing: Facing, allowBlend: Boolean): LensGraph? {
            val side = profiles.filter { it.facing == facing && it.usable }
            if (side.isEmpty()) return null

            // The logical camera, if there is one: widest zoom span among those that front several lenses.
            val logical = side.filter { it.physicalIds.size >= 2 }.maxByOrNull { it.zoomMax - it.zoomMin }

            // Every real lens on this side, with its 35mm equivalent.
            data class Found(val id: String, val f35: Float, val standalone: Boolean)
            val found = ArrayList<Found>()
            if (logical != null) {
                for (pid in logical.physicalIds) {
                    val f = logical.physicalFocal35[pid] ?: continue
                    found += Found(pid, f, standalone = false)
                }
            }
            for (c in side) {
                if (c.physicalIds.isNotEmpty()) continue
                val f = c.focal35 ?: continue
                found += Found(c.id, f, standalone = true)
            }

            if (found.isEmpty()) {
                // Nothing reported a focal length: take the widest-ranging camera and trust it to zoom.
                val only = side.maxByOrNull { it.zoomMax - it.zoomMin }!!
                return LensGraph(
                    LensStrategy.LOGICAL, only.id, listOf(LensEntry(only.id, 1f, "1")),
                    only.zoomMin.coerceAtMost(1f), only.zoomMax.coerceAtLeast(1f),
                    note = "no focal lengths reported: one camera, zoom only"
                )
            }

            // The main lens is the one nearest a 26mm-equivalent view.
            val main = found.minBy { abs(ln(it.f35 / MAIN_EQUIV_MM)) }
            val withRatio = found.map { it to it.f35 / main.f35 }.sortedBy { it.second }

            // The same lens can be reported twice (as a physical id inside the logical camera and as its own
            // standalone id). Keep one per ratio, preferring the physical id inside the logical camera.
            val distinct = ArrayList<Pair<Found, Float>>()
            for ((f, r) in withRatio) {
                val i = distinct.indexOfFirst { (_, r2) -> max(r, r2) / min(r, r2) < SAME_LENS }
                if (i < 0) distinct += f to r
                else if (distinct[i].first.standalone && !f.standalone) distinct[i] = f to r
            }

            val entries = distinct.map { (f, r) -> LensEntry(f.id, r, labelFor(r)) }
            val logicalCoversAll = logical != null && logical.zoomMin <= entries.first().ratio * 1.1f &&
                logical.zoomMax >= entries.last().ratio * 0.95f

            if (logical != null && logicalCoversAll) {
                val strategy = if (allowBlend && entries.size >= 2) LensStrategy.BLEND else LensStrategy.LOGICAL
                return LensGraph(
                    strategy, logical.id, entries,
                    min(logical.zoomMin, entries.first().ratio), max(logical.zoomMax, entries.last().ratio).coerceAtMost(MAX_ZOOM),
                    note = "logical camera ${logical.id} fronts ${entries.joinToString { "${it.id}@${it.label}x" }}"
                )
            }

            // No single camera covers every lens: the main lens is the device we hold, the rest are switched to.
            val mainEntry = entries.minBy { abs(ln(it.ratio)) }
            val holder = if (logical != null && logical.zoomMin <= 1.01f) logical.id else mainEntry.id
            val targets = entries.filter { it.id != holder && it.id != mainEntry.id }
                .associate { it.id to it.ratio }
            val strategy = if (entries.size >= 2) LensStrategy.SWITCH else LensStrategy.LOGICAL
            val holderMax = side.firstOrNull { it.id == holder }?.zoomMax ?: 1f
            return LensGraph(
                strategy, holder, entries,
                entries.first().ratio.coerceAtMost(1f), max(holderMax, entries.last().ratio).coerceAtMost(MAX_ZOOM),
                targets,
                note = "separate lens cameras: ${entries.joinToString { "${it.id}@${it.label}x" }}"
            )
        }

        const val MAX_ZOOM = 10f

        /** "0.5", "1", "2", "3", "5": the way phones name lenses. */
        fun labelFor(ratio: Float): String {
            val halves = Math.round(ratio * 2f) / 2f
            return if (halves == halves.toInt().toFloat()) halves.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, halves)
        }
    }
}
