package app.heat.phone.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heat.core.camera.LensEntry
import app.heat.core.control.BezelMode
import app.heat.glass.Heat
import app.heat.glass.HeatIcon
import app.heat.glass.HeatIconView
import app.heat.glass.HeatText
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

// ── matte surfaces ────────────────────────────────────────────────────

/** A solid dark surface with a hairline that is lit from above. Over live video this is what chrome is made of. */
fun Modifier.matte(shape: Shape, fill: Color = Heat.Matte): Modifier = this
    .clip(shape)
    .background(fill, shape)
    .border(1.dp, Brush.verticalGradient(listOf(Color(0x40FFFFFF), Color(0x0AFFFFFF))), shape)

fun BezelMode.icon(): HeatIcon = when (this) {
    BezelMode.ZOOM -> HeatIcon.Zoom
    BezelMode.TRACKER -> HeatIcon.Aim
    BezelMode.SENSITIVITY -> HeatIcon.Waves
    BezelMode.MUDDINESS -> HeatIcon.Drop
    BezelMode.EXPOSURE -> HeatIcon.Sun
    BezelMode.FLASH -> HeatIcon.Bolt
    BezelMode.FOCUS -> HeatIcon.Focus
    BezelMode.WHITE_BALANCE -> HeatIcon.Thermo
    BezelMode.ISO -> HeatIcon.Grain
    BezelMode.SHUTTER -> HeatIcon.Shutter
    BezelMode.NIGHT -> HeatIcon.Moon
    BezelMode.LOCK -> HeatIcon.Lock
    BezelMode.CAMERA -> HeatIcon.Flip
}

