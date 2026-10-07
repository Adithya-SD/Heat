package app.heat.glass

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/**
 * Two stacked layers sharing one coordinate space:
 *
 *  1. [backdrop], the thing the glass bends: a camera preview, a gradient,
 *     anything. The glass shader runs as a RenderEffect over exactly this layer.
 *  2. [content], the foreground: glass surfaces ([GlassSurface]) and their
 *     labels and icons. Drawn above the effect, so text is never refracted.
 *
 * Surfaces tell the [state] where they are; the shader draws the material under
 * them. If the shader cannot run (Android < 13, or a driver rejects it) the
 * surfaces draw a flat glass look themselves and nothing else changes.
 */
@Composable
fun GlassHost(
    state: GlassState,
    modifier: Modifier = Modifier,
    backdrop: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val context = LocalContext.current
    val renderer = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) GlassRenderer.create(context) else null
    }
    SideEffect { state.shaderReady = renderer != null }

    if (renderer != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        FrameGovernor(state)
    }

    Box(modifier.onGloballyPositioned { state.hostMoved(it.positionInWindow()) }) {
        val layerModifier =
            if (renderer != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Modifier.matchParentSize().glassLayer(state, renderer)
            } else {
                Modifier.matchParentSize()
            }
        Box(layerModifier, content = backdrop)
        content()
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun Modifier.glassLayer(state: GlassState, renderer: GlassRenderer): Modifier = graphicsLayer {
    // Reading these inside the layer block is what re-runs it when a lens moves or the quality changes,
    // without recomposing anything.
    @Suppress("UNUSED_EXPRESSION")
    state.version
    renderEffect = renderer.effect(state, size.width, size.height, density)
}

/** Watches frame deadlines and steps [GlassState.quality] down (or back up) to keep the UI smooth. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun FrameGovernor(state: GlassState) {
    val view = LocalView.current
    DisposableEffect(view, state) {
        val window = view.context.findWindow()
        if (window == null) {
            onDispose { }
        } else {
            val governor = QualityGovernor(state.maxQuality)
            val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                if (state.isEmpty || !state.shaderReady) return@OnFrameMetricsAvailableListener
                val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                val deadline = metrics.getMetric(FrameMetrics.DEADLINE)
                governor.onFrame(missedDeadline = deadline > 0 && total > deadline)?.let { state.quality = it }
            }
            val handler = Handler(Looper.getMainLooper())
            window.addOnFrameMetricsAvailableListener(listener, handler)
            onDispose { window.removeOnFrameMetricsAvailableListener(listener) }
        }
    }
}

private fun Context.findWindow(): Window? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c.window
        c = c.baseContext
    }
    return null
}
