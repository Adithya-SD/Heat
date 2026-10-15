package app.heat.core.camera

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Turns "the zoom should be X" into the sequence of zoom values that makes the picture move like it is on
 * rails: no steps, no stutter, no overshoot, from any level to any level.
 *
 * It works on the *logarithm* of the zoom. That is what makes a zoom feel even: going from 1x to 2x and from
 * 5x to 10x are the same distance to the eye, and a linear motion would crawl through the first and rush the
 * second. Two critically damped springs in a row do the moving: the first chases the target, the second chases
 * the first. A single spring starts with a jolt (its acceleration is greatest at the very first instant);
 * chained, the motion eases in from rest, runs, and eases out, like the zoom of a real lens barrel. Critical
 * damping means it arrives as fast as it can without ever passing the target.
 *
 * The part that makes it "smart" is that the springs' stiffness follows the distance still to go. A bezel
 * click or a finger inch moves the target by a hair and the picture catches up in about a tenth of a second
 * (it feels attached to your hand); a tap on 0.5x from 5x asks for a long journey and gets a longer, gentler
 * one, so it reads as a camera moving rather than a cut. Because the *state* (position and velocity of both
 * springs) is never reset, a new target in mid-flight simply bends the path: there is no seam to see, whether
 * the retarget comes from a slider, a pinch, the watch, or a lens button.
 *
 * Each spring is advanced with its closed-form solution, so it is exact and unconditionally stable for any
 * frame time, including the long frames a camera pipeline sometimes produces.
 */
class ZoomSmoother(
    initial: Float,
    private val minZoom: Float,
    private val maxZoom: Float,
    /** Seconds to settle a tiny move. */
    private val baseSettleSec: Float = 0.11f,
    /** Extra seconds per natural-log unit of distance (a doubling is 0.69). */
    private val perLogUnitSec: Float = 0.42f
) {
    private var x1 = ln(initial.coerceIn(minZoom, maxZoom).toDouble())
    private var v1 = 0.0
    private var x2 = x1
    private var v2 = 0.0
    private var goal = x1

    /** Current stiffness. Eased toward what the distance asks for, so a change of target never jolts the speed. */
    private var omega = -1.0

    /** The zoom to show right now. */
    val zoom: Float get() = exp(x2).toFloat()

    /** Where it is heading. */
    val target: Float get() = exp(goal).toFloat()

    /** Rate of change of ln(zoom) per second; useful for deciding which lenses to warm up. */
    val velocity: Float get() = v2.toFloat()

    val settled: Boolean
        get() = abs(goal - x2) < EPS_X && abs(v2) < EPS_V && abs(goal - x1) < EPS_X && abs(v1) < EPS_V

    fun setTarget(zoom: Float) {
        goal = ln(zoom.coerceIn(minZoom, maxZoom).toDouble())
    }

    /** No animation: used when the picture itself changes (a different camera) and the old position means nothing. */
    fun snapTo(zoom: Float) {
        x1 = ln(zoom.coerceIn(minZoom, maxZoom).toDouble())
        x2 = x1
        goal = x1
        v1 = 0.0
        v2 = 0.0
        omega = -1.0
    }

    /** Advance by [dtSec] seconds and return the new zoom. */
    fun step(dtSec: Float): Float {
        if (dtSec <= 0f) return zoom
        if (settled) {
            snapTo(exp(goal).toFloat())
            return zoom
        }
        val n = ceil(dtSec / SLICE).toInt().coerceIn(1, MAX_SLICES)
        val h = dtSec.toDouble() / n
        repeat(n) {
            val want = omegaFor(abs(goal - x2))
            omega = if (omega < 0) want else omega + (want - omega) * (h / OMEGA_EASE_SEC).coerceAtMost(1.0)
            val nx1 = advance(x1, v1, goal, omega, h)
            val nv1 = advanceV(x1, v1, goal, omega, h)
            // The second spring chases the first; use the first's midpoint over the slice for accuracy.
            val chase = 0.5 * (x1 + nx1)
            val nx2 = advance(x2, v2, chase, omega, h)
            val nv2 = advanceV(x2, v2, chase, omega, h)
            x1 = nx1; v1 = nv1; x2 = nx2; v2 = nv2
        }
        if (settled) snapTo(exp(goal).toFloat())
        return zoom
    }

    private fun omegaFor(distance: Double): Double {
        val settle = baseSettleSec + perLogUnitSec * distance
        return (SETTLE_FACTOR / max(settle, 0.05)).coerceAtMost(MAX_OMEGA)
    }

    // Closed-form critically damped spring: position and velocity after t seconds toward a fixed target.
    private fun advance(x: Double, v: Double, to: Double, omega: Double, t: Double): Double {
        val y0 = x - to
        return to + (y0 + (v + omega * y0) * t) * exp(-omega * t)
    }

    private fun advanceV(x: Double, v: Double, to: Double, omega: Double, t: Double): Double {
        val y0 = x - to
        return (v - omega * (v + omega * y0) * t) * exp(-omega * t)
    }

    private companion object {
        /** Two springs in a row are within ~2% of the target after about this many time constants. */
        const val SETTLE_FACTOR = 8.4
        const val MAX_OMEGA = 90.0
        const val OMEGA_EASE_SEC = 0.12
        const val SLICE = 1.0 / 240
        const val MAX_SLICES = 128
        const val EPS_X = 1e-4
        const val EPS_V = 2e-3
    }
}