@Composable
private fun rememberTick(): () -> Unit {
    val view = LocalView.current
    return { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
}

// ── slide to arm ──────────────────────────────────────────────────────

/**
 * The one big control: a track you slide an ember orb along, to arm. A slide cannot be done by accident (a tap
 * only nudges the orb to show how), it leaves a trail of heat behind the orb, and it ticks under the thumb at
 * each quarter. While the arm delay runs the orb waits at the end with the count; slide back to cancel. Armed,
 * the whole track turns green; slide back to stand down.
 */
@Composable
fun ArmTrack(
    armed: Boolean,
    arming: Int,
    delaySec: Int,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tick = rememberTick()
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val busy = armed || arming > 0

    var widthPx by remember { mutableFloatStateOf(0f) }
    val orbPx = with(density) { 56.dp.toPx() }
    val padPx = with(density) { 6.dp.toPx() }
    val travel = (widthPx - orbPx - 2 * padPx).coerceAtLeast(1f)

    val progress = remember { Animatable(if (busy) 1f else 0f) }
    var dragging by remember { mutableStateOf(false) }
    var lastBucket by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(busy) {
        if (!dragging) progress.animateTo(if (busy) 1f else 0f, spring(dampingRatio = 0.72f, stiffness = 260f))
    }

    val c0 = if (armed) Color(0xFF1FA85C) else Heat.Ember0
    val c1 = if (armed) Heat.Green else Heat.Ember1
    val pulse = rememberInfiniteTransition(label = "pulse")
    val shimmer by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "shimmer")
    val breathe by pulse.animateFloat(0.55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "breathe")

    Box(
        modifier.fillMaxWidth().height(68.dp).matte(CircleShape).onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(busy, travel) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = {
                        dragging = false
                        scope.launch {
                            val p = progress.value
                            if (!busy && p > 0.78f) {
                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                onArm()
                                progress.animateTo(1f, spring(0.72f, 260f))
                            } else if (busy && p < 0.22f) {
                                view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                                onDisarm()
                                progress.animateTo(0f, spring(0.72f, 260f))
                            } else progress.animateTo(if (busy) 1f else 0f, spring(0.72f, 260f))
                        }
                    },
                    onDragCancel = {
                        dragging = false
                        scope.launch { progress.animateTo(if (busy) 1f else 0f) }
                    }
                ) { change, dx ->
                    change.consume()
                    scope.launch {
                        val next = (progress.value + dx / travel).coerceIn(0f, 1f)
                        progress.snapTo(next)
                        val bucket = (next * 4).toInt().toFloat()
                        if (bucket != lastBucket) { lastBucket = bucket; tick() }
                    }
                }
            }
            .pointerInput(busy) {
                detectTapGestures {
                    // A tap is a lesson, not an action: the orb leans toward the end and comes back.
                    if (!busy) scope.launch {
                        progress.animateTo(0.28f, tween(160))
                        progress.animateTo(0f, spring(0.5f, 380f))
                    }
                }
            }
    ) {
        // The trail of heat behind the orb.
        Canvas(Modifier.matchParentSize()) {
            val x = padPx + orbPx / 2 + travel * progress.value
            if (x > padPx + orbPx / 2 + 2f) {
                drawRoundRect(
                    Brush.horizontalGradient(listOf(c0.copy(alpha = 0f), c0.copy(alpha = 0.42f), c1.copy(alpha = 0.95f)), startX = 0f, endX = x),
                    size = Size(x, size.height),
                    cornerRadius = CornerRadius(size.height / 2)
                )
            }
        }

        // The words, fading as the orb passes over them.
        val label = when {
            arming > 0 -> "Arming in $arming"
            armed -> "Watching"
            else -> "Slide to arm"
        }
        val sub = when {
            arming > 0 -> "Slide back to cancel"
            armed -> "Slide back to stand down"
            delaySec > 0 -> "Then ${delaySec}s to leave the room"
            else -> null
        }
        Box(Modifier.matchParentSize().padding(start = 76.dp, end = 20.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.alpha((1f - progress.value * 1.6f).coerceIn(0f, 1f).let { if (busy) 1f else it })) {
                androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    HeatText(label, size = 17, weight = FontWeight.SemiBold, color = if (armed) Heat.Green else Heat.Text)
                    if (sub != null) HeatText(sub, size = 12, color = Heat.TextFaint)
                }
            }
        }

        // Chevrons that invite the slide.
        if (!busy) {
            Canvas(Modifier.align(Alignment.CenterEnd).padding(end = 22.dp).size(width = 42.dp, height = 20.dp).alpha(1f - progress.value)) {
                for (i in 0 until 3) {
                    val phase = ((shimmer + (2 - i) * 0.18f) % 1f)
                    val a = (1f - abs(phase - 0.5f) * 2f).coerceIn(0f, 1f) * 0.8f
                    val x = i * 13.dp.toPx()
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(x + 2.dp.toPx(), 3.dp.toPx()); lineTo(x + 8.dp.toPx(), size.height / 2); lineTo(x + 2.dp.toPx(), size.height - 3.dp.toPx())
                    }
                    drawPath(path, Heat.Ember1.copy(alpha = a), style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round))
                }
            }
        }

        // The orb.
        Box(
            Modifier.offset { IntOffset((padPx + travel * progress.value).roundToInt(), ((with(density) { 68.dp.toPx() } - orbPx) / 2f).roundToInt()) }
                .size(56.dp)
        ) {
            Canvas(Modifier.matchParentSize()) {
                val glow = if (busy) breathe else 1f
                drawCircle(c1.copy(alpha = 0.28f * glow), radius = size.minDimension / 2 + 7.dp.toPx())
                drawCircle(
                    Brush.radialGradient(listOf(Heat.EmberGlow.takeIf { !armed } ?: Color(0xFF8BF0B6), c1, c0), center = Offset(size.width * 0.38f, size.height * 0.32f), radius = size.minDimension * 0.85f)
                )
                drawArc(Color.White.copy(alpha = 0.38f), 200f, 70f, false, Offset(5.dp.toPx(), 5.dp.toPx()), Size(size.width - 10.dp.toPx(), size.height - 10.dp.toPx()), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                drawCircle(Color.Black.copy(alpha = 0.18f), radius = size.minDimension / 2, style = Stroke(1.dp.toPx()))
            }
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                HeatIconView(if (armed) HeatIcon.Check else HeatIcon.Shield, Color(0xFF1A0A02), 24.dp)
            }
        }
    }
}

// ── zoom ──────────────────────────────────────────────────────────────

/** The lens buttons: 0.5, 1, 2, 3... The active one shows the live zoom, like a phone camera's. */
@Composable
fun LensButtons(lenses: List<LensEntry>, maxZoom: Float, zoomState: () -> Float, onSelect: (Float) -> Unit, modifier: Modifier = Modifier) {
    val tick = rememberTick()
    // The lenses the phone really has, plus a 2x crop if nothing is near it, so there is always a quick step in.
    val stops = remember(lenses, maxZoom) {
        val base = lenses.map { it.ratio to it.label }.toMutableList()
        if (maxZoom >= 2f && base.none { abs(ln(it.first / 2f)) < 0.35f }) base += 2f to "2"
        base.sortedBy { it.first }
    }
    Row(modifier, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((ratio, label) in stops) {
            val z = zoomState()
            val next = stops.firstOrNull { it.first > ratio }?.first ?: Float.MAX_VALUE
            val active = z >= ratio * 0.94f && z < next * 0.94f
            val size = if (active) 46.dp else 38.dp
            Box(
                Modifier.size(size).matte(CircleShape, if (active) Heat.MatteHi else Heat.Matte)
                    .then(if (active) Modifier.border(1.5.dp, Heat.Ember1, CircleShape) else Modifier)
                    .tappable({ tick(); onSelect(ratio) }),
                contentAlignment = Alignment.Center
            ) {
                val shown = if (active) liveZoomText(z) else label
                HeatText(shown, size = if (active) 13 else 12, weight = FontWeight.SemiBold, color = if (active) Heat.Ember1 else Heat.TextDim, maxLines = 1)
            }
        }
    }
}

