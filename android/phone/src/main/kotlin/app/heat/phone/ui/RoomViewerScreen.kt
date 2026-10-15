package app.heat.phone.ui

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.heat.core.link.LinkState
import app.heat.core.model.Facing
import app.heat.glass.Heat
import app.heat.glass.HeatIcon
import app.heat.glass.HeatIconView
import app.heat.glass.HeatText
import app.heat.phone.rooms.RoomManager
import app.heat.phone.rooms.RoomViewer
import kotlin.math.min

/**
 * Watching somebody else's camera in a room: the picture, what it is doing, and the same controls the watch has
 * (arm, sensitivity, zoom, torch, flip), each one only a message to that camera.
 */
@Composable
fun RoomViewerScreen(
    viewer: RoomViewer,
    hub: app.heat.phone.service.CameraHub,
    glass: app.heat.glass.GlassState,
    onSwitch: (RoomViewer) -> Unit,
    onClose: () -> Unit
) {
    // 0: the picture, 1: this host's full settings, 2: who is here and which hosts are in the room
    var panel by remember { mutableStateOf(0) }
    BackHandler(enabled = panel != 0) { panel = 0 }
    BackHandler(enabled = panel == 0, onBack = onClose)
    val client = viewer.client
    val config by client.config.collectAsState()
    val status by client.status.collectAsState()
    val link by client.linkState.collectAsState()
    val hello by client.hello.collectAsState()
    val rendered by viewer.decoder.rendered.collectAsState()
    val thumbs by viewer.thumbnails.collectAsState()
    val ctx = LocalContext.current

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val stream = status?.stream
    val vw = stream?.width ?: 0
    val vh = stream?.height ?: 0
    val rotation = stream?.rotation ?: 0
    val live = remember { IntArray(3) }
    live[0] = vw; live[1] = vh; live[2] = rotation

    var surface by remember { mutableStateOf<Surface?>(null) }
    LaunchedEffect(surface, vw, vh) {
        val s = surface
        if (s != null && s.isValid && vw > 0 && vh > 0) viewer.decoder.attach(s, vw, vh)
    }
    DisposableEffect(viewer) { onDispose { viewer.decoder.detach() } }

    val cfg = config
    val armed = status?.armed == true
    val arming = status?.armingIn ?: 0
    val name = cfg?.label ?: hello?.name ?: "Camera"
    val fresh = rememberFreshFrames(viewer.decoder)
    val isLive = link.phase == LinkState.Phase.UP && rendered > 0 && fresh

    Box(Modifier.fillMaxSize().background(Color.Black)) {
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
                            viewer.decoder.detach { s?.release() }
                            return true
                        }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                    addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                        (v as TextureView).setTransform(containMatrix(v.width.toFloat(), v.height.toFloat(), live[0], live[1], live[2]))
                    }
                }
            },
            update = { tv -> tv.setTransform(containMatrix(tv.width.toFloat(), tv.height.toFloat(), vw, vh, rotation)) },
            modifier = Modifier.fillMaxSize()
        )
        // Safety: a stopped picture must never pass for a live one. No fresh frames, no picture.
        if (!fresh) Box(Modifier.fillMaxSize().background(Color.Black))

        Box(Modifier.fillMaxWidth().height(140.dp).background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(400.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)))))

        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).matte(CircleShape).tappable(onClose), contentAlignment = Alignment.Center) {
                    HeatIconView(HeatIcon.Close, Heat.Text, 20.dp)
                }
                Spacer(Modifier.width(10.dp))
                Row(Modifier.weight(1f, fill = false).matte(CircleShape).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val dot = when {
                        isLive -> Heat.Green
                        link.phase == LinkState.Phase.UNPAIRED -> Heat.Red
                        else -> Heat.Amber
                    }
                    Box(Modifier.size(9.dp).background(dot, CircleShape))
                    Spacer(Modifier.width(9.dp))
                    HeatText(
                        when {
                            isLive -> name
                            link.phase == LinkState.Phase.UNPAIRED -> "That room did not accept this code"
                            link.phase == LinkState.Phase.UP -> "Starting picture…"
                            else -> "Looking for the camera…"
                        },
                        size = 14, weight = FontWeight.SemiBold, maxLines = 1
                    )
                    status?.battery?.takeIf { it >= 0 }?.let {
                        Spacer(Modifier.width(10.dp))
                        HeatText("$it%", size = 13, color = if (it <= 20) Heat.Amber else Heat.TextDim)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(42.dp).matte(CircleShape).tappable({ panel = 2 }), contentAlignment = Alignment.Center) { HeatIconView(HeatIcon.List, Heat.Text, 20.dp) }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(42.dp).matte(CircleShape).tappable({ panel = 1 }), contentAlignment = Alignment.Center) { HeatIconView(HeatIcon.Gear, Heat.Text, 20.dp) }
            }

            Spacer(Modifier.weight(1f))

            if (thumbs.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (b in thumbs) {
                        Image(b.asImageBitmap(), null, Modifier.size(width = 84.dp, height = 63.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (cfg != null) {
                val lenses = status?.lenses.orEmpty().filter { it.ratio > 0f }.sortedBy { it.ratio }
                Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().matte(RoundedCornerShape(24.dp)).padding(vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        HeatIconView(HeatIcon.Waves, Heat.Ember1, 18.dp)
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.weight(1f)) {
                            GlassSlider(cfg.detect.sensitivity, 1..100, 1) { v -> client.edit { it.copy(detect = it.detect.copy(sensitivity = v)) } }
                        }
                        Spacer(Modifier.width(10.dp))
                        HeatText("${cfg.detect.sensitivity}", size = 14, color = Heat.Ember1, weight = FontWeight.SemiBold, modifier = Modifier.width(30.dp))
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        HeatIconView(HeatIcon.Zoom, Heat.Ember1, 18.dp)
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.weight(1f)) {
                            // log scale: equal distance is an equal ratio, as on the camera's own screen
                            val z = cfg.camera.zoom.coerceIn(0.5f, 10f)
                            val pos = (Math.log((z / 0.5f).toDouble()) / Math.log(20.0) * 100).toInt()
                            GlassSlider(pos, 0..100, 1) { p ->
                                val zoom = (0.5 * Math.pow(20.0, p / 100.0)).toFloat()
                                client.edit { it.copy(camera = it.camera.copy(zoom = zoom)) }
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        HeatText("%.1fx".format(java.util.Locale.US, cfg.camera.zoom), size = 14, color = Heat.Ember1, weight = FontWeight.SemiBold, modifier = Modifier.width(44.dp))
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        for (l in lenses) {
                            Chip(l.label.ifBlank { "%.1fx".format(java.util.Locale.US, l.ratio) }, Math.abs(cfg.camera.zoom - l.ratio) < 0.12f) {
                                client.edit { it.copy(camera = it.camera.copy(zoom = l.ratio)) }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (status?.hasTorch == true) IconChip(HeatIcon.Bolt, cfg.camera.torch) { client.edit { it.copy(camera = it.camera.copy(torch = !it.camera.torch)) } }
                        IconChip(HeatIcon.Flip, cfg.camera.facing == Facing.FRONT) {
                            client.edit { it.copy(camera = it.camera.copy(facing = if (it.camera.facing == Facing.REAR) Facing.FRONT else Facing.REAR)) }
                        }
                        IconChip(HeatIcon.Share, false) { shareRoom(ctx, viewer.code) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                ArmTrack(
                    armed = armed, arming = arming, delaySec = cfg.detect.armDelaySec,
                    onArm = { client.arm(true, cfg.detect.armDelaySec) },
                    onDisarm = { client.arm(false) },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(Modifier.height(14.dp))
            } else {
                Spacer(Modifier.height(28.dp))
            }
        }

        if (panel == 1) SettingsScreen(hub, glass, onBack = { panel = 0 }, client = client)
        if (panel == 2) PeoplePanel(viewer, hub, onSwitch, onClose = { panel = 0 }, onLeave = onClose)

        if (!isLive && panel == 0) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    HeatText(
                        when {
                            link.phase == LinkState.Phase.UNPAIRED -> "Not a room we can open"
                            link.phase == LinkState.Phase.UP -> "Starting the picture…"
                            else -> "Looking for the camera…"
                        },
                        size = 18, color = Heat.TextDim
                    )
                    HeatText(
                        if (link.phase == LinkState.Phase.UNPAIRED) "The code may have been changed. Ask for the new one." else "The camera has to be running Heat with its room open.",
                        size = 13, color = Heat.TextFaint, modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

/** Who is connected to this host (anyone may remove anyone), and the other hosts in the room (switch to one, or remove it). */
@Composable
private fun PeoplePanel(viewer: RoomViewer, hub: app.heat.phone.service.CameraHub, onSwitch: (RoomViewer) -> Unit, onClose: () -> Unit, onLeave: () -> Unit) {
    val roster by viewer.client.roster.collectAsState()
    val selfHex = hub.deviceId.hex
    val siblings = remember(roster) { hub.relay.siblings(viewer.code) }
    Column(Modifier.fillMaxSize().background(Heat.Ink).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).matte(CircleShape).tappable(onClose), contentAlignment = Alignment.Center) { HeatIconView(HeatIcon.Back, Heat.Text, 20.dp) }
            Spacer(Modifier.width(14.dp))
            HeatText("This room", size = 22, weight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HeatText("HOSTS", size = 11, weight = FontWeight.SemiBold, color = Heat.TextFaint)
            for (v in siblings) {
                val cfg by v.client.config.collectAsState()
                val link by v.client.linkState.collectAsState()
                val here = v === viewer
                Row(
                    Modifier.fillMaxWidth().matte(RoundedCornerShape(16.dp), if (here) Heat.Ember0 else Heat.MatteHi).tappable({ if (!here) onSwitch(v) }).padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(9.dp).background(if (link.phase == LinkState.Phase.UP) Heat.Green else Heat.Amber, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        HeatText(cfg?.label ?: "Camera ${v.slot + 1}", size = 15, weight = FontWeight.SemiBold, maxLines = 1)
                        HeatText(if (here) "Showing" else if (link.phase == LinkState.Phase.UP) "Tap to show" else "Offline", size = 12, color = if (here) Color.White else Heat.TextDim)
                    }
                    TwoTapRemove { hub.relay.removeHost(v); if (here) onLeave() }
                }
            }
            Box(
                Modifier.fillMaxWidth().matte(RoundedCornerShape(16.dp), Heat.MatteHi).tappable({ hub.rooms.hostIn(viewer.code); onLeave() }).padding(vertical = 13.dp),
                contentAlignment = Alignment.Center
            ) { HeatText("Also host from this phone", size = 14, weight = FontWeight.SemiBold) }
            Spacer(Modifier.height(6.dp))
            HeatText("PEOPLE HERE", size = 11, weight = FontWeight.SemiBold, color = Heat.TextFaint)
            for (m in roster) {
                Row(Modifier.fillMaxWidth().matte(RoundedCornerShape(16.dp), Heat.MatteHi).padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        HeatText(m.name + if (m.id == selfHex) " (you)" else "", size = 15, weight = FontWeight.SemiBold, maxLines = 1)
                        HeatText(if (m.hub) "Hub" else "Viewer", size = 12, color = Heat.TextDim)
                    }
                    if (m.id != selfHex) TwoTapRemove { viewer.client.kick(m.id) }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** Removing someone is two taps: the first turns the button red and says "Remove?", the second does it. */
@Composable
private fun TwoTapRemove(onConfirmed: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { kotlinx.coroutines.delay(3_000); armed = false } }
    Box(
        Modifier.matte(CircleShape, if (armed) Heat.Red else Heat.MatteHi).tappable({ if (armed) { armed = false; onConfirmed() } else armed = true }).padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) { HeatText(if (armed) "Remove?" else "\u2715", size = 13, weight = FontWeight.Bold, color = if (armed) Color.White else Heat.Red) }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.matte(CircleShape, if (on) Heat.Ember0 else Heat.MatteHi).tappable(onClick).padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        HeatText(label, size = 13, weight = FontWeight.SemiBold, color = if (on) Color.White else Heat.TextDim)
    }
}

@Composable
private fun IconChip(icon: HeatIcon, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).matte(CircleShape, if (on) Heat.Ember0 else Heat.MatteHi).tappable(onClick), contentAlignment = Alignment.Center) {
        HeatIconView(icon, if (on) Color.White else Heat.TextDim, 17.dp)
    }
}

private fun shareRoom(ctx: android.content.Context, code: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, "Watch this Heat camera: ${RoomManager.inviteLink(code)}")
    }
    ctx.startActivity(android.content.Intent.createChooser(send, "Share").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * The matrix that shows the decoded picture whole, upright and centred on a [w]x[h] view. A TextureView stretches the
 * frame to its own shape, so the first step undoes that stretch; then it is rotated and fitted.
 */
internal fun containMatrix(w: Float, h: Float, videoW: Int, videoH: Int, rotation: Int): Matrix {
    val m = Matrix()
    if (w <= 0f || h <= 0f || videoW <= 0 || videoH <= 0) return m
    val quarter = (rotation / 90) % 2 != 0
    val rw = if (quarter) videoH.toFloat() else videoW.toFloat()
    val rh = if (quarter) videoW.toFloat() else videoH.toFloat()
    val s = min(w / rw, h / rh)                       // screen pixels per picture pixel
    val cx = w / 2f
    val cy = h / 2f
    m.setScale(videoW * s / w, videoH * s / h, cx, cy)
    m.postRotate(rotation.toFloat(), cx, cy)
    return m
}

/** True only while new frames keep arriving (the last within 1.5 s); false before the first and after they stop. */
@Composable
internal fun rememberFreshFrames(decoder: app.heat.platform.PreviewDecoder): Boolean {
    var lastFrameAt by remember(decoder) { mutableStateOf(0L) }
    var fresh by remember(decoder) { mutableStateOf(false) }
    LaunchedEffect(decoder) {
        decoder.rendered.collect { n ->
            if (n > 0) { lastFrameAt = android.os.SystemClock.elapsedRealtime(); fresh = true } else fresh = false
        }
    }
    LaunchedEffect(decoder) {
        while (true) {
            kotlinx.coroutines.delay(200)
            val f = lastFrameAt > 0 && android.os.SystemClock.elapsedRealtime() - lastFrameAt < 1_500
            if (fresh != f) fresh = f
        }
    }
    return fresh
}
