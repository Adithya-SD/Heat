package app.heat.wear.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import app.heat.core.host.CameraClient
import app.heat.core.link.LinkState
import app.heat.core.model.AlertKind
import app.heat.core.model.AntiFlicker
import app.heat.core.model.CameraConfig
import app.heat.core.model.CameraSteps
import app.heat.core.model.WhiteBalance
import app.heat.core.model.ConfigPatch
import app.heat.core.model.HapticProfile
import app.heat.core.model.NightMode
import app.heat.core.model.ProtoJson
import app.heat.core.model.StreamQuality
import app.heat.core.settings.Capabilities
import app.heat.core.settings.Section
import app.heat.core.settings.SettingDef
import app.heat.core.settings.SettingKind
import app.heat.core.settings.SettingsCatalog
import app.heat.glass.GlassState
import app.heat.glass.Heat
import app.heat.glass.HeatIcon
import app.heat.glass.HeatIconView
import app.heat.glass.HeatText
import app.heat.platform.fingerprint
import app.heat.wear.WatchHub
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject

private enum class Screen { Camera, Cameras, Settings, Activity }

@Composable
fun WatchRoot(hub: WatchHub) {
    // The watch caps the glass at refraction without colour fringing or frost: visually the same on a screen this small,
    // and about a seventh of the per-pixel work, which is what lets it run for hours.
    val glass = remember { GlassState(maxQuality = 1) }
    val pairing by hub.pairing.collectAsState()
    val cameras by hub.cameras.collectAsState()
    val selectedId by hub.selectedId.collectAsState()
    val visible by hub.visible.collectAsState()
    var screen by rememberSaveable { mutableStateOf(Screen.Camera) }
    BackHandler(enabled = screen != Screen.Camera) { screen = Screen.Camera }

    val target = cameras.firstOrNull { it.cameraId == selectedId } ?: cameras.firstOrNull()

    // The camera on screen follows the selection by a cross-fade: the new one is built underneath while the old one
    // keeps playing, and fades in over it when its first frame is drawn. A switch (picked by hand, or because a camera
    // saw movement) therefore never shows a blank or a spinner. Off screen or in a menu there is nobody to see a fade.
    var shown by remember { mutableStateOf<CameraClient?>(null) }
    var incoming by remember { mutableStateOf<CameraClient?>(null) }
    LaunchedEffect(target?.cameraId, cameras.size, visible, screen) {
        val t = target
        val s = shown
        when {
            t == null -> { shown = null; incoming = null }
            s == null || cameras.none { it.cameraId == s.cameraId } -> { shown = t; incoming = null }
            s.cameraId == t.cameraId -> incoming = null
            !visible || screen != Screen.Camera -> { shown = t; incoming = null; hub.release(s.cameraId) }
            else -> {
                incoming = t
                withTimeoutOrNull(2_500) { hub.decoder(t).rendered.first { it > 0 } }
                delay(300)                                  // the fade itself
                shown = t
                incoming = null
                hub.release(s.cameraId)
            }
        }
    }
    val client = shown ?: target

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            client == null -> Waiting(paired = pairing != null)
            screen == Screen.Cameras -> CamerasScreen(hub, cameras, client) { screen = Screen.Camera }
            screen == Screen.Settings -> SettingsScreen(client, cameras, hub) { screen = Screen.Camera }
            screen == Screen.Activity -> GalleryScreen(hub) { screen = Screen.Camera }
            else -> CameraScreen(
                client, incoming, hub, glass,
                onCameras = { screen = Screen.Cameras }, onActivity = { screen = Screen.Activity }, onSettings = { screen = Screen.Settings }
            )
        }
    }
}

