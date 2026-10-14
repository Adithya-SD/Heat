package app.heat.phone.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heat.glass.GlassShape
import app.heat.glass.GlassState
import app.heat.glass.GlassSurface
import app.heat.glass.HeatText
import app.heat.glass.Heat
import app.heat.glass.HeatIcon
import app.heat.glass.HeatIconView
import kotlin.math.roundToInt

/** Clickable with a light haptic tick and no ripple (glass has its own press feedback). */
@Composable
fun Modifier.tappable(onClick: () -> Unit, source: MutableInteractionSource? = null): Modifier {
    val view = LocalView.current
    val src = source ?: remember { MutableInteractionSource() }
    return this.clickable(interactionSource = src, indication = null) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        onClick()
    }
}

/** A round glass button with an icon. Brightens while pressed or [active]. */
@Composable
fun GlassIconButton(
    state: GlassState,
    icon: HeatIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    active: Boolean = false,
    tint: Color = Heat.Text,
    refract: Boolean = true
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val lift by animateFloatAsState(if (pressed) 0.20f else if (active) 0.14f else 0.045f, tween(120), label = "lift")
    GlassSurface(
        state, modifier.size(size).tappable(onClick, src),
        shape = GlassShape.Rounded(size.value / 2), tint = lift, refract = refract, bezel = (size.value * 0.3f).dp
    ) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            HeatIconView(icon, if (active) Heat.Orange else tint, (size.value * 0.46f).dp)
        }
    }
}

/** A capsule of glass with a text label, optionally accented. */
@Composable
fun GlassPillButton(
    state: GlassState,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    accent: Color? = null,
    refract: Boolean = true,
    subLabel: String? = null
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val lift by animateFloatAsState(if (pressed) 0.22f else 0.06f, tween(120), label = "lift")
    GlassSurface(
        state, modifier.height(height).tappable(onClick, src),
        shape = GlassShape.Capsule, tint = lift, refract = refract, bezel = (height.value * 0.32f).dp
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 26.dp).height(height), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            HeatText(label, size = 17, weight = FontWeight.SemiBold, color = accent ?: Heat.Text, align = TextAlign.Center)
            if (subLabel != null) HeatText(subLabel, size = 12, color = Heat.TextDim, align = TextAlign.Center)
        }
    }
}

// ── settings building blocks (flat glass: cheap, and the shaded lenses are saved for the live view) ──

@Composable
fun SettingsGroup(state: GlassState, title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        HeatText(title.uppercase(), size = 12, weight = FontWeight.SemiBold, color = Heat.TextFaint, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
        GlassSurface(state, Modifier.fillMaxWidth(), shape = GlassShape.Rounded(22f), refract = false) {
            Column { content() }
        }
    }
}

@Composable
private fun RowShell(label: String, hint: String?, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            HeatText(label, size = 16)
            if (hint != null) HeatText(hint, size = 12, color = Heat.TextFaint)
        }
        trailing()
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, hint: String? = null) {
    RowShell(label, hint) {
        val knob by animateFloatAsState(if (checked) 1f else 0f, tween(160), label = "knob")
        Box(
            Modifier.size(width = 52.dp, height = 32.dp).clip(CircleShape)
                .background(if (checked) Heat.Green.copy(alpha = 0.85f) else Color(0x33FFFFFF))
                .tappable({ onChange(!checked) })
        ) {
            Box(
                Modifier.padding(3.dp).padding(start = (20.dp * knob)).size(26.dp).clip(CircleShape).background(Color.White)
            )
        }
    }
}

@Composable
fun <T> SegmentRow(label: String, options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit, hint: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
        HeatText(label, size = 16)
        if (hint != null) HeatText(hint, size = 12, color = Heat.TextFaint)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x22FFFFFF)).padding(3.dp)) {
            for ((text, value) in options) {
                val on = value == selected
                Box(
                    Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(11.dp))
                        .background(if (on) Color(0x55FFFFFF) else Color.Transparent)
                        .tappable({ onSelect(value) }),
                    contentAlignment = Alignment.Center
                ) {
                    HeatText(text, size = 14, weight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = if (on) Heat.Text else Heat.TextDim)
                }
            }
        }
    }
}

