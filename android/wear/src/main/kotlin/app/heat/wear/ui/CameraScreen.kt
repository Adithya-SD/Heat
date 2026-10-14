package app.heat.wear.ui

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.heat.core.control.BezelController
import app.heat.core.control.BezelMode
import app.heat.core.host.CameraClient
import app.heat.core.link.CarrierKind
import app.heat.core.link.LinkState
import app.heat.core.model.Zone
import app.heat.glass.GlassHost
import app.heat.glass.GlassShape
import app.heat.glass.GlassState
import app.heat.glass.GlassSurface
import app.heat.glass.Heat
import app.heat.glass.HeatIcon
import app.heat.glass.HeatIconView
import app.heat.glass.HeatText
import app.heat.wear.Rotary
import app.heat.wear.WatchHaptics
import app.heat.wear.WatchHub
import app.heat.wear.previewMatrix
import kotlinx.coroutines.delay

/**
 * The camera, full face, with one menu.
 *
 * The chip at the top is the menu: tap it and it moves to the next control, long-press for the previous; the
 * bezel changes whatever the chip currently shows. The ring of glass round the edge carries a gauge of the same
 * value. Choosing the tracker clears every button off the picture, so the box can be put exactly where it
 * belongs: touch (or drag) puts it there, the bezel makes it bigger or smaller, and the camera follows live.
 */
