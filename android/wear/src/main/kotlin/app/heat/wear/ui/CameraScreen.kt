package app.heat.wear.ui

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.heat.core.control.BezelController
import app.heat.core.control.BezelMode
import app.heat.core.control.Controls
import app.heat.core.host.CameraClient
import app.heat.core.link.LinkState
import app.heat.core.model.Zone
import app.heat.core.model.Facing
import app.heat.glass.GlassHost
import app.heat.glass.GlassShape
import app.heat.glass.GlassState
import app.heat.glass.GlassSurface
import app.heat.glass.Heat
import app.heat.glass.HeatIcon
import app.heat.glass.HeatIconView
import app.heat.glass.HeatText
import app.heat.wear.LastPicture
import app.heat.wear.Rotary
import app.heat.wear.WatchHaptics
import app.heat.wear.WatchHub
import app.heat.wear.previewMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The camera, full face, with one menu and a ring of buttons.
 *
 * The chip at the top is the menu: tap it and it moves to the next control, long-press for the previous; the
 * bezel changes whatever the chip currently shows, and the line along the rim draws how far through it is. Every
 * status lives in that chip (link, mode, value). The buttons sit round the edge where a thumb finds them: power
 * (arm) on the left, flip on the right, settings bottom-left, the rooms (other cameras) at the bottom, torch,
 * alerts and "clear view" in the corners. Choosing the tracker clears every button off the picture, so the box can
 * be put exactly where it belongs: touch (or drag) puts it there, the bezel makes it bigger or smaller, and the
 * camera follows live.
 *
 * [incoming] is a second camera being brought on screen (the user picked it, or it saw motion): its picture is
 * built underneath, and fades in over this one the moment its first frame is drawn. Nothing goes blank in between.
 */
