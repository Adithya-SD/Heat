package app.heat.glass

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.asComposeRenderEffect

/**
 * Owns the compiled AGSL program and turns a [GlassState] into a RenderEffect.
 *
 * The shader is compiled once per process. If a driver rejects it for any
 * reason, [create] returns null and the UI falls back to the flat glass look:
 * a shader problem must degrade the material, never take the app down.
 */
@RequiresApi(33)
internal class GlassRenderer private constructor(private val shader: RuntimeShader) {

    private val rect = FloatArray(GlassState.MAX_LENSES * 4)
    private val shape = FloatArray(GlassState.MAX_LENSES * 4)
    private val lookArr = FloatArray(GlassState.MAX_LENSES * 4)

    /** Builds the effect for the current lenses, or null when there is nothing to draw. */
    fun effect(
        state: GlassState, width: Float, height: Float, density: Float
    ): androidx.compose.ui.graphics.RenderEffect? {
        if (state.isEmpty || width <= 0f || height <= 0f) return null
        java.util.Arrays.fill(rect, 0f)
        java.util.Arrays.fill(shape, 0f)
        java.util.Arrays.fill(lookArr, 0f)
        val n = state.pack(rect, shape, lookArr)
        if (n == 0) return null

        val look = state.look
        shader.setFloatUniform("uSize", width, height)
        shader.setFloatUniform("uCount", n.toFloat())
        shader.setFloatUniform("uRect", rect)
        shader.setFloatUniform("uShape", shape)
        shader.setFloatUniform("uLook", lookArr)
        shader.setFloatUniform("uLight", look.lightX, look.lightY)
        shader.setFloatUniform("uIor", look.ior)
        shader.setFloatUniform("uThick", look.thicknessDp * density)
        shader.setFloatUniform("uDispersion", look.dispersion)
        shader.setFloatUniform("uSpec", look.specular)
        shader.setFloatUniform("uBlur", (look.frostDp * density).coerceAtLeast(0.5f))
        shader.setFloatUniform("uQuality", state.quality.coerceIn(0, 2).toFloat())
        shader.setFloatUniform("uShadow", look.shadow)
        shader.setFloatUniform("uSaturation", look.saturation)
        return RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }

    companion object {
        private const val TAG = "HeatGlass"
        @Volatile private var source: String? = null

        fun create(context: Context): GlassRenderer? = try {
            val src = source ?: context.applicationContext.resources
                .openRawResource(R.raw.liquid_glass).bufferedReader().use { it.readText() }
                .also { source = it }
            GlassRenderer(RuntimeShader(src))
        } catch (t: Throwable) {
            Log.w(TAG, "glass shader unavailable, using the flat look", t)
            null
        }
    }
}