@Composable
private fun Waiting(paired: Boolean) {
    Column(Modifier.fillMaxSize().padding(26.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        HeatText(if (paired) "Looking for cameras…" else "Waiting for your phone", size = 15, weight = FontWeight.SemiBold, align = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        HeatText(
            if (paired) "Open Heat on a phone and start its camera." else "Open Heat on your phone once. It shares its key with this watch.",
            size = 12, color = Heat.TextDim, align = TextAlign.Center
        )
    }
}

// ── cameras ──────────────────────────────────────────────────────────

@Composable
private fun CamerasScreen(hub: WatchHub, cameras: List<CameraClient>, current: CameraClient, back: () -> Unit) {
    val list = rememberScalingLazyListState()
    val follow by hub.followMotion.collectAsState()
    ScalingLazyColumn(Modifier.fillMaxSize(), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { HeatText("Cameras", size = 15, weight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp)) }
        item { Toggle("Follow motion", follow) { hub.setFollowMotion(it) } }
        item { val hold by hub.followHoldSec.collectAsState(); Stepper("Hold a camera", hold, 0..30, 1, { if (it == 0) "off" else "${it}s" }) { hub.setFollowHoldSec(it) } }
        for (c in cameras) item(key = c.cameraId.hex) { CameraRow(c, selected = c === current) { hub.select(c.cameraId); back() } }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun CameraRow(c: CameraClient, selected: Boolean, onClick: () -> Unit) {
    val link by c.linkState.collectAsState()
    val hello by c.hello.collectAsState()
    val status by c.status.collectAsState()
    Row(
        Modifier.fillMaxWidth(0.88f).clip(RoundedCornerShape(20.dp)).background(if (selected) Color(0x44FF7A2F) else Color(0x26FFFFFF))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(9.dp).background(if (link.phase == LinkState.Phase.UP) Heat.Green else Heat.Amber, CircleShape))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            HeatText(hello?.name ?: "Camera ${c.cameraId.short}", size = 14, weight = FontWeight.SemiBold, maxLines = 1)
            HeatText(
                when (link.phase) {
                    LinkState.Phase.UP -> (link.carrier?.label ?: "Connected") + if (status?.armed == true) " · armed" else ""
                    LinkState.Phase.UNPAIRED -> "Not paired"
                    else -> "Connecting…"
                },
                size = 11, color = Heat.TextDim
            )
        }
    }
}

// ── activity ─────────────────────────────────────────────────────────

@Composable
private fun ActivityScreen(hub: WatchHub, back: () -> Unit) {
    val entries by hub.activity.collectAsState()
    val list = rememberScalingLazyListState()
    ScalingLazyColumn(Modifier.fillMaxSize(), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { HeatText("Activity", size = 15, weight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp)) }
        if (entries.isEmpty()) item { HeatText("Nothing yet. Alerts from every camera appear here with their picture.", size = 12, color = Heat.TextDim, align = TextAlign.Center, modifier = Modifier.fillMaxWidth(0.8f)) }
        for (e in entries) item(key = "${e.camera.hex}-${e.atMs}") {
            ActivityRow(e) { hub.select(e.camera); back() }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ActivityRow(e: WatchHub.ActivityEntry, onClick: () -> Unit) {
    val time = remember(e.atMs) { java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(e.atMs)) }
    Row(
        Modifier.fillMaxWidth(0.88f).clip(RoundedCornerShape(20.dp)).background(Color(0x26FFFFFF)).clickable(onClick = onClick).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val pic = e.picture
        Box(Modifier.size(width = 54.dp, height = 40.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x33FFFFFF)), contentAlignment = Alignment.Center) {
            if (pic != null) Image(pic.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else HeatIconView(HeatIcon.Pictures, Heat.TextFaint, 18.dp)
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            HeatText(e.cameraName, size = 13, weight = FontWeight.SemiBold, maxLines = 1)
            HeatText(
                when (e.kind) {
                    AlertKind.MOTION -> "Motion"
                    AlertKind.TAMPER -> "Moved"
                    AlertKind.LOW_BATTERY -> "Low battery"
                    AlertKind.OVERHEAT -> "Hot"
                    AlertKind.CAMERA_ERROR -> "Camera problem"
                } + " · " + time,
                size = 10, color = Heat.TextDim, maxLines = 1
            )
        }
    }
}

// ── settings ─────────────────────────────────────────────────────────

@Composable
private fun SettingsScreen(client: CameraClient, cameras: List<CameraClient>, hub: WatchHub, back: () -> Unit) {
    val config by client.config.collectAsState()
    val link by client.linkState.collectAsState()
    val status by client.status.collectAsState()
    val pairing by hub.pairing.collectAsState()
    val c = config
    val list = rememberScalingLazyListState()
    ScalingLazyColumn(Modifier.fillMaxSize(), state = list, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        item { HeatText(c?.label ?: "Settings", size = 15, weight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(top = 18.dp, bottom = 2.dp)) }
        if (c == null) {
            item { HeatText("Waiting for the camera…", size = 12, color = Heat.TextDim) }
        } else {
            fun edit(f: (CameraConfig) -> CameraConfig) = client.edit(f)

            // Drawn from the same catalog as the phone's settings page: one list, one set of words.
            val caps = Capabilities(
                hasTorch = status?.hasTorch ?: true,
                manualFocus = status?.manualFocus ?: false,
                manualExposure = status?.manualExposure ?: false
            )
            val grouped = SettingsCatalog.bySection(c, caps)
            for (section in listOf(Section.CAMERA, Section.DETECTION, Section.VIDEO, Section.ALERTS, Section.POWER)) {
                val defs = grouped[section].orEmpty()
                if (defs.isEmpty()) continue
                item(key = "section-${section.name}") { SectionLabel(section.title) }
                for (d in defs) item(key = d.id) { CatalogRow(d, c, ::edit) }
            }

            val others = cameras.filter { it !== client }
            if (others.isNotEmpty()) {
                item { SectionLabel("All cameras") }
                item {
                    ActionRow("Copy these settings to ${others.size} other camera${if (others.size == 1) "" else "s"}") {
                        // Everything except each camera's own placement (name, lens, zoom, torch, tracker box).
                        val patch = ConfigPatch.withoutPerCamera(ProtoJson.encodeToJsonElement(CameraConfig.serializer(), c).jsonObject)
                        others.forEach { o -> o.edit { old -> ConfigPatch.apply(old, patch) ?: old } }
                    }
                }
            }
        }
        // Connection details: what to read out if something will not connect.
        item { SectionLabel("Connection") }
        item { Info("Link", link.phase.name.lowercase().replaceFirstChar(Char::uppercase) + (link.carrier?.let { " · ${it.label}" } ?: "")) }
        if (link.standby.isNotEmpty()) item { Info("Standby", link.standby.joinToString { it.label }) }
        if (link.rttMs >= 0) item { Info("Round trip", "${link.rttMs} ms") }
        client.link.lastFailure?.let { f -> item { Info("Last failure", f.name.lowercase()) } }
        item { Info("Pairing code", pairing?.key?.fingerprint() ?: "none") }
        item { Info("Camera", client.cameraId.short) }
        item { Info("Wi-Fi", if (hub.onWifi) "Joined" else "Not joined") }
        item { Info("Bezel", "${app.heat.wear.Rotary.events} clicks") }
        status?.stream?.let { s -> item { Info("Stream", "${s.width}x${s.height} · ${s.bitrate / 1000} kbps") } }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

/** One catalog entry, with the control its kind calls for. Text and info entries are the phone's business. */
@Composable
private fun CatalogRow(d: SettingDef, c: CameraConfig, edit: ((CameraConfig) -> CameraConfig) -> Unit) {
    when (val k = d.kind) {
        is SettingKind.Toggle -> Toggle(d.title, k.read(c)) { on -> edit { k.write(it, on) } }
        is SettingKind.Choice -> Cycle(d.title, k.read(c), k.labels.indices.toList(), { k.labels[it] }) { i -> edit { k.write(it, i) } }
        is SettingKind.Slider -> Stepper(d.title, k.read(c), k.range, k.step, k.format) { v -> edit { k.write(it, v) } }
        is SettingKind.Stepper -> Stepper(d.title, k.read(c), k.range, k.step, k.format) { v -> edit { k.write(it, v) } }
        is SettingKind.Text, is SettingKind.Info -> Unit
    }
}

@Composable
private fun Info(label: String, value: String) {
    Shell {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            HeatText(label, size = 11, color = Heat.TextDim, modifier = Modifier.weight(1f))
            HeatText(value, size = 11, weight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    HeatText(text.uppercase(), size = 10, weight = FontWeight.Bold, color = Heat.TextFaint, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Shell(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxWidth(0.9f).clip(RoundedCornerShape(18.dp)).background(Color(0x26FFFFFF))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 12.dp, vertical = 8.dp)
    ) { content() }
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Shell({ onChange(!on) }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            HeatText(label, size = 12, modifier = Modifier.weight(1f))
            Box(Modifier.size(width = 34.dp, height = 20.dp).clip(CircleShape).background(if (on) Heat.Green.copy(alpha = 0.85f) else Color(0x40FFFFFF))) {
                Box(Modifier.padding(2.dp).padding(start = if (on) 14.dp else 0.dp).size(16.dp).clip(CircleShape).background(Color.White))
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, step: Int, format: (Int) -> String, onChange: (Int) -> Unit) {
    Shell {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            HeatText(label, size = 12, modifier = Modifier.weight(1f))
            StepBtn(HeatIcon.Minus, value > range.first) { onChange((value - step).coerceAtLeast(range.first)) }
            HeatText(format(value), size = 12, weight = FontWeight.SemiBold, color = Heat.Orange, modifier = Modifier.width(44.dp), align = TextAlign.Center)
            StepBtn(HeatIcon.Plus, value < range.last) { onChange((value + step).coerceAtMost(range.last)) }
        }
    }
}

@Composable
private fun StepBtn(icon: HeatIcon, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(28.dp).clip(CircleShape).background(Color(0x33FFFFFF)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { HeatIconView(icon, if (enabled) Heat.Text else Heat.TextFaint, 14.dp) }
}

@Composable
private fun <T> Cycle(label: String, value: T, options: List<T>, text: (T) -> String, onChange: (T) -> Unit) {
    Shell({ onChange(options[(options.indexOf(value) + 1) % options.size]) }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            HeatText(label, size = 12, modifier = Modifier.weight(1f))
            HeatText(text(value), size = 12, weight = FontWeight.SemiBold, color = Heat.Orange)
        }
    }
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
    Shell(onClick) { HeatText(label, size = 12, color = Heat.OrangeSoft, weight = FontWeight.SemiBold) }
}
