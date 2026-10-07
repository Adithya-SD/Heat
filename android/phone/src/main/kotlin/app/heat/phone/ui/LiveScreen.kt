package app.heat.phone.ui

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.heat.core.model.Zone
import app.heat.core.util.Clock
import app.heat.glass.GlassHost
import app.heat.glass.GlassShape
import app.heat.glass.GlassState
import app.heat.glass.GlassSurface
import app.heat.glass.Heat
import app.heat.glass.HeatText
import app.heat.glass.HeatIcon
import app.heat.glass.HeatIconView
import app.heat.phone.service.CameraHub
import kotlinx.coroutines.delay

@Composable
fun LiveScreen(hub: CameraHub, glass: GlassState, onSettings: () -> Unit) {
    val config by hub.host.config.collectAsState()
    val armed by hub.host.armed.collectAsState()
    val viewers by hub.host.viewerCount.collectAsState()
    val hubState by hub.state.collectAsState()
    val armingEnds by hub.host.armingDeadline.collectAsState()

    var aiming by remember { mutableStateOf(false) }
    var peekUntil by remember { mutableStateOf(0L) }
    val now = rememberTicker(armed || armingEnds > 0)

    val remaining = if (armingEnds > 0) ((armingEnds - now + 999) / 1000).toInt().coerceAtLeast(0) else 0
    val blackout = armed && config.power.blackoutWhileArmed && now > peekUntil

    GlassHost(
        glass, Modifier.fillMaxSize().background(Color.Black),
        backdrop = {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
                    CameraPreview(hub, Modifier.fillMaxSize())
                    // Zones live in sensor space; the preview shows the picture upright, so convert both ways.
                    val up = hubState.sensorOrientation
                    ZoneOverlay(config.detect.zone.rotated(up), editing = aiming, motion = hubState.motionPercent) { z ->
                        hub.host.editLocally { it.copy(detect = it.detect.copy(zone = z.unrotated(up))) }
                    }
                }
            }
        }
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
            // ── top: status ──
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                GlassSurface(glass, shape = GlassShape.Capsule, bezel = 14.dp) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).background(if (armed) Heat.Green else if (remaining > 0) Heat.Amber else Heat.TextFaint, androidx.compose.foundation.shape.CircleShape))
                        Spacer(Modifier.width(10.dp))
                        HeatText(
                            when {
                                remaining > 0 -> "Arming in $remaining"
                                armed -> "Watching"
                                else -> "Ready"
                            },
                            size = 15, weight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(10.dp))
                        HeatIconView(HeatIcon.Eye, if (viewers > 0) Heat.Orange else Heat.TextFaint, 17.dp)
                        Spacer(Modifier.width(4.dp))
                        HeatText("$viewers", size = 15, color = if (viewers > 0) Heat.Orange else Heat.TextFaint)
                    }
                }
                GlassSurface(glass, shape = GlassShape.Capsule, refract = false) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        HeatText(if (hubState.battery >= 0) "${hubState.battery}%" else "", size = 14, color = Heat.TextDim)
                    }
                }
            }

            hubState.error?.let { err ->
                Spacer(Modifier.height(10.dp))
                GlassSurface(glass, Modifier.fillMaxWidth(), shape = GlassShape.Rounded(18f), refract = false, tint = 0.18f) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        HeatIconView(HeatIcon.Alert, Heat.Amber, 20.dp)
                        Spacer(Modifier.width(10.dp))
                        HeatText(err, size = 14)
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // ── zoom ──
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                GlassSurface(glass, shape = GlassShape.Capsule, refract = false) {
                    Row(Modifier.padding(5.dp)) {
                        for (z in listOf(0.5f, 1f, 2f, 5f)) {
                            val on = kotlin.math.abs(config.camera.zoom - z) < 0.06f
                            Box(
                                Modifier.size(width = 54.dp, height = 36.dp).background(if (on) Color(0x44FFFFFF) else Color.Transparent, androidx.compose.foundation.shape.CircleShape)
                                    .tappable({ hub.host.editLocally { it.copy(camera = it.camera.copy(zoom = z)) } }),
                                contentAlignment = Alignment.Center
                            ) { HeatText(if (z < 1f) "0.5" else "${z.toInt()}×", size = 15, weight = FontWeight.SemiBold, color = if (on) Heat.Orange else Heat.Text) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            // ── bottom controls ──
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassIconButton(glass, HeatIcon.Bolt, { hub.host.editLocally { it.copy(camera = it.camera.copy(torch = !it.camera.torch)) } }, active = config.camera.torch)
                GlassIconButton(glass, HeatIcon.Aim, { aiming = !aiming }, active = aiming)
                GlassPillButton(
                    glass,
                    label = when {
                        remaining > 0 -> "Cancel"
                        armed -> "Disarm"
                        else -> "Arm"
                    },
                    onClick = {
                        if (armed || remaining > 0) hub.host.setArmed(false) else hub.host.setArmed(true)
                    },
                    modifier = Modifier.weight(1f),
                    accent = if (armed) Heat.Green else Heat.Orange,
                    subLabel = if (!armed && remaining == 0 && config.detect.armDelaySec > 0) "in ${config.detect.armDelaySec}s" else null
                )
                GlassIconButton(glass, HeatIcon.Flip, {
                    hub.host.editLocally {
                        it.copy(camera = it.camera.copy(facing = if (it.camera.facing == app.heat.core.model.Facing.REAR) app.heat.core.model.Facing.FRONT else app.heat.core.model.Facing.REAR))
                    }
                })
                GlassIconButton(glass, HeatIcon.Gear, onSettings)
            }
        }

        if (blackout) {
            Box(
                Modifier.fillMaxSize().background(Color.Black)
                    .tappable({ peekUntil = Clock.SYSTEM.nowMs() + 10_000 }),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    HeatText("Watching", size = 18, color = Color(0x55FFFFFF))
                    HeatText("Tap to wake the screen", size = 13, color = Color(0x33FFFFFF))
                }
            }
        }
    }
}

