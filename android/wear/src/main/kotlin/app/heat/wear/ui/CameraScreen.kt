package app.heat.wear.ui

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.heat.core.control.BezelController
import app.heat.core.control.BezelMode
import app.heat.core.host.CameraClient
import app.heat.core.host.VideoSink
import app.heat.core.link.CarrierKind
import app.heat.core.link.LinkState
import app.heat.core.link.MediaFlags
import app.heat.core.model.CameraConfig
import app.heat.core.model.Msg
import app.heat.core.model.Zone
import app.heat.glass.GlassHost
import app.heat.glass.GlassShape
import app.heat.glass.GlassState
import app.heat.glass.GlassSurface
import app.heat.glass.Heat
import app.heat.glass.HeatIcon
import app.heat.glass.HeatIconView
import app.heat.glass.HeatText
import app.heat.wear.PreviewDecoder
import app.heat.wear.WatchHaptics
import app.heat.wear.WatchHub
import app.heat.wear.previewMatrix
import kotlin.math.ln

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
    val placingZone by bezel.placingZone.collectAsState()

    val decoder = remember(client) { PreviewDecoder().also { d -> d.onNeedKeyframe = { client.needKeyframe() } } }
    var surface by remember { mutableStateOf<Surface?>(null) }
    var resumes by remember { mutableIntStateOf(0) }
    var framesSeen by remember { mutableIntStateOf(0) }

    val stream = status?.stream
    val videoW = stream?.width ?: 0
    val videoH = stream?.height ?: 0
    val rotation = stream?.rotation ?: 0

    // Showing the picture means: ask for video, route it to the decoder, and undo all of it the moment we leave.
    LifecycleResumeEffect(client) {
        client.videoSink = VideoSink { flags, pts, data, off, len ->
            decoder.submit(flags and MediaFlags.KEYFRAME != 0, pts, data, off, len)
        }
        client.watch(video = true)
        hub.refresh()
        resumes++
        onPauseOrDispose {
            client.watch(video = false)
            client.videoSink = null
            decoder.stop()
        }
    }
    LaunchedEffect(surface, videoW, videoH, resumes) {
        val s = surface
        if (s != null && s.isValid && videoW > 0) decoder.start(s, videoW, videoH)
    }
    LaunchedEffect(decoder) {
        while (true) {
            framesSeen = decoder.framesRendered.toInt()
            kotlinx.coroutines.delay(500)
        }
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val cfg = config
    val armed = status?.armed == true
    val arming = status?.armingIn ?: 0
    val fraction = cfg?.let { fractionFor(mode, it, placingZone) } ?: 0f
    val accent = if (armed) Heat.Green else Heat.Orange

    GlassHost(
        glass,
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onRotaryScrollEvent { e -> bezel.onRotary(e.verticalScrollPixels); true }
            .focusRequester(focus)
            .focusable(),
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
                                    decoder.stop()
                                    surface?.release()
                                    surface = null
                                    return true
                                }
                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                            }
                            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                                (v as TextureView).setTransform(previewMatrix(v.width.toFloat(), v.height.toFloat(), videoW, videoH, rotation))
                            }
                        }
                    },
                    update = { tv -> tv.setTransform(previewMatrix(tv.width.toFloat(), tv.height.toFloat(), videoW, videoH, rotation)) },
                    modifier = Modifier.fillMaxSize()
                )
                ZoneOverlay(placingZone ?: cfg?.detect?.zone, videoW, videoH, rotation, active = placing)
            }
        }
    ) {
        Box(
            Modifier.fillMaxSize().pointerInput(placing, videoW, videoH, rotation) {
                detectTapGestures(
                    onTap = { off ->
                        if (placing && videoW > 0) {
                            val m = previewMatrix(size.width.toFloat(), size.height.toFloat(), videoW, videoH, rotation)
                            val inv = Matrix()
                            if (m.invert(inv)) {
                                val p = floatArrayOf(off.x, off.y)
                                inv.mapPoints(p)
                                // Stretched-buffer coordinates are exactly sensor-space fractions, which is where zones live.
                                bezel.moveZone((p[0] / size.width).coerceIn(0f, 1f), (p[1] / size.height).coerceIn(0f, 1f))
                            }
                        } else bezel.cycleMode()
                    },
                    onLongPress = { onCameras() }
                )
            }
        ) {
            // The bezel itself, as glass; its gauge shows what the real bezel is currently adjusting.
            GlassSurface(glass, Modifier.fillMaxSize(), shape = GlassShape.Ring(13f), bezel = 6.dp, scrim = 0.12f) {}
            Gauge(fraction, accent, Modifier.fillMaxSize())

            // Top: which camera, how it is connected.
            GlassSurface(
                glass, Modifier.align(Alignment.TopCenter).padding(top = 20.dp), shape = GlassShape.Capsule, bezel = 8.dp
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    LinkIcon(link)
                    Spacer(Modifier.width(5.dp))
                    HeatText(
                        hello?.name ?: "Camera", size = 12, weight = FontWeight.SemiBold, maxLines = 1,
                        color = if (link.phase == LinkState.Phase.UP) Heat.Text else Heat.TextDim
                    )
                    if (armed) {
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(7.dp).background(Heat.Green, CircleShape))
                    }
                }
            }

            // Centre: what the bezel just did (or why there is no picture).
            val centre = overlay ?: waitingText(link, framesSeen, status?.error)
            if (centre != null) {
                Box(
                    Modifier.align(Alignment.Center).clip(RoundedCornerShape(14.dp)).background(Color(0xAA000000)).padding(horizontal = 12.dp, vertical = 7.dp)
                ) { HeatText(centre, size = 14, weight = FontWeight.SemiBold, align = TextAlign.Center) }
            }

            // Bottom: arm, and the menu.
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

