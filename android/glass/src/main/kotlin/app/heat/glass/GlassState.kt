package app.heat.glass

import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset

/** What the device can do. Refraction needs AGSL RuntimeShader, which arrived in Android 13. */
object GlassSupport {
    val shader: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}

/** Global material constants. Distances are in dp so the look is identical on every density. */
@Immutable
data class GlassLook(
    /** Refractive index. Real glass is ~1.5; higher bends the rim harder. */
    val ior: Float = 1.5f,
    /** Slab thickness driving how far the rim displaces the backdrop. */
    val thicknessDp: Float = 12f,
    /** Spread between the R, G and B displacements (colour fringe). */
    val dispersion: Float = 0.12f,
    /** Rim highlight strength. */
    val specular: Float = 1f,
    /** Frost radius. Keep tiny: Apple's glass is clear, not blurry. */
    val frostDp: Float = 0.4f,
    val saturation: Float = 1.18f,
    val shadow: Float = 1f,
    /** Unit vector pointing toward the light, in screen space (default: top-left). */
    val lightX: Float = -0.55f,
    val lightY: Float = -0.83f
)

/** The outline a piece of glass takes. Everything else (bevel, refraction, light) follows from it. */
sealed interface GlassShape {
    /** A rounded rectangle. A radius at or beyond half the shorter side gives a capsule, or a circle when square. */
    data class Rounded(val radiusDp: Float) : GlassShape

    /** A circular ring, e.g. the bezel of a round watch face. The host rect is its bounding square. */
    data class Ring(val thicknessDp: Float) : GlassShape

    companion object {
        val Capsule: GlassShape = Rounded(Float.MAX_VALUE)
        fun rounded(dp: Float): GlassShape = Rounded(dp)
    }
}

/** One registered piece of glass, in window pixels. */
@Immutable
internal data class LensSpec(
    val id: Int,
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val radiusPx: Float,
    val ring: Boolean,
    val ringThicknessPx: Float,
    val bezelPx: Float,
    val strength: Float,
    val tint: Float,
    val scrim: Float
)

/**
 * The set of glass shapes currently on screen, and the knobs that govern how
 * they are drawn. One per [GlassHost].
 *
 * Surfaces register themselves from layout, so the lenses follow the UI by
 * construction: there is no second place to keep a position in sync. Reading
 * [version] from the effect's layer block is what invalidates just that layer
 * (not recomposition) when any lens moves.
 */
@Stable
class GlassState(maxQuality: Int = if (GlassSupport.shader) 2 else 0) {

    /** 0 = tint and rim only, 1 = refraction, 2 = refraction + dispersion + frost. Adjusted by [GlassGovernor]. */
    var quality by mutableIntStateOf(maxQuality)

    /** The highest quality the governor may climb back to. */
    var maxQuality = maxQuality

    var look by mutableStateOf(GlassLook())

    /** True once the shader compiled on this device. Until then (or forever, on old Android) surfaces draw the flat look. */
    var shaderReady by mutableStateOf(false)
        internal set

    internal var version by mutableIntStateOf(0)
        private set

    internal var hostOrigin: Offset = Offset.Zero

    private val lenses = ArrayList<LensSpec>(MAX_LENSES)
    private var nextId = 1

    internal fun newId(): Int = nextId++

    internal fun set(spec: LensSpec) {
        val i = lenses.indexOfFirst { it.id == spec.id }
        if (i < 0) {
            lenses += spec
            version++
        } else if (lenses[i] != spec) {
            lenses[i] = spec
            version++
        }
    }

    internal fun remove(id: Int) {
        if (lenses.removeAll { it.id == id }) version++
    }

    internal fun hostMoved(origin: Offset) {
        if (origin != hostOrigin) {
            hostOrigin = origin
            version++
        }
    }

    /** True while there is any glass to draw. */
    internal val isEmpty: Boolean get() = lenses.isEmpty()

    /**
     * Packs the top [MAX_LENSES] lenses (later registration = on top) into the
     * flat arrays the shader reads. Returns how many were written.
     */
    internal fun pack(rect: FloatArray, shape: FloatArray, look: FloatArray): Int {
        val start = (lenses.size - MAX_LENSES).coerceAtLeast(0)
        var n = 0
        for (i in start until lenses.size) {
            val s = lenses[i]
            val o = n * 4
            rect[o] = s.left - hostOrigin.x
            rect[o + 1] = s.top - hostOrigin.y
            rect[o + 2] = s.right - hostOrigin.x
            rect[o + 3] = s.bottom - hostOrigin.y
            shape[o] = s.radiusPx
            shape[o + 1] = if (s.ring) 1f else 0f
            shape[o + 2] = s.ringThicknessPx
            shape[o + 3] = 0f
            look[o] = s.bezelPx
            look[o + 1] = s.strength
            look[o + 2] = s.tint
            look[o + 3] = s.scrim
            n++
        }
        return n
    }

    /** Whether [id] is among the lenses the shader will actually draw. Others fall back to the flat look. */
    internal fun isShaded(id: Int): Boolean {
        val start = (lenses.size - MAX_LENSES).coerceAtLeast(0)
        for (i in start until lenses.size) if (lenses[i].id == id) return true
        return false
    }

    companion object {
        /** Must match the array sizes in liquid_glass.agsl. */
        const val MAX_LENSES = 10
    }
}
