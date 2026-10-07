package app.heat.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * A piece of liquid glass.
 *
 * Place it inside the `content` of a [GlassHost]. Its size and position come
 * from layout like any other composable, and it reports them so the shader
 * draws the refractive material exactly under it. Its own [content] (text,
 * icons) is drawn normally on top, never distorted.
 *
 * **Animate a glass surface through layout** (size, offset, `animateContentSize`),
 * not through `graphicsLayer` transforms: layout changes are seen by the shader
 * in the same frame, while pure layer transforms are not reported.
 *
 * At most [GlassState.MAX_LENSES] surfaces are shaded at once; any beyond that
 * (and everything on devices without AGSL, or when [refract] is false) gets
 * the flat glass look, which is cheap and still reads as glass. Use [refract]
 * = false for small controls inside a larger shaded panel.
 */
@Composable
fun GlassSurface(
    state: GlassState,
    modifier: Modifier = Modifier,
    shape: GlassShape = GlassShape.Rounded(24f),
    bezel: Dp = 14.dp,
    strength: Float = 1f,
    tint: Float = 0.045f,
    scrim: Float = 0.10f,
    refract: Boolean = true,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val density = LocalDensity.current.density
    val id = remember(state) { state.newId() }

    DisposableEffect(id, refract) {
        onDispose { state.remove(id) }
    }

    Box(
        modifier
            .onGloballyPositioned { c ->
                if (!refract) {
                    state.remove(id)
                    return@onGloballyPositioned
                }
                val p = c.positionInWindow()
                val w = c.size.width.toFloat()
                val h = c.size.height.toFloat()
                val half = min(w, h) / 2f
                val radius = when (shape) {
                    is GlassShape.Rounded -> min(shape.radiusDp * density, half)
                    is GlassShape.Ring -> half
                }
                state.set(
                    LensSpec(
                        id = id,
                        left = p.x, top = p.y, right = p.x + w, bottom = p.y + h,
                        radiusPx = radius,
                        ring = shape is GlassShape.Ring,
                        ringThicknessPx = (shape as? GlassShape.Ring)?.thicknessDp?.times(density) ?: 0f,
                        bezelPx = bezel.value * density,
                        strength = strength,
                        tint = tint,
                        scrim = scrim
                    )
                )
            }
            .drawBehind {
                // Subscribing to the version re-runs this when the shader takes over or gives up this surface.
                @Suppress("UNUSED_EXPRESSION")
                state.version
                val shaded = refract && state.shaderReady && state.quality >= 0 && state.isShaded(id)
                if (!shaded) drawFlatGlass(shape, density)
            },
        content = content
    )
}

/**
 * The fallback material: translucent gradient fill, thin lit rim, hairline
 * inner edge. No backdrop sampling, so it costs almost nothing, and it keeps
 * the same silhouette and light direction as the shader version.
 */
private fun DrawScope.drawFlatGlass(shape: GlassShape, density: Float) {
    val rim = 1.2f * density
    val fill = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.07f)))
    val edge = Brush.linearGradient(
        0f to Color.White.copy(alpha = 0.70f),
        0.5f to Color.White.copy(alpha = 0.10f),
        1f to Color.White.copy(alpha = 0.30f),
        start = Offset.Zero,
        end = Offset(size.width, size.height)
    )
    when (shape) {
        is GlassShape.Rounded -> {
            val r = min(shape.radiusDp * density, min(size.width, size.height) / 2f)
            drawRoundRect(fill, cornerRadius = CornerRadius(r))
            drawRoundRect(
                edge, topLeft = Offset(rim / 2, rim / 2), size = Size(size.width - rim, size.height - rim),
                cornerRadius = CornerRadius((r - rim / 2).coerceAtLeast(0f)), style = Stroke(rim)
            )
        }

        is GlassShape.Ring -> {
            val thickness = shape.thicknessDp * density
            val outer = min(size.width, size.height) / 2f
            drawCircle(fill, radius = outer - thickness / 2f, style = Stroke(thickness))
            drawCircle(edge, radius = outer - rim / 2f, style = Stroke(rim))
            drawCircle(edge, radius = outer - thickness + rim / 2f, style = Stroke(rim))
        }
    }
}