@Composable
fun CameraScreen(client: CameraClient, hub: WatchHub, glass: GlassState, onCameras: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by client.config.collectAsState()
    val status by client.status.collectAsState()
    val link by client.linkState.collectAsState()
    val hello by client.hello.collectAsState()

    val haptics = remember { WatchHaptics(ctx) }
    val bezel = remember(client) {
        BezelController(client.configSync(), { client.status.value }, { m -> client.send(m); client.refresh() }, haptics, scope)
    }
    val mode by bezel.mode.collectAsState()
    val overlay by bezel.overlay.collectAsState()
    val placing by bezel.placing.collectAsState()

    // Every click of the bezel goes straight to the controller, whatever has focus.
    DisposableEffect(bezel) {
        val mine: (Float) -> Unit = { bezel.onDetents(it) }
        Rotary.sink = mine
        onDispose { if (Rotary.sink === mine) Rotary.sink = null }
    }

    val decoder = remember(client) { hub.decoder(client) }
    val rendered by decoder.rendered.collectAsState()
    var surface by remember { mutableStateOf<Surface?>(null) }

    val stream = status?.stream
    val videoW = stream?.width ?: 0
    val videoH = stream?.height ?: 0
    val rotation = stream?.rotation ?: 0
    // The view's layout listener outlives this composition's values, so it reads the latest through this holder.
    val live = remember { IntArray(3) }
    live[0] = videoW; live[1] = videoH; live[2] = rotation

    // The decoder is already collecting frames; this just points it at the glass once both are ready.
    LaunchedEffect(surface, videoW, videoH) {
        val s = surface
        if (s != null && s.isValid && videoW > 0 && videoH > 0) decoder.attach(s, videoW, videoH)
    }
    DisposableEffect(decoder) { onDispose { decoder.detach() } }

    val cfg = config
    val armed = status?.armed == true
    val arming = status?.armingIn ?: 0
    val accent = if (armed) Heat.Green else Heat.Orange
    val fraction = cfg?.let { BezelController.fraction(mode, it) } ?: 0f

    val waited = rememberWaitSeconds(link.phase == LinkState.Phase.UP)

    GlassHost(
        glass,
        Modifier.fillMaxSize().background(Color.Black),
        backdrop = {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(Color.Black)) {
                AndroidView(
                    factory = { c ->
                        TextureView(c).apply {
                            isOpaque = true
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { surface = Surface(st) }
                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                    val s = surface
                                    surface = null
                                    // Release the Surface only after the decoder has let go of it.
                                    decoder.detach { s?.release() }
                                    return true
                                }
                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                            }
                            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                                (v as TextureView).setTransform(previewMatrix(v.width.toFloat(), v.height.toFloat(), live[0], live[1], live[2]))
                            }
                        }
                    },
                    update = { tv -> tv.setTransform(previewMatrix(tv.width.toFloat(), tv.height.toFloat(), videoW, videoH, rotation)) },
                    modifier = Modifier.fillMaxSize()
                )
                ZoneOverlay(cfg?.detect?.zone, videoW, videoH, rotation, active = placing)
            }
        }
    ) {
        Box(
            Modifier.fillMaxSize().pointerInput(placing, videoW, videoH, rotation) {
                if (placing) {
                    if (videoW <= 0 || videoH <= 0) return@pointerInput
                    // Put the box under the finger the instant it lands, and keep it there as the finger moves.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        placeZone(bezel, down.position, size.width, size.height, videoW, videoH, rotation)
                        do {
                            val event = awaitPointerEvent()
                            for (change in event.changes) {
                                if (change.positionChanged()) {
                                    placeZone(bezel, change.position, size.width, size.height, videoW, videoH, rotation)
                                    change.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                } else {
                    detectTapGestures(onLongPress = { onCameras() })
                }
            }
        ) {
            // The bezel itself, as glass; its gauge shows what the real bezel is currently adjusting.
            GlassSurface(glass, Modifier.fillMaxSize(), shape = GlassShape.Ring(13f), bezel = 6.dp, tint = 0.02f, scrim = 0.06f) {}
            Gauge(fraction, accent, Modifier.fillMaxSize())

            // The menu.
            GlassSurface(
                glass,
                Modifier.align(Alignment.TopCenter).padding(top = 18.dp).pointerInput(Unit) {
                    detectTapGestures(onTap = { bezel.cycleMode(1) }, onLongPress = { bezel.cycleMode(-1) })
                },
                shape = GlassShape.Capsule, bezel = 9.dp, tint = 0.03f, scrim = 0.10f
            ) {
                Row(Modifier.padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    LinkDot(link)
                    Spacer(Modifier.width(6.dp))
                    HeatIconView(iconFor(mode), accent, 14.dp)
                    Spacer(Modifier.width(5.dp))
                    HeatText(mode.displayName, size = 11, weight = FontWeight.Medium, color = Heat.TextDim, maxLines = 1)
                    if (cfg != null) {
                        Spacer(Modifier.width(6.dp))
                        HeatText(BezelController.describe(mode, cfg), size = 13, weight = FontWeight.Bold, color = Heat.Text, maxLines = 1)
                    }
                }
            }

            // Centre: a hint from the bezel, or why there is no picture yet.
            val centre = overlay ?: waitingText(link, rendered, status?.error, waited, hello?.name)
            if (centre != null) {
                Box(
                    Modifier.align(Alignment.Center).clip(RoundedCornerShape(14.dp)).background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 7.dp)
                ) { HeatText(centre, size = 13, weight = FontWeight.SemiBold, align = TextAlign.Center) }
            }

            // Bottom: arm, and the full menu. Gone while placing the tracker so nothing is in the way.
            if (!placing) {
                Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    WatchButton(glass, HeatIcon.Shield, accent = armed, label = if (arming > 0) "$arming" else null) {
                        client.arm(!(armed || arming > 0))
                        haptics.action()
                    }
                    WatchButton(glass, HeatIcon.List, accent = false) { onSettings() }
                }
            }
        }
    }
}

/** Touch position in view pixels -> sensor-space fractions, which is where the tracker box lives. */
private fun placeZone(bezel: BezelController, at: Offset, w: Int, h: Int, videoW: Int, videoH: Int, rotation: Int) {
    val m = previewMatrix(w.toFloat(), h.toFloat(), videoW, videoH, rotation)
    val inv = Matrix()
    if (!m.invert(inv)) return
    val p = floatArrayOf(at.x, at.y)
    inv.mapPoints(p)
    // Stretched-buffer coordinates are exactly sensor-space fractions.
    bezel.moveZone((p[0] / w).coerceIn(0f, 1f), (p[1] / h).coerceIn(0f, 1f))
}

@Composable
private fun WatchButton(glass: GlassState, icon: HeatIcon, accent: Boolean, label: String? = null, onClick: () -> Unit) {
    GlassSurface(
        glass, Modifier.size(42.dp).clip(CircleShape).pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        shape = GlassShape.Rounded(21f), bezel = 12.dp, tint = if (accent) 0.10f else 0.02f, scrim = 0.08f
    ) {
        Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
            if (label != null) HeatText(label, size = 16, weight = FontWeight.Bold, color = Heat.Amber)
            else HeatIconView(icon, if (accent) Heat.Green else Heat.Text, 20.dp)
        }
    }
}