@Composable
fun SliderRow(
    label: String, value: Int, range: IntRange, onChange: (Int) -> Unit,
    format: (Int) -> String = { it.toString() }, hint: String? = null, step: Int = 1
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            HeatText(label, size = 16)
            HeatText(format(value), size = 16, color = Heat.Orange, weight = FontWeight.SemiBold)
        }
        if (hint != null) HeatText(hint, size = 12, color = Heat.TextFaint)
        Spacer(Modifier.height(8.dp))
        GlassSlider(value, range, step, onChange)
    }
}

@Composable
fun GlassSlider(value: Int, range: IntRange, step: Int, onChange: (Int) -> Unit) {
    val view = LocalView.current
    val frac = (value - range.first).toFloat() / (range.last - range.first).coerceAtLeast(1)
    Box(
        Modifier.fillMaxWidth().height(36.dp)
            .pointerInput(range, step) {
                fun setFrom(x: Float) {
                    val f = (x / size.width).coerceIn(0f, 1f)
                    val raw = range.first + f * (range.last - range.first)
                    val v = (raw / step).roundToInt() * step
                    onChange(v.coerceIn(range.first, range.last))
                }
                detectHorizontalDragGestures(onDragStart = { setFrom(it.x) }) { change, _ -> setFrom(change.position.x) }
            }
            .pointerInput(range, step) {
                detectTapGestures { off ->
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    val f = (off.x / size.width).coerceIn(0f, 1f)
                    onChange(((range.first + f * (range.last - range.first)) / step).roundToInt().times(step).coerceIn(range.first, range.last))
                }
            }
    ) {
        Canvas(Modifier.fillMaxWidth().height(36.dp)) {
            val cy = size.height / 2
            drawRoundRect(Color(0x30FFFFFF), Offset(0f, cy - 3.dp.toPx()), Size(size.width, 6.dp.toPx()), CornerRadius(3.dp.toPx()))
            drawRoundRect(Heat.Orange, Offset(0f, cy - 3.dp.toPx()), Size(size.width * frac, 6.dp.toPx()), CornerRadius(3.dp.toPx()))
            val x = (size.width * frac).coerceIn(14.dp.toPx(), size.width - 14.dp.toPx())
            drawCircle(Color.White, 13.dp.toPx(), Offset(x, cy))
            drawCircle(Color(0x33000000), 13.dp.toPx(), Offset(x, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        }
    }
}

@Composable
fun StepperRow(
    label: String, value: Int, range: IntRange, onChange: (Int) -> Unit,
    step: Int = 1, format: (Int) -> String = { it.toString() }, hint: String? = null
) {
    RowShell(label, hint) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton(HeatIcon.Minus, enabled = value > range.first) { onChange((value - step).coerceAtLeast(range.first)) }
            HeatText(format(value), size = 16, weight = FontWeight.SemiBold, color = Heat.Orange, modifier = Modifier.width(72.dp), align = TextAlign.Center)
            StepButton(HeatIcon.Plus, enabled = value < range.last) { onChange((value + step).coerceAtMost(range.last)) }
        }
    }
}

@Composable
private fun StepButton(icon: HeatIcon, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(Color(0x28FFFFFF)).tappable({ if (enabled) onClick() }),
        contentAlignment = Alignment.Center
    ) { HeatIconView(icon, if (enabled) Heat.Text else Heat.TextFaint, 18.dp) }
}

@Composable
fun InfoRow(label: String, value: String) {
    RowShell(label, null) { HeatText(value, size = 15, color = Heat.TextDim) }
}

@Composable
fun ActionRow(label: String, onClick: () -> Unit, hint: String? = null, danger: Boolean = false) {
    Box(Modifier.fillMaxWidth().tappable(onClick)) {
        RowShell(label, hint) {
            HeatIconView(HeatIcon.Chevron, if (danger) Heat.Red else Heat.TextFaint, 20.dp)
        }
    }
}

@Composable
fun RowDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(1.dp).background(Heat.Hairline))
}
