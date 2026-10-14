package app.heat.core.camera

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * One physical lens, described by where it sits on the zoom axis: [ratio] is its field of view relative to the
 * main lens (0.5 for an ultrawide, 1 for the main lens, 3 for a 3x telephoto).
 */
data class Lens(val id: String, val ratio: Float)

/**
 * One picture to draw: a lens, how much to magnify its full frame ([scale], below 1 is a smaller window
 * inside the frame) and how opaque to draw it ([alpha]).
 */
data class Layer(val lens: Int, val scale: Float, val alpha: Float)

/** What to draw now, and which lenses must be streaming for it. */
data class BlendPlan(val layers: List<Layer>, val warm: Set<Int>)

/**
 * Decides how several lenses combine into one picture at any zoom, with no seam anywhere.
 *
 * The model is the one the best camera apps use, reduced to a rule. At zoom Z:
 *  - the **primary** lens is the tightest one that still covers the whole view, i.e. the one with the largest
 *    ratio not above Z, magnified by Z / ratio (so it is always at least full frame);
 *  - the **next** lens up (narrower, longer) is the one about to take over. As Z approaches its ratio it
 *    appears as a *window* in the middle of the picture: magnified by Z / ratio, which is below 1 until Z gets
 *    there, so the window grows as you zoom in and fills the frame exactly at its ratio, while fading in.
 *
 * Going the other way is the same rule read backwards, which is what produces the effect people recognise from
 * going 1x -> 0.5x on a phone: the 1x picture shrinks away and fades while the wider lens zooms out into place.
 * At every boundary the picture on screen is identical on both sides (the window is exactly full frame and fully
 * opaque), so a lens change cannot pop.
 *
 * The planner is pure arithmetic. The Android layer turns a [BlendPlan] into GL draw calls and into the set of
 * physical streams to request.
 */
class LensBlend(
    lenses: List<Lens>,
    /**
     * How far ahead of its ratio a lens starts to appear: it begins at ratio / [window]. Wider = a gentler,
     * longer cross-fade. 1.3 means the 3x lens begins to show at about 2.3x.
     */
    private val window: Float = 1.3f,
    /** Extra lead, as a factor, for waking a sensor before its picture is visible (a cold sensor takes a moment). */
    private val warmLead: Float = 1.12f
) {
    private val sorted = lenses.sortedBy { it.ratio }
    val lenses: List<Lens> get() = sorted

    init {
        require(sorted.isNotEmpty()) { "at least one lens" }
        require(window > 1f)
    }

    /** The widest and the longest reach, in zoom ratio: the range the user can ask for. */
    val minZoom: Float get() = sorted.first().ratio
    val maxZoom: Float get() = sorted.last().ratio

    /**
     * What to show at zoom [z]. [target] is where the zoom is heading (defaults to [z]): lenses it will cross
     * on the way are kept warm so each is already streaming when its turn comes.
     */
    fun plan(z: Float, target: Float = z): BlendPlan {
        val zc = max(z, sorted.first().ratio)
        val p = primaryIndex(zc)
        val layers = ArrayList<Layer>(2)
        layers += Layer(p, zc / sorted[p].ratio, 1f)

        val s = p + 1
        if (s < sorted.size) {
            val a = appearance(zc, sorted[s].ratio)
            if (a > 0f) layers += Layer(s, zc / sorted[s].ratio, a)
        }

        val warm = HashSet<Int>()
        warm += p
        val lo = min(zc, target)
        val hi = max(zc, target)
        for (i in sorted.indices) {
            val r = sorted[i].ratio
            // A lens is worth waking if its on-screen life (from when it starts to appear until the next lens
            // takes over) overlaps the zoom's journey, with a little lead.
            val begins = r / (window * warmLead)
            val ends = if (i + 1 < sorted.size) sorted[i + 1].ratio else Float.MAX_VALUE
            if (hi >= begins && lo < ends) warm += i
        }
        return BlendPlan(layers, warm)
    }

    private fun primaryIndex(z: Float): Int {
        var p = 0
        for (i in sorted.indices) if (sorted[i].ratio <= z * (1f + EPS)) p = i
        return p
    }

    /** 0 until [ratio]/[window], rising smoothly to exactly 1 at [ratio]. */
    private fun appearance(z: Float, ratio: Float): Float {
        val start = ratio / window
        if (z <= start) return 0f
        if (z >= ratio) return 1f
        val u = (ln(z) - ln(start)) / (ln(ratio) - ln(start))
        return u * u * (3f - 2f * u)
    }

    private companion object {
        const val EPS = 1e-4f
    }
}