/** Ticks while [active], giving the current monotonic time so countdowns and the blackout peek stay live without per-frame work. */
@Composable
private fun rememberTicker(active: Boolean): Long {
    var now by remember { mutableStateOf(Clock.SYSTEM.nowMs()) }
    LaunchedEffect(active) {
        while (true) {
            now = Clock.SYSTEM.nowMs()
            delay(if (active) 250 else 1_000)
        }
    }
    return now
}

@Composable
private fun CameraPreview(hub: CameraHub, modifier: Modifier) {
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                isOpaque = true
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = hub.attachPreview(st)
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        hub.detachPreview()
                        return true
                    }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
            }
        },
        modifier = modifier
    )
}

/** The tracker box. Drag to move, pinch to resize while [editing]; always shown faintly so you know what is watched. */
@Composable
private fun ZoneOverlay(zone: Zone, editing: Boolean, motion: Float, onChange: (Zone) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val current by androidx.compose.runtime.rememberUpdatedState(zone)
        val gestureModifier = if (editing) {
            Modifier.pointerInput(Unit) {
                detectTransformGestures { _, pan, scale, _ ->
                    val z = current
                    val w = (z.width * scale).coerceIn(Zone.MIN_SIZE, 1f)
                    val h = (z.height * scale).coerceIn(Zone.MIN_SIZE, 1f)
                    val cx = z.x + z.width / 2 + pan.x / wPx
                    val cy = z.y + z.height / 2 + pan.y / hPx
                    onChange(Zone(cx - w / 2, cy - h / 2, w, h).sanitized())
                }
            }
        } else Modifier
        Canvas(Modifier.fillMaxSize().then(gestureModifier)) {
            val r = Offset(zone.x * size.width, zone.y * size.height)
            val s = Size(zone.width * size.width, zone.height * size.height)
            val hot = motion > 1f
            val color = if (hot) Heat.Orange else Color.White.copy(alpha = if (editing) 0.95f else 0.45f)
            drawRoundRect(
                color, r, s, CornerRadius(10.dp.toPx()),
                style = Stroke(if (editing) 2.5.dp.toPx() else 1.5.dp.toPx(), pathEffect = if (editing) null else PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
            )
            if (editing) {
                for (cx in listOf(r.x, r.x + s.width)) for (cy in listOf(r.y, r.y + s.height)) {
                    drawCircle(Color.White, 5.dp.toPx(), Offset(cx, cy))
                }
            }
        }
    }
}