@Composable
fun CameraScreen(
    client: CameraClient, incoming: CameraClient?, hub: WatchHub, glass: GlassState,
    onCameras: () -> Unit, onActivity: () -> Unit, onSettings: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by client.config.collectAsState()
    val status by client.status.collectAsState()
    val link by client.linkState.collectAsState()
    val hello by client.hello.collectAsState()
    val cameras by hub.cameras.collectAsState()

    val haptics = remember { WatchHaptics(ctx) }
    val bezel = remember(client) {
        BezelController(client.configSync(), { client.status.value }, { m -> client.send(m); client.refresh() }, haptics, scope)
    }
    val mode by bezel.mode.collectAsState()
    val overlay by bezel.overlay.collectAsState()
    val placing by bezel.placing.collectAsState()

    // While the tracker is being placed every button is hidden, so the Back button is the way out: it moves on to the
    // next control (and the buttons come back with it). Outside the tracker it keeps its normal job.
    BackHandler(enabled = placing) { bezel.cycleMode(1) }

    // Where the menu chip is, so a finger on it is a tap on the menu and not a placement of the tracker box.
    var chipBounds by remember { mutableStateOf(Rect.Zero) }
    val chipNow by rememberUpdatedState(chipBounds)

    // Every click of the bezel goes straight to the controller, whatever has focus.
    DisposableEffect(bezel) {
        val mine: (Float) -> Unit = { bezel.onDetents(it) }
        Rotary.sink = mine
        onDispose { if (Rotary.sink === mine) Rotary.sink = null }
    }

    val g = rememberGeometry(client, hub)
    val decoder = remember(client) { hub.decoder(client) }
    val rendered by decoder.rendered.collectAsState()

    // The second picture fades in once it has something to show.
    val noFrames = remember { MutableStateFlow(0L) }
    val incomingFrames by remember(incoming) { incoming?.let { hub.decoder(it).rendered } ?: noFrames }.collectAsState()
    val fade by animateFloatAsState(if (incoming != null && incomingFrames > 0) 1f else 0f, tween(280), label = "handover")

    val cfg = config
    val armed = status?.armed == true
    val arming = status?.armingIn ?: 0
    val accent = if (armed) Heat.Green else Heat.Orange
    val fraction = cfg?.let { BezelController.fraction(mode, it) } ?: 0f

    // The line shows for a moment after the bezel moves or the mode changes (not on first open), and stays while placing.
    var lineShown by remember { mutableStateOf(false) }
    var lineReady by remember { mutableStateOf(false) }
    LaunchedEffect(mode, fraction) {
        if (!lineReady) { lineReady = true; return@LaunchedEffect }
        lineShown = true
        delay(LINE_LINGER_MS)
        lineShown = false
    }

    // "Clear view": every button steps aside so the picture is the whole screen. One faint eye stays to bring them back.
    var clear by remember { mutableStateOf(false) }

    val waited = rememberWaitSeconds(link.phase == LinkState.Phase.UP)

    GlassHost(
        glass,
        Modifier.fillMaxSize().background(Color.Black),
        backdrop = {
            Box(Modifier.fillMaxSize()) {
                key(client.cameraId) { CameraPicture(client, hub, placing) }
                if (incoming != null) key(incoming.cameraId) { CameraPicture(incoming, hub, false, Modifier.alpha(fade)) }
            }
        }
    ) {
        Box(
            Modifier.fillMaxSize().pointerInput(placing, g.w, g.h, g.rotation) {
                if (placing) {
                    if (g.w <= 0 || g.h <= 0) return@pointerInput
                    // Put the box under the finger the instant it lands, and keep it there as the finger moves.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // A finger on the menu chip belongs to the chip: leave it alone.
                        if (chipNow.inflate(6f).contains(down.position)) {
                            do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                            return@awaitEachGesture
                        }
                        placeZone(bezel, down.position, size.width, size.height, g.w, g.h, g.rotation)
                        do {
                            val event = awaitPointerEvent()
                            for (change in event.changes) {
                                if (change.positionChanged()) {
                                    placeZone(bezel, change.position, size.width, size.height, g.w, g.h, g.rotation)
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
            // What the real bezel is adjusting, as a hairline along the screen edge: it fades in while you turn,
            // grows round the rim one step at a time, closes into a full circle on the last step, and fades away.
            BezelLine(fraction, accent, visible = placing || lineShown, Modifier.fillMaxSize())

            // The menu.
            GlassSurface(
                glass,
                Modifier.align(Alignment.TopCenter).padding(top = 18.dp)
                    .onGloballyPositioned { chipBounds = it.boundsInParent() }
                    .pointerInput(Unit) {
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

            // The ring of buttons. Gone while placing the tracker so nothing is in the way.
            if (!placing) {
                val torch = Controls.switchFor(BezelMode.FLASH)
                EdgeButtons(
                    clear = clear,
                    armed = armed, arming = arming,
                    torchAvailable = status?.hasTorch == true && torch != null, torchOn = cfg != null && torch != null && torch.read(cfg),
                    otherCameras = cameras.size > 1,
                    onArm = { client.arm(!(armed || arming > 0)); haptics.action() },
                    onTorch = { if (torch != null) client.edit { c -> torch.write(c, !torch.read(c)) } },
                    onFlip = {
                        client.edit { c -> c.copy(camera = c.camera.copy(facing = if (c.camera.facing == Facing.REAR) Facing.FRONT else Facing.REAR)) }
                        haptics.action()
                    },
                    onRooms = onCameras, onPictures = onActivity, onSettings = onSettings,
                    onClear = { clear = !clear; haptics.action() }
                )
            }
        }
    }
}

/** What the picture is stretched to, which is also where the tracker box's touch coordinates come from. */
private class Geometry(val w: Int, val h: Int, val rotation: Int)

/**
 * Until the camera reports its picture size, assume what it said last time: the decoder can be built now
 * instead of after the first status message. If the guess is wrong the real report rebuilds it.
 */
@Composable
private fun rememberGeometry(client: CameraClient, hub: WatchHub): Geometry {
    val status by client.status.collectAsState()
    val cached = remember(client) { hub.hints.geometry(client.cameraId.hex) }
    val stream = status?.stream
    return Geometry(stream?.width ?: cached?.width ?: 0, stream?.height ?: cached?.height ?: 0, stream?.rotation ?: cached?.rotation ?: 0)
}

/**
 * One camera's picture: the live video on a surface the decoder draws to, its last still while it starts, and the
 * tracker box. It is a layer on its own so two cameras can be on screen at once during a hand-over.
 */
@Composable
private fun CameraPicture(client: CameraClient, hub: WatchHub, placing: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by client.config.collectAsState()
    val g = rememberGeometry(client, hub)
    val decoder = remember(client) { hub.decoder(client) }
    val rendered by decoder.rendered.collectAsState()
    var surface by remember { mutableStateOf<Surface?>(null) }
    var textureView by remember { mutableStateOf<TextureView?>(null) }

    // The last picture from this camera, shown dimmed the instant the screen opens and faded out when live video starts.
    val still by produceState<Bitmap?>(null, client) { value = withContext(Dispatchers.IO) { LastPicture.load(ctx, client.cameraId.hex) } }
    val stillAlpha by animateFloatAsState(if (rendered > 0) 0f else 0.55f, tween(450), label = "still")
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, client) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && decoder.rendered.value > 0) {
                val tv = textureView
                if (tv != null && tv.isAvailable) {
                    val bmp = runCatching { tv.getBitmap(192, 192) }.getOrNull()
                    if (bmp != null) scope.launch(Dispatchers.IO) { LastPicture.save(ctx, client.cameraId.hex, bmp) }
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // The view's layout listener outlives this composition's values, so it reads the latest through this holder.
    val live = remember { IntArray(3) }
    live[0] = g.w; live[1] = g.h; live[2] = g.rotation

    // The decoder is already collecting frames; this just points it at the glass once both are ready.
    LaunchedEffect(surface, g.w, g.h) {
        val s = surface
        if (s != null && s.isValid && g.w > 0 && g.h > 0) decoder.attach(s, g.w, g.h)
    }
    DisposableEffect(decoder) { onDispose { decoder.detach() } }

    Box(modifier.fillMaxSize().clip(CircleShape).background(Color.Black)) {
        AndroidView(
            factory = { c ->
                TextureView(c).apply {
                    textureView = this
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
            update = { tv -> tv.setTransform(previewMatrix(tv.width.toFloat(), tv.height.toFloat(), g.w, g.h, g.rotation)) },
            modifier = Modifier.fillMaxSize()
        )
        still?.let { bmp ->
            if (stillAlpha > 0.01f) {
                Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize().alpha(stillAlpha), contentScale = ContentScale.Crop)
            }
        }
        ZoneOverlay(config?.detect?.zone, g.w, g.h, g.rotation, active = placing)
    }
}

// ── the ring of buttons ──────────────────────────────────────────────

/**
 * Seven buttons on the rim of the round screen, where a thumb rests. Angles are clockwise from twelve o'clock, radii a
 * fraction of the screen's own: power (arm) at nine, flip at three, settings bottom-left, the rooms at the bottom,
 * alerts top-right, torch top-left, clear view bottom-right. The menu chip owns the top centre.
 */
@Composable
private fun EdgeButtons(
    clear: Boolean, armed: Boolean, arming: Int,
    torchAvailable: Boolean, torchOn: Boolean, otherCameras: Boolean,
    onArm: () -> Unit, onTorch: () -> Unit, onFlip: () -> Unit,
    onRooms: () -> Unit, onPictures: () -> Unit, onSettings: () -> Unit, onClear: () -> Unit
) {
    val fade by animateFloatAsState(if (clear) 0f else 1f, tween(200), label = "buttons")
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val r = minOf(maxWidth, maxHeight) / 2
        fun Modifier.at(angle: Double, radius: Float): Modifier {
            val a = Math.toRadians(angle)
            return this.align(Alignment.Center).offset(x = r * (radius * Math.sin(a)).toFloat(), y = r * (-radius * Math.cos(a)).toFloat())
        }
        if (fade > 0.01f) {
            val m = Modifier.alpha(fade)
            EdgeButton(HeatIcon.Power, SMALL, m.at(270.0, 0.80f), onClick = onArm, tint = if (armed) Heat.Green else Heat.Text, fill = if (armed) Color(0x4034D27A) else DISC, label = if (arming > 0) "$arming" else null)
            EdgeButton(HeatIcon.Flip, SMALL, m.at(90.0, 0.80f), onClick = onFlip)
            EdgeButton(HeatIcon.Gear, SMALL, m.at(222.0, 0.84f), onClick = onSettings)
            EdgeButton(HeatIcon.Pictures, SMALL, m.at(60.0, 0.84f), onClick = onPictures)
            if (torchAvailable) EdgeButton(HeatIcon.Bolt, SMALL, m.at(300.0, 0.84f), onClick = onTorch, tint = if (torchOn) Heat.Amber else Heat.Text, fill = if (torchOn) Color(0x40FFC247) else DISC)
            if (otherCameras) EdgeButton(HeatIcon.Rooms, BIG, m.at(180.0, 0.76f), onClick = onRooms, tint = Heat.Orange, ring = Heat.Orange)
        }
        EdgeButton(if (clear) HeatIcon.Eye else HeatIcon.EyeOff, SMALL, Modifier.at(138.0, 0.84f).alpha(if (clear) 0.45f else 1f), onClick = onClear)
    }
}

@Composable
private fun EdgeButton(
    icon: HeatIcon, size: Dp, modifier: Modifier, onClick: () -> Unit,
    tint: Color = Heat.Text, fill: Color = DISC, ring: Color? = null, label: String? = null
) {
    Box(
        modifier.size(size).clip(CircleShape).background(fill)
            .border(if (ring != null) 2.dp else 0.5.dp, ring ?: Color(0x33FFFFFF), CircleShape)
            .pointerInput(onClick) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center
    ) {
        if (label != null) HeatText(label, size = 15, weight = FontWeight.Bold, color = Heat.Amber)
        else HeatIconView(icon, tint, if (size == BIG) 22.dp else 17.dp)
    }
}

private val SMALL = 34.dp
private val BIG = 46.dp
private val DISC = Color(0x8C000000)

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
private fun LinkDot(link: LinkState) {
    val color = when (link.phase) {
        LinkState.Phase.UP -> Heat.Green
        LinkState.Phase.UNPAIRED -> Heat.Red
        else -> Heat.Amber
    }
    val icon = if (link.carrier?.isBluetooth == true) HeatIcon.Bluetooth else HeatIcon.Wifi
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

private const val LINE_LINGER_MS = 1_600L

/**
 * A thin line riding the very edge of the round screen, starting at twelve o'clock and running clockwise. It is
 * nothing at the first step and a complete circle at the last, so its length is how far through the setting's own
 * steps the bezel is. Plain colour, no glass; when it is faded out nothing is drawn at all.
 */
@Composable
private fun BezelLine(fraction: Float, color: Color, visible: Boolean, modifier: Modifier) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 160 else 650), label = "bezelLineAlpha")
    val sweep by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(130), label = "bezelLineSweep")
    if (alpha < 0.01f) return
    Canvas(modifier) {
        if (sweep < 0.002f) return@Canvas
        val stroke = 1.5.dp.toPx()
        val inset = 1.dp.toPx() + stroke / 2
        val d = size.minDimension - inset * 2
        drawArc(
            color.copy(alpha = alpha), -90f, 360f * sweep, false,
            Offset(inset, inset), Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round)
        )
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
