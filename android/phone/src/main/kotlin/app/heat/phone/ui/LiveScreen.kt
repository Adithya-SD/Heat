package app.heat.phone.ui

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.heat.core.camera.ZoomSmoother
import app.heat.core.control.BezelController
import app.heat.core.control.BezelMode
import app.heat.core.control.Controls
import app.heat.core.model.Facing
import app.heat.core.model.Zone
import app.heat.core.util.Clock
import app.heat.glass.Heat
import app.heat.glass.HeatIcon
import app.heat.glass.HeatIconView
import app.heat.glass.HeatText
import app.heat.phone.service.CameraHub
import kotlinx.coroutines.delay

/**
 * The camera view.
 *
 * Deliberately matte: it sits over a live picture, and glass over moving video is both busy to look at and costly
 * to draw every frame. The picture is the hero: a status pill at the top (nothing to press there), the lens buttons,
 * and one dock at the bottom. The dock's Camera folder opens the ruler and the setting chips (the same settings,
 * worded the same way, that the watch's menu offers); arming is one tap on the shield in the middle.
 */
@Composable
fun LiveScreen(hub: CameraHub, onRooms: () -> Unit, onSettings: () -> Unit, onGallery: () -> Unit) {
    val config by hub.host.config.collectAsState()
    val armed by hub.host.armed.collectAsState()
    val viewers by hub.host.viewerCount.collectAsState()
    val hubState by hub.state.collectAsState()
    val armingEnds by hub.host.armingDeadline.collectAsState()

    var aiming by remember { mutableStateOf(false) }
    var peekUntil by remember { mutableStateOf(0L) }
    var active by remember { mutableStateOf(BezelMode.EXPOSURE) }
    // Zoom is the lens buttons; slide on them and they become a ruler (like a phone camera's dial), which goes back to buttons a moment after the finger lifts.
    var rulerOn by remember { mutableStateOf(false) }
    var touching by remember { mutableStateOf(false) }
    LaunchedEffect(touching, rulerOn) { if (!touching && rulerOn) { delay(2_200); if (!touching) rulerOn = false } }
    var shutterFlash by remember { mutableStateOf(false) }
    LaunchedEffect(shutterFlash) { if (shutterFlash) { delay(120); shutterFlash = false } }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val recordingSince by hub.gallery.recording.collectAsState()
    val galleryItems by hub.gallery.items.collectAsState()
    val capturePrefsVersion by hub.gallery.prefs.version.collectAsState()
    val clipping = remember(capturePrefsVersion) { hub.gallery.prefs.clipping }
    var toolsOpen by remember { mutableStateOf(false) }
    val now = rememberTicker(armed || armingEnds > 0)

    // A camera view that goes dark on its own is not a camera view. While armed the app draws its own black
    // screen to save power, so the system's timeout is left alone then.
    val view = LocalView.current
    DisposableEffect(armed) {
        view.keepScreenOn = !armed
        onDispose { view.keepScreenOn = false }
    }

    val remaining = if (armingEnds > 0) ((armingEnds - now + 999) / 1000).toInt().coerceAtLeast(0) else 0
    val blackout = armed && config.power.blackoutWhileArmed && now > peekUntil

    // The zoom that is drawn follows the requested one with the same smoothing the camera uses, once per display
    // frame, so rulers and readouts glide at the screen's refresh rate rather than at the camera's report rate.
    val target = config.camera.zoom
    val zoomShown = remember { mutableFloatStateOf(target) }
    val smoother = remember(hubState.zoomMin, hubState.zoomMax) {
        ZoomSmoother(zoomShown.floatValue, hubState.zoomMin.coerceAtMost(1f), hubState.zoomMax.coerceAtLeast(1f))
    }
    val switchingNow by rememberUpdatedState(hubState.lensSwitching)
    val lensNow by rememberUpdatedState(hubState.activeLens?.ratio ?: 1f)
    val hasWider by rememberUpdatedState(hubState.lenses.any { it.ratio < (hubState.activeLens?.ratio ?: 1f) * 0.99f })
    LaunchedEffect(target, smoother) {
        // Going wider than the lens in use, the camera changes lens first and only then moves. The screen does the
        // same: it holds still (showing the last frame) until the new lens's first frame, then moves with the camera.
        if (hasWider && target < lensNow * 0.999f && smoother.settled) {
            var waited = 0
            while (!switchingNow && waited < 250) { delay(10); waited += 10 }
            while (switchingNow && waited < 3_000) { delay(10); waited += 10 }
            delay(180)                                 // the camera holds the old framing this long too
        }
        smoother.setTarget(target)
        var last = 0L
        while (!smoother.settled) {
            val t = withFrameNanos { it }
            val dt = if (last == 0L) 1f / 60f else ((t - last) / 1e9f).coerceIn(0.001f, 0.1f)
            last = t
            zoomShown.floatValue = smoother.step(dt)
        }
        zoomShown.floatValue = smoother.zoom
    }
    // This phone cannot keep two rear lenses open at once, so changing lens takes the camera about a second to
    // reopen. Rather than a frozen or black picture, the last frame is kept on screen, blurred behind and
    // still moving with the zoom in front (so going 1x to 0.5x shrinks the old picture away), and dissolved into
    // the new lens when its first frame arrives.
    var previewView by remember { mutableStateOf<TextureView?>(null) }
    var frozen by remember { mutableStateOf<Bitmap?>(null) }
    val freezeZoom = remember { mutableFloatStateOf(1f) }
    val dissolve = remember { Animatable(0f) }
    LaunchedEffect(hubState.lensSwitching) {
        if (hubState.lensSwitching) {
            val tv = previewView
            val bmp = if (tv != null && tv.isAvailable) runCatching { tv.getBitmap(432, 576) }.getOrNull() else null
            if (bmp != null) {
                frozen = bmp
                freezeZoom.floatValue = zoomShown.floatValue
                dissolve.snapTo(1f)
            }
        } else if (frozen != null) {
            dissolve.animateTo(0f, tween(160))
            frozen = null
        }
    }

    val setZoom: (Float) -> Unit = { z ->
        val c = z.coerceIn(hubState.zoomMin, hubState.zoomMax)
        hub.host.editLocally { it.copy(camera = it.camera.copy(zoom = c)) }
    }
    val currentTarget by rememberUpdatedState(target)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // The picture, 4:3, full width. Pinch anywhere on it to zoom.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(3f / 4f).pointerInput(aiming) {
                    if (!aiming) detectTransformGestures { _, _, zoom, _ -> if (zoom != 1f) setZoom(currentTarget * zoom) }
                }
            ) {
                CameraPreview(hub, Modifier.fillMaxSize()) { previewView = it }
                frozen?.let { bmp ->
                    val img = remember(bmp) { bmp.asImageBitmap() }
                    Box(Modifier.matchParentSize().graphicsLayer { alpha = dissolve.value }) {
                        Image(img, null, Modifier.matchParentSize().blur(26.dp).graphicsLayer { scaleX = 1.25f; scaleY = 1.25f; alpha = 0.8f }, contentScale = ContentScale.Crop)
                        Image(
                            img, null,
                            Modifier.matchParentSize().graphicsLayer {
                                val s = (zoomShown.floatValue / freezeZoom.floatValue).coerceIn(0.25f, 6f)
                                scaleX = s; scaleY = s
                            },
                            contentScale = ContentScale.Crop
                        )
                    }
                }
                // Zones live in sensor space; the preview shows the picture upright, so convert both ways.
                val up = hubState.sensorOrientation
                ZoneOverlay(config.detect.zone.rotated(up), editing = aiming, motion = hubState.motionPercent) { z ->
                    hub.host.editLocally { it.copy(detect = it.detect.copy(zone = z.unrotated(up))) }
                }
            }
        }

        if (shutterFlash) Box(Modifier.fillMaxSize().background(Color(0x66FFFFFF)))

        // Quiet scrims so the chrome reads over any picture.
        Box(Modifier.fillMaxWidth().height(150.dp).background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(420.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)))))

        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            // Top: status only, nothing to press.
            StatusPill(armed, remaining, viewers, hubState.battery, hubState.carrier?.label)

            hubState.error?.let { err ->
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().matte(RoundedCornerShape(16.dp), Color(0xFF2A1B0A)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HeatIconView(HeatIcon.Alert, Heat.Amber, 20.dp)
                    Spacer(Modifier.width(10.dp))
                    HeatText(err, size = 14)
                }
            }

            Spacer(Modifier.weight(1f))

            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(if (rulerOn) 0.0001f else 1f), contentAlignment = Alignment.CenterStart) {
                    if (!rulerOn) GalleryThumb(galleryItems.firstOrNull(), onGallery)
                }
                Box(
                    if (rulerOn) Modifier.weight(6f) else Modifier,
                    contentAlignment = Alignment.Center
                ) {
                    ZoomStrip(
                        rulerOn = rulerOn,
                        lenses = hubState.lenses, zoomMin = hubState.zoomMin, zoomMax = hubState.zoomMax,
                        zoomShown = { zoomShown.floatValue },
                        onZoom = { setZoom(it) },
                        onSlideStart = { touching = true; rulerOn = true },
                        onSlideEnd = { touching = false }
                    )
                }
                Box(Modifier.weight(if (rulerOn) 0.0001f else 1f), contentAlignment = Alignment.CenterEnd) {
                    if (!rulerOn) CaptureButtons(
                        clipping = clipping, recordingSince = recordingSince,
                        onPhoto = {
                            val bmp = previewView?.takeIf { it.isAvailable }?.getBitmap(960, 1280)
                            if (bmp != null) {
                                shutterFlash = true
                                hub.gallery.savePhoto(bmp, config.label) { ok ->
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        if (!ok) android.widget.Toast.makeText(ctx, "Could not save the picture", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        onRecord = {
                            if (recordingSince != null) hub.stopClip()
                            else hub.startClip(previewView?.takeIf { it.isAvailable }?.getBitmap(480, 640))
                        }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // The camera folder: the ruler and every picture setting, out of sight until asked for.
            androidx.compose.animation.AnimatedVisibility(
                visible = toolsOpen,
                enter = androidx.compose.animation.fadeIn(tween(160)) + androidx.compose.animation.expandVertically(tween(220)),
                exit = androidx.compose.animation.fadeOut(tween(120)) + androidx.compose.animation.shrinkVertically(tween(180))
            ) {
                Column {
                    Controls.detentFor(active)?.let { detent ->
                        Box(Modifier.padding(horizontal = 16.dp)) {
                            DetentRuler(
                                detent.labels, detent.read(config),
                                onIndex = { i -> hub.host.editLocally { c -> detent.write(c, i) } }
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    ChipRow {
                        ControlChip(BezelMode.EXPOSURE, BezelController.describe(BezelMode.EXPOSURE, config), active == BezelMode.EXPOSURE, false, false) { active = BezelMode.EXPOSURE }
                        if (hubState.manualFocus) ControlChip(BezelMode.FOCUS, BezelController.describe(BezelMode.FOCUS, config), active == BezelMode.FOCUS, false, false) { active = BezelMode.FOCUS }
                        ControlChip(BezelMode.WHITE_BALANCE, BezelController.describe(BezelMode.WHITE_BALANCE, config), active == BezelMode.WHITE_BALANCE, false, false) { active = BezelMode.WHITE_BALANCE }
                        if (hubState.manualExposure) {
                            ControlChip(BezelMode.ISO, BezelController.describe(BezelMode.ISO, config), active == BezelMode.ISO, false, false) { active = BezelMode.ISO }
                            ControlChip(BezelMode.SHUTTER, BezelController.describe(BezelMode.SHUTTER, config), active == BezelMode.SHUTTER, false, false) { active = BezelMode.SHUTTER }
                        }
                        ControlChip(BezelMode.NIGHT, BezelController.describe(BezelMode.NIGHT, config), active == BezelMode.NIGHT, false, false) { active = BezelMode.NIGHT }
                        if (hubState.hasTorch) {
                            val sw = Controls.switchFor(BezelMode.FLASH)!!
                            ControlChip(BezelMode.FLASH, "", false, true, sw.read(config)) { hub.host.editLocally { c -> sw.write(c, !sw.read(c)) } }
                        }
                        val lock = Controls.switchFor(BezelMode.LOCK)!!
                        ControlChip(BezelMode.LOCK, "", false, true, lock.read(config)) { hub.host.editLocally { c -> lock.write(c, !lock.read(c)) } }
                        ControlChip(BezelMode.TRACKER, "", aiming, false, false) { aiming = !aiming }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            // One dock: the folders either side, and arming in the middle (a tap, no slide).
            Dock(
                armed = armed, arming = remaining, toolsOpen = toolsOpen,
                onTools = { toolsOpen = !toolsOpen; if (!toolsOpen) aiming = false },
                onRooms = onRooms,
                onArm = { if (armed || remaining > 0) hub.host.setArmed(false) else hub.host.setArmed(true) },
                onFlip = { hub.host.editLocally { it.copy(camera = it.camera.copy(facing = if (it.camera.facing == Facing.REAR) Facing.FRONT else Facing.REAR)) } },
                onSettings = onSettings
            )
            Spacer(Modifier.height(14.dp))
        }

        if (blackout) {
            Box(
                Modifier.fillMaxSize().background(Color.Black).tappable({ peekUntil = Clock.SYSTEM.nowMs() + 10_000 }),
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

@Composable
private fun StatusPill(armed: Boolean, remaining: Int, viewers: Int, battery: Int, carrier: String?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.Center) {
        Row(Modifier.matte(CircleShape).padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (armed) Heat.Green else if (remaining > 0) Heat.Amber else Heat.TextFaint, CircleShape))
            Spacer(Modifier.width(8.dp))
            HeatText(if (remaining > 0) "Arming $remaining" else if (armed) "Watching" else "Ready", size = 13, weight = FontWeight.SemiBold)
            if (viewers > 0) {
                Spacer(Modifier.width(10.dp))
                HeatIconView(HeatIcon.Eye, Heat.Ember1, 14.dp)
                Spacer(Modifier.width(3.dp))
                HeatText("$viewers", size = 13, color = Heat.Ember1, weight = FontWeight.SemiBold)
                if (carrier != null) {
                    Spacer(Modifier.width(6.dp))
                    HeatIconView(when (carrier) { "Wi-Fi" -> HeatIcon.Wifi; "Internet" -> HeatIcon.Link; else -> HeatIcon.Bluetooth }, Heat.TextDim, 13.dp)
                }
            }
            if (battery >= 0) {
                Spacer(Modifier.width(10.dp))
                HeatText("$battery%", size = 12, color = if (battery <= 20) Heat.Amber else Heat.TextDim, weight = FontWeight.Medium)
            }
        }
    }
}

/**
 * The bottom dock: Camera (the settings folder), Link, the arm shield in the middle, Flip, Settings. Mirrored, one
 * row, nothing else on the picture.
 */
@Composable
private fun Dock(
    armed: Boolean, arming: Int, toolsOpen: Boolean,
    onTools: () -> Unit, onRooms: () -> Unit, onArm: () -> Unit, onFlip: () -> Unit, onSettings: () -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        DockButton(HeatIcon.Focus, "Camera", toolsOpen, onTools)
        DockButton(HeatIcon.Link, "Link", false, onRooms)
        ArmButton(armed, arming, onArm)
        DockButton(HeatIcon.Flip, "Flip", false, onFlip)
        DockButton(HeatIcon.Gear, "Settings", false, onSettings)
    }
}

@Composable
private fun DockButton(icon: HeatIcon, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.tappable(onClick)) {
        Box(
            Modifier.size(46.dp).matte(CircleShape, if (selected) Color(0xFF3A2414) else Heat.Matte),
            contentAlignment = Alignment.Center
        ) { HeatIconView(icon, if (selected) Heat.Ember1 else Heat.Text, 20.dp) }
        Spacer(Modifier.height(5.dp))
        HeatText(label, size = 11, color = if (selected) Heat.Ember1 else Heat.TextDim)
    }
}

/** Tap to arm (with the arming countdown inside), tap again to stop. */
@Composable
private fun ArmButton(armed: Boolean, arming: Int, onClick: () -> Unit) {
    val ring = when {
        armed -> Heat.Green
        arming > 0 -> Heat.Amber
        else -> Heat.Ember0
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.tappable(onClick)) {
        Box(
            Modifier.size(64.dp).background(Brush.radialGradient(listOf(ring.copy(alpha = 0.32f), ring.copy(alpha = 0.10f))), CircleShape)
                .padding(3.dp).background(Color(0xFF0B0B0F), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (arming > 0) HeatText("$arming", size = 22, weight = FontWeight.Bold, color = Heat.Amber)
            else HeatIconView(HeatIcon.Shield, ring, 26.dp)
        }
        Spacer(Modifier.height(5.dp))
        HeatText(if (armed) "Armed" else if (arming > 0) "Arming" else "Arm", size = 11, weight = FontWeight.SemiBold, color = ring)
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
private fun CameraPreview(hub: CameraHub, modifier: Modifier, onView: (TextureView) -> Unit) {
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                onView(this)
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
        val current by rememberUpdatedState(zone)
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
            val color = if (hot) Heat.Ember1 else Color.White.copy(alpha = if (editing) 0.95f else 0.45f)
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

/**
 * Zoom: the lens buttons, and when you slide across them they become a ruler you drag like a tape measure (the way a
 * phone camera's zoom does). The ruler returns to buttons a moment after the finger lifts. A tap on a button goes to
 * that lens; a slide anywhere on the strip zooms continuously.
 */
@Composable
private fun ZoomStrip(
    rulerOn: Boolean, lenses: List<app.heat.core.camera.LensEntry>, zoomMin: Float, zoomMax: Float,
    zoomShown: () -> Float, onZoom: (Float) -> Unit, onSlideStart: () -> Unit, onSlideEnd: () -> Unit
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val pxPerLn = with(density) { 160.dp.toPx() }
    var local by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier.pointerInput(zoomMin, zoomMax) {
            detectHorizontalDragGestures(
                onDragStart = { local = kotlin.math.ln(zoomShown()); onSlideStart() },
                onDragEnd = onSlideEnd, onDragCancel = onSlideEnd
            ) { change, dx ->
                change.consume()
                local = (local - dx / pxPerLn).coerceIn(kotlin.math.ln(zoomMin), kotlin.math.ln(zoomMax))
                onZoom(kotlin.math.exp(local))
            }
        }
    ) {
        if (rulerOn) LogRuler(
            zoomState = zoomShown, minZoom = zoomMin, maxZoom = zoomMax,
            stops = lenses.map { it.ratio }, onTarget = onZoom,
            onDragging = { if (it) onSlideStart() else onSlideEnd() }
        ) else LensButtons(lenses, zoomMax, zoomShown, onSelect = onZoom)
    }
}

/** The newest picture or clip, small, at the left of the zoom strip: tap for the gallery. Empty until there is one. */
@Composable
private fun GalleryThumb(latest: app.heat.platform.Gallery.Item?, onClick: () -> Unit) {
    val thumb by androidx.compose.runtime.produceState<Bitmap?>(null, latest?.thumb?.path) {
        value = latest?.thumb?.let { f -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { android.graphics.BitmapFactory.decodeFile(f.path) }.getOrNull() } }
    }
    Box(Modifier.size(44.dp).matte(RoundedCornerShape(14.dp)).tappable(onClick), contentAlignment = Alignment.Center) {
        val t = thumb
        if (t == null) HeatIconView(HeatIcon.Pictures, Heat.TextDim, 20.dp)
        else Image(t.asImageBitmap(), null, Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
    }
}

/**
 * Take a picture: always there, small. When clips are switched on in settings a second, red button records one (it
 * ends by itself after the clip length in settings, or when pressed again) and shows the time while it runs.
 */
@Composable
private fun CaptureButtons(clipping: Boolean, recordingSince: Long?, onPhoto: () -> Unit, onRecord: () -> Unit) {
    val now by androidx.compose.runtime.produceState(android.os.SystemClock.elapsedRealtime(), recordingSince) {
        while (recordingSince != null) { value = android.os.SystemClock.elapsedRealtime(); delay(250) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (recordingSince != null) {
            val secs = ((now - recordingSince) / 1000).coerceAtLeast(0)
            Row(
                Modifier.height(40.dp).matte(CircleShape, Color(0xFF3A1414)).tappable(onRecord).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(9.dp).background(Heat.Red, CircleShape))
                Spacer(Modifier.width(7.dp))
                HeatText("%d:%02d".format(secs / 60, secs % 60), size = 13, weight = FontWeight.SemiBold)
            }
        } else {
            if (clipping) Box(Modifier.size(38.dp).matte(CircleShape).tappable(onRecord), contentAlignment = Alignment.Center) {
                Box(Modifier.size(14.dp).background(Heat.Red, CircleShape))
            }
            Box(Modifier.size(44.dp).matte(CircleShape).border(1.5.dp, Heat.Ember1, CircleShape).tappable(onPhoto), contentAlignment = Alignment.Center) {
                HeatIconView(HeatIcon.Camera, Heat.Ember1, 20.dp)
            }
        }
    }
}