@Composable
private fun LinkDot(link: LinkState) {
    val color = when (link.phase) {
        LinkState.Phase.UP -> Heat.Green
        LinkState.Phase.UNPAIRED -> Heat.Red
        else -> Heat.Amber
    }
    val icon = if (link.carrier == CarrierKind.BLUETOOTH) HeatIcon.Bluetooth else HeatIcon.Wifi
    HeatIconView(icon, color, 12.dp)
}

/** Seconds spent not connected, counting only while not connected; 0 once the link is up. */
@Composable
private fun rememberWaitSeconds(up: Boolean): Int {
    var seconds by remember { mutableLongStateOf(0) }
    LaunchedEffect(up) {
        seconds = 0
        while (!up) {
            delay(1_000)
            seconds++
        }
    }
    return seconds.toInt()
}

private fun waitingText(link: LinkState, rendered: Long, error: String?, waited: Int, name: String?): String? = when {
    link.phase == LinkState.Phase.UNPAIRED -> "Not paired.\nOpen Heat on the phone"
    link.phase != LinkState.Phase.UP -> if (waited >= 8) "Can't reach the camera.\nIs Heat running on it?" else "Connecting…"
    error != null -> error
    rendered == 0L -> "Starting picture…"
    else -> null
}

private fun iconFor(mode: BezelMode): HeatIcon = when (mode) {
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

/** A 240-degree gauge hugging the bezel, from the lower left round to the lower right. */
@Composable
private fun Gauge(fraction: Float, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = 3.dp.toPx()
        val inset = 6.5.dp.toPx() + stroke / 2
        val d = size.minDimension - inset * 2
        val topLeft = Offset(inset, inset)
        val arcSize = Size(d, d)
        drawArc(Color(0x33FFFFFF), 150f, 240f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        if (fraction > 0.003f) drawArc(color, 150f, 240f * fraction, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/**
 * The tracker box, drawn through the same transform as the picture so it sits exactly on what it watches.
 * While it is being placed it is drawn to be seen: bright corners, and the picture outside it dimmed.
 */
@Composable
private fun ZoneOverlay(zone: Zone?, videoW: Int, videoH: Int, rotation: Int, active: Boolean) {
    if (zone == null || videoW <= 0) return
    Canvas(Modifier.fillMaxSize()) {
        val m = previewMatrix(size.width, size.height, videoW, videoH, rotation)
        val pts = floatArrayOf(
            zone.x * size.width, zone.y * size.height,
            zone.right * size.width, zone.y * size.height,
            zone.right * size.width, zone.bottom * size.height,
            zone.x * size.width, zone.bottom * size.height
        )
        m.mapPoints(pts)
        val box = Path().apply {
            moveTo(pts[0], pts[1]); lineTo(pts[2], pts[3]); lineTo(pts[4], pts[5]); lineTo(pts[6], pts[7]); close()
        }
        if (active) {
            val outside = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                addPath(box)
            }
            drawPath(outside, Color(0x66000000))
        }
        drawPath(box, if (active) Color.White.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.5f), style = Stroke(if (active) 1.dp.toPx() else 1.2.dp.toPx()))
        if (active) drawCorners(pts, Heat.Orange, 3.dp.toPx())
    }
}

private fun DrawScope.drawCorners(pts: FloatArray, color: Color, width: Float) {
    for (i in 0 until 4) {
        val p = Offset(pts[i * 2], pts[i * 2 + 1])
        val next = Offset(pts[((i + 1) % 4) * 2], pts[((i + 1) % 4) * 2 + 1])
        val prev = Offset(pts[((i + 3) % 4) * 2], pts[((i + 3) % 4) * 2 + 1])
        drawLine(color, p, p + (next - p) * 0.26f, strokeWidth = width, cap = StrokeCap.Round)
        drawLine(color, p, p + (prev - p) * 0.26f, strokeWidth = width, cap = StrokeCap.Round)
    }
    val cx = (pts[0] + pts[4]) / 2f
    val cy = (pts[1] + pts[5]) / 2f
    drawCircle(color, 2.dp.toPx(), Offset(cx, cy))
}