fun liveZoomText(z: Float): String = if (z < 1f) "%.1f".format(java.util.Locale.ROOT, z) else if (z < 10f) "%.1f×".format(java.util.Locale.ROOT, z) else "10×"

/**
 * A ruler for the zoom, on a logarithmic scale so every doubling is the same width. Drag it like a tape measure.
 * [zoomState] is read only while drawing, so the ruler can move every frame without recomposing anything.
 */
@Composable
fun LogRuler(
    zoomState: () -> Float,
    minZoom: Float,
    maxZoom: Float,
    stops: List<Float>,
    onTarget: (Float) -> Unit,
    onDragging: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val tick = rememberTick()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val pxPerLn = with(density) { 160.dp.toPx() }
    val ticks = remember(minZoom, maxZoom) { rulerTicks(minZoom, maxZoom) }
    var local by remember { mutableFloatStateOf(1f) }     // ln of the zoom being dragged to
    var dragging by remember { mutableStateOf(false) }
    val stopsNow by rememberUpdatedState(stops)

    Box(
        modifier.fillMaxWidth().height(64.dp).matte(androidx.compose.foundation.shape.RoundedCornerShape(22.dp))
            .pointerInput(minZoom, maxZoom) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true; local = ln(zoomState()); onDragging(true) },
                    onDragEnd = { dragging = false; onDragging(false) },
                    onDragCancel = { dragging = false; onDragging(false) }
                ) { change, dx ->
                    change.consume()
                    val before = local
                    local = (local - dx / pxPerLn).coerceIn(ln(minZoom), ln(maxZoom))
                    // A tick as the scale passes a lens: you can feel where the lenses are.
                    for (s in stopsNow) {
                        val l = ln(s)
                        if ((before < l && local >= l) || (before > l && local <= l)) tick()
                    }
                    onTarget(exp(local))
                }
            }
    ) {
        Canvas(Modifier.matchParentSize()) {
            val z = if (dragging) exp(local) else zoomState()
            val cx = size.width / 2f
            val lnZ = ln(z)
            val baseline = size.height - 16.dp.toPx()
            for (t in ticks) {
                val x = cx + (ln(t.value) - lnZ) * pxPerLn
                if (x < -20f || x > size.width + 20f) continue
                val edge = 1f - (abs(x - cx) / (size.width / 2f)).coerceIn(0f, 1f).let { it * it }
                val isLens = stops.any { abs(ln(it / t.value)) < 0.02f }
                val h = if (t.major) 22.dp.toPx() else 11.dp.toPx()
                val color = if (isLens) Heat.Ember1 else Color.White
                drawLine(color.copy(alpha = (if (t.major) 0.9f else 0.4f) * edge), Offset(x, baseline - h), Offset(x, baseline), strokeWidth = if (t.major) 2.dp.toPx() else 1.dp.toPx(), cap = StrokeCap.Round)
                if (t.major) {
                    val layout = measurer.measure(t.label, TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color.copy(alpha = 0.85f * edge)))
                    drawText(layout, topLeft = Offset(x - layout.size.width / 2f, baseline + 1.dp.toPx()))
                }
            }
            // The reading mark.
            drawLine(Heat.Ember1, Offset(cx, 6.dp.toPx()), Offset(cx, baseline), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(Heat.Ember1, 3.5.dp.toPx(), Offset(cx, 6.dp.toPx()))
        }
    }
}

private class RulerTick(val value: Float, val major: Boolean, val label: String)

