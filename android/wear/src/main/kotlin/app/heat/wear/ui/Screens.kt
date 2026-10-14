package app.heat.wear.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import app.heat.core.host.CameraClient
import app.heat.core.link.LinkState
import app.heat.core.model.AntiFlicker
import app.heat.core.model.CameraConfig
import app.heat.core.model.CameraSteps
import app.heat.core.model.WhiteBalance
import app.heat.core.model.ConfigPatch
import app.heat.core.model.HapticProfile
import app.heat.core.model.NightMode
import app.heat.core.model.ProtoJson
import app.heat.core.model.StreamQuality
import app.heat.glass.GlassState
import app.heat.glass.Heat
import app.heat.glass.HeatIcon
import app.heat.glass.HeatIconView
import app.heat.glass.HeatText
import app.heat.platform.fingerprint
import app.heat.wear.WatchHub
import kotlinx.serialization.json.jsonObject

private enum class Screen { Camera, Cameras, Settings }

@Composable
fun WatchRoot(hub: WatchHub) {
    val glass = remember { GlassState() }
    val pairing by hub.pairing.collectAsState()
    val cameras by hub.cameras.collectAsState()
    val selectedId by hub.selectedId.collectAsState()
    var screen by rememberSaveable { mutableStateOf(Screen.Camera) }
    BackHandler(enabled = screen != Screen.Camera) { screen = Screen.Camera }

    val client = cameras.firstOrNull { it.cameraId == selectedId } ?: cameras.firstOrNull()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            client == null -> Waiting(paired = pairing != null)
            screen == Screen.Cameras -> CamerasScreen(hub, cameras, client) { screen = Screen.Camera }
            screen == Screen.Settings -> SettingsScreen(client, cameras, hub) { screen = Screen.Camera }
            else -> CameraScreen(client, hub, glass, onCameras = { screen = Screen.Cameras }, onSettings = { screen = Screen.Settings })
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
    ScalingLazyColumn(Modifier.fillMaxSize(), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { HeatText("Cameras", size = 15, weight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp)) }
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

            item { SectionLabel("Detection") }
            item { Stepper("Sensitivity", c.detect.sensitivity, 1..100, 5, { "$it%" }) { v -> edit { it.copy(detect = it.detect.copy(sensitivity = v)) } } }
            item { Stepper("Alert gap", c.detect.cooldownSec, 0..600, 5, { "${it}s" }) { v -> edit { it.copy(detect = it.detect.copy(cooldownSec = v)) } } }
            item { Stepper("Confirm frames", c.detect.persistence, 1..10, 1, { "$it" }) { v -> edit { it.copy(detect = it.detect.copy(persistence = v)) } } }
            item { Stepper("Arm delay", c.detect.armDelaySec, 0..60, 5, { "${it}s" }) { v -> edit { it.copy(detect = it.detect.copy(armDelaySec = v)) } } }
            item { Toggle("Ignore light changes", c.detect.rejectLightChanges) { v -> edit { it.copy(detect = it.detect.copy(rejectLightChanges = v)) } } }
            item { Toggle("Tamper alert", c.detect.tamper) { v -> edit { it.copy(detect = it.detect.copy(tamper = v)) } } }

            item { SectionLabel("Camera") }
            item { Cycle("Night", c.camera.night, NightMode.entries, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { v -> edit { it.copy(camera = it.camera.copy(night = v)) } } }
            item { Toggle("Lock exposure", c.camera.lockExposure) { v -> edit { it.copy(camera = it.camera.copy(lockExposure = v)) } } }
            item { Cycle("Flicker", c.camera.antiFlicker, AntiFlicker.entries, { when (it) { AntiFlicker.AUTO -> "Auto"; AntiFlicker.HZ50 -> "50 Hz"; AntiFlicker.HZ60 -> "60 Hz"; AntiFlicker.OFF -> "Off" } }) { v -> edit { it.copy(camera = it.camera.copy(antiFlicker = v)) } } }
            item { Cycle("Lens", c.camera.facing, app.heat.core.model.Facing.entries, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { v -> edit { it.copy(camera = it.camera.copy(facing = v)) } } }
            item { Cycle("White balance", c.camera.whiteBalance, WhiteBalance.entries, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { v -> edit { it.copy(camera = it.camera.copy(whiteBalance = v)) } } }
            item { Cycle("Focus", c.camera.focus, listOf(-1, 0, 25, 50, 75, 100), { when (it) { -1 -> "Auto"; 0 -> "Far"; 100 -> "Near"; else -> "$it%" } }) { v -> edit { it.copy(camera = it.camera.copy(focus = v)) } } }
            item { Cycle("ISO", c.camera.iso, CameraSteps.ISO, { if (it == 0) "Auto" else "$it" }) { v -> edit { it.copy(camera = it.camera.copy(iso = v)) } } }
            item { Cycle("Shutter", c.camera.shutterDenom, CameraSteps.SHUTTER, { if (it == 0) "Auto" else "1/$it" }) { v -> edit { it.copy(camera = it.camera.copy(shutterDenom = v)) } } }
            item { Toggle("Blend lenses", c.camera.blendLenses) { v -> edit { it.copy(camera = it.camera.copy(blendLenses = v)) } } }

            item { SectionLabel("Video") }
            item { Cycle("Quality", c.stream.quality, StreamQuality.entries, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { v -> edit { it.copy(stream = it.stream.copy(quality = v)) } } }
            item { Toggle("Microphone", c.stream.allowAudio) { v -> edit { it.copy(stream = it.stream.copy(allowAudio = v)) } } }

            item { SectionLabel("Alerts") }
            item { Cycle("Wrist haptics", c.alerts.watchHaptics, HapticProfile.entries, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { v -> edit { it.copy(alerts = it.alerts.copy(watchHaptics = v)) } } }
            item { Toggle("Phone sound", c.alerts.phoneSound) { v -> edit { it.copy(alerts = it.alerts.copy(phoneSound = v)) } } }

            item { SectionLabel("Power") }
            item { Toggle("Black screen when armed", c.power.blackoutWhileArmed) { v -> edit { it.copy(power = it.power.copy(blackoutWhileArmed = v)) } } }
            item { Stepper("Stop below", c.power.lowBatteryStopPercent, 0..50, 5, { if (it == 0) "Never" else "$it%" }) { v -> edit { it.copy(power = it.power.copy(lowBatteryStopPercent = v)) } } }

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