@Composable
private fun WatchButton(glass: GlassState, icon: HeatIcon, accent: Boolean, label: String? = null, onClick: () -> Unit) {
    GlassSurface(
        glass, Modifier.size(42.dp).clip(CircleShape).pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        shape = GlassShape.Rounded(21f), bezel = 12.dp, tint = if (accent) 0.16f else 0.05f
    ) {
        Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
            if (label != null) HeatText(label, size = 16, weight = FontWeight.Bold, color = Heat.Amber)
            else HeatIconView(icon, if (accent) Heat.Green else Heat.Text, 20.dp)
        }
    }
}

@Composable
private fun LinkIcon(link: LinkState) {
    val color = when (link.phase) {
        LinkState.Phase.UP -> Heat.Green
        LinkState.Phase.UNPAIRED -> Heat.Red
        else -> Heat.Amber
    }
    val icon = if (link.carrier == CarrierKind.BLUETOOTH) HeatIcon.Bluetooth else HeatIcon.Wifi
    HeatIconView(icon, color, 13.dp)
}

private fun waitingText(link: LinkState, frames: Int, error: String?): String? = when {
    link.phase == LinkState.Phase.UNPAIRED -> "Not paired.\nOpen Heat on the phone"
    link.phase != LinkState.Phase.UP -> "Connecting…"
    error != null -> error
    frames == 0 -> "Starting picture…"
    else -> null
}

/** Where the current mode's value sits in its range, 0..1. */
private fun fractionFor(mode: BezelMode, c: CameraConfig, placing: Zone?): Float = when (mode) {
    BezelMode.ZOOM -> {
        val lo = ln(BezelController.MIN_ZOOM.toDouble()); val hi = ln(BezelController.MAX_ZOOM.toDouble())
        ((ln(c.camera.zoom.toDouble()) - lo) / (hi - lo)).toFloat()
    }
    BezelMode.TRACKER -> ((placing ?: c.detect.zone).width - Zone.MIN_SIZE) / (0.95f - Zone.MIN_SIZE)
    BezelMode.SENSITIVITY -> c.detect.sensitivity / 100f
    BezelMode.MUDDINESS -> c.stream.muddiness / 100f
    BezelMode.EXPOSURE -> (c.camera.exposure + 10) / 20f
    BezelMode.FLASH -> if (c.camera.torch) 1f else 0f
}.coerceIn(0f, 1f)

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

/** The tracker box, drawn through the same transform as the picture so it sits exactly on what it watches. */
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
        val path = Path().apply {
            moveTo(pts[0], pts[1]); lineTo(pts[2], pts[3]); lineTo(pts[4], pts[5]); lineTo(pts[6], pts[7]); close()
        }
        drawPath(
            path, if (active) Color.White else Color.White.copy(alpha = 0.5f),
            style = Stroke(if (active) 2.5.dp.toPx() else 1.2.dp.toPx())
        )
    }
}