private fun rulerTicks(min: Float, max: Float): List<RulerTick> {
    val out = ArrayList<RulerTick>()
    fun add(v: Float, major: Boolean, label: String = "") { if (v in (min * 0.999f)..(max * 1.001f)) out += RulerTick(v, major, label) }
    var v = 0.5f
    while (v < 1f) { add(v, v == 0.5f, "0.5"); v += 0.1f }
    v = 1f
    while (v < 2f) { add(v, abs(v - 1f) < 0.01f, "1"); v += 0.1f }
    v = 2f
    while (v < 5f) { add(v, v == 2f || v == 3f, "${v.toInt()}"); v += 0.5f }
    v = 5f
    while (v <= 10.001f) { add(v, v == 5f || v == 10f, "${v.toInt()}"); v += 1f }
    return out.distinctBy { (it.value * 100).roundToInt() }
}

// ── a ruler of discrete positions ─────────────────────────────────────

/**
 * The same tape-measure idea for anything with named positions (exposure steps, white balance, shutter speed):
 * the selected position sits under the mark, and dragging slides the labels past it, ticking at each one.
 */
@Composable
fun DetentRuler(labels: List<String>, index: Int, onIndex: (Int) -> Unit, modifier: Modifier = Modifier) {
    val tick = rememberTick()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val spacing = with(density) { 58.dp.toPx() }
    val scope = rememberCoroutineScope()
    val pos = remember { Animatable(index.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(index)
    LaunchedEffect(index) { if (!dragging) pos.animateTo(index.toFloat(), spring(0.8f, 420f)) }

    Box(
        modifier.fillMaxWidth().height(64.dp).matte(androidx.compose.foundation.shape.RoundedCornerShape(22.dp))
            .pointerInput(labels.size) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false; scope.launch { pos.animateTo(current.toFloat(), spring(0.8f, 420f)) } },
                    onDragCancel = { dragging = false; scope.launch { pos.animateTo(current.toFloat(), spring(0.8f, 420f)) } }
                ) { change, dx ->
                    change.consume()
                    scope.launch {
                        val next = (pos.value - dx / spacing).coerceIn(0f, (labels.size - 1).toFloat())
                        pos.snapTo(next)
                        val i = next.roundToInt()
                        if (i != current) { tick(); onIndex(i) }
                    }
                }
            }
    ) {
        Canvas(Modifier.matchParentSize()) {
            val cx = size.width / 2f
            val baseline = size.height - 20.dp.toPx()
            for (i in labels.indices) {
                val x = cx + (i - pos.value) * spacing
                if (x < -spacing || x > size.width + spacing) continue
                val dist = abs(x - cx)
                val edge = 1f - (dist / (size.width / 2f)).coerceIn(0f, 1f).let { it * it }
                val selected = i == pos.value.roundToInt()
                drawLine(Color.White.copy(alpha = (if (selected) 0.95f else 0.45f) * edge), Offset(x, baseline - 14.dp.toPx()), Offset(x, baseline), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
                if (i < labels.size - 1) drawLine(Color.White.copy(alpha = 0.22f * edge), Offset(x + spacing / 2, baseline - 7.dp.toPx()), Offset(x + spacing / 2, baseline), strokeWidth = 1.dp.toPx())
                val layout = measurer.measure(
                    labels[i],
                    TextStyle(fontSize = if (selected) 14.sp else 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = (if (selected) Heat.Ember1 else Color.White).copy(alpha = (if (selected) 1f else 0.75f) * edge))
                )
                drawText(layout, topLeft = Offset(x - layout.size.width / 2f, baseline + 1.dp.toPx()))
            }
            drawLine(Heat.Ember1, Offset(cx, 6.dp.toPx()), Offset(cx, baseline - 16.dp.toPx()), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(Heat.Ember1, 3.5.dp.toPx(), Offset(cx, 6.dp.toPx()))
        }
    }
}

// ── the row of controls ───────────────────────────────────────────────

/** One chip in the row under the ruler. A ruler control selects what the ruler shows; a switch just flips. */
@Composable
fun ControlChip(mode: BezelMode, value: String, selected: Boolean, isSwitch: Boolean, on: Boolean, onClick: () -> Unit) {
    val tint = if (selected || (isSwitch && on)) Heat.Ember1 else Heat.TextDim
    Row(
        Modifier.height(40.dp)
            .matte(CircleShape, if (selected) Heat.MatteHi else Heat.Matte)
            .then(if (selected || (isSwitch && on)) Modifier.border(1.5.dp, Heat.Ember1.copy(alpha = if (selected) 1f else 0.7f), CircleShape) else Modifier)
            .tappable(onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeatIconView(mode.icon(), tint, 17.dp)
        if (value.isNotEmpty()) {
            Spacer(Modifier.width(7.dp))
            HeatText(value, size = 13, weight = FontWeight.SemiBold, color = if (selected) Heat.Text else Heat.TextDim, maxLines = 1)
        }
    }
}

@Composable
fun ChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}
