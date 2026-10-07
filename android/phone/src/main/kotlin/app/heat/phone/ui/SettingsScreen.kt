package app.heat.phone.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.heat.core.model.AntiFlicker
import app.heat.core.model.Facing
import app.heat.core.model.HapticProfile
import app.heat.core.model.NightMode
import app.heat.core.model.StreamQuality
import app.heat.glass.GlassHost
import app.heat.glass.GlassState
import app.heat.glass.Heat
import app.heat.glass.HeatText
import app.heat.glass.HeatIcon
import app.heat.glass.HeatIconView
import app.heat.phone.service.CameraHub

/** Every setting the camera has, on one scrolling page. Edits go straight to the camera's own config, exactly as a viewer's would. */
@Composable
fun SettingsScreen(hub: CameraHub, glass: GlassState, onBack: () -> Unit) {
    val c by hub.host.config.collectAsState()
    val hubState by hub.state.collectAsState()
    fun edit(f: (app.heat.core.model.CameraConfig) -> app.heat.core.model.CameraConfig) = hub.host.editLocally(f)

    GlassHost(
        glass, Modifier.fillMaxSize(),
        backdrop = {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1B1018), Color(0xFF0A0A10), Color(0xFF14090A)))))
        }
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(glass, HeatIcon.Back, onBack, size = 46.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    HeatText("Settings", size = 24, weight = FontWeight.Bold)
                    HeatText(c.label, size = 13, color = Heat.TextFaint)
                }
            }

            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 40.dp)) {
                SettingsGroup(glass, "Camera") {
                    SegmentRow("Lens", listOf("Rear" to Facing.REAR, "Front" to Facing.FRONT), c.camera.facing, { v -> edit { it.copy(camera = it.camera.copy(facing = v)) } })
                    RowDivider()
                    SegmentRow(
                        "Night handling", listOf("Auto" to NightMode.AUTO, "Day" to NightMode.DAY, "Night" to NightMode.NIGHT), c.camera.night,
                        { v -> edit { it.copy(camera = it.camera.copy(night = v)) } },
                        hint = "Auto switches by itself as the room darkens"
                    )
                    RowDivider()
                    StepperRow("Exposure", c.camera.exposure, -10..10, { v -> edit { it.copy(camera = it.camera.copy(exposure = v)) } }, format = { if (it > 0) "+$it" else "$it" })
                    RowDivider()
                    ToggleRow("Flashlight", c.camera.torch, { v -> edit { it.copy(camera = it.camera.copy(torch = v)) } }, hint = if (hubState.hasTorch) null else "This camera has no flash")
                    RowDivider()
                    ToggleRow(
                        "Lock exposure", c.camera.lockExposure, { v -> edit { it.copy(camera = it.camera.copy(lockExposure = v)) } },
                        hint = "Stops auto-exposure steps being read as movement"
                    )
                    RowDivider()
                    SegmentRow(
                        "Anti-flicker", listOf("Auto" to AntiFlicker.AUTO, "50 Hz" to AntiFlicker.HZ50, "60 Hz" to AntiFlicker.HZ60, "Off" to AntiFlicker.OFF), c.camera.antiFlicker,
                        { v -> edit { it.copy(camera = it.camera.copy(antiFlicker = v)) } }, hint = "Match your mains frequency if you see banding"
                    )
                }

                SettingsGroup(glass, "Detection") {
                    SliderRow("Sensitivity", c.detect.sensitivity, 1..100, { v -> edit { it.copy(detect = it.detect.copy(sensitivity = v)) } }, format = { "$it%" }, hint = "Higher notices smaller, fainter movement")
                    RowDivider()
                    StepperRow("Quiet time between alerts", c.detect.cooldownSec, 0..600, { v -> edit { it.copy(detect = it.detect.copy(cooldownSec = v)) } }, step = 5, format = { "${it}s" })
                    RowDivider()
                    StepperRow("Frames to confirm", c.detect.persistence, 1..10, { v -> edit { it.copy(detect = it.detect.copy(persistence = v)) } }, hint = "Higher means fewer false alarms, a little slower")
                    RowDivider()
                    ToggleRow("Ignore lighting changes", c.detect.rejectLightChanges, { v -> edit { it.copy(detect = it.detect.copy(rejectLightChanges = v)) } }, hint = "Headlights and light switches")
                    RowDivider()
                    StepperRow("Arm delay", c.detect.armDelaySec, 0..60, { v -> edit { it.copy(detect = it.detect.copy(armDelaySec = v)) } }, step = 5, format = { "${it}s" }, hint = "Time to leave the room")
                }

                SettingsGroup(glass, "Video to your watch") {
                    SegmentRow(
                        "Quality", listOf("Auto" to StreamQuality.AUTO, "Low" to StreamQuality.LOW, "Med" to StreamQuality.BALANCED, "High" to StreamQuality.HIGH, "Max" to StreamQuality.MAX),
                        c.stream.quality, { v -> edit { it.copy(stream = it.stream.copy(quality = v)) } }, hint = "Auto follows the connection"
                    )
                    RowDivider()
                    SliderRow("Muddiness", c.stream.muddiness, 0..100, { v -> edit { it.copy(stream = it.stream.copy(muddiness = v)) } }, step = 5, hint = "Softer picture, smoother motion on a weak link")
                    RowDivider()
                    ToggleRow("Allow microphone", c.stream.allowAudio, { v -> edit { it.copy(stream = it.stream.copy(allowAudio = v)) } })
                }

                SettingsGroup(glass, "Power") {
                    ToggleRow("Black screen while armed", c.power.blackoutWhileArmed, { v -> edit { it.copy(power = it.power.copy(blackoutWhileArmed = v)) } }, hint = "Saves battery and heat")
                    RowDivider()
                    StepperRow("Stop below battery", c.power.lowBatteryStopPercent, 0..50, { v -> edit { it.copy(power = it.power.copy(lowBatteryStopPercent = v)) } }, step = 5, format = { if (it == 0) "Never" else "$it%" })
                    RowDivider()
                    StepperRow("Warn below battery", c.power.lowBatteryWarnPercent, 0..80, { v -> edit { it.copy(power = it.power.copy(lowBatteryWarnPercent = v)) } }, step = 5, format = { if (it == 0) "Never" else "$it%" })
                }

                SettingsGroup(glass, "Alerts") {
                    ToggleRow("Sound on this phone", c.alerts.phoneSound, { v -> edit { it.copy(alerts = it.alerts.copy(phoneSound = v)) } })
                    RowDivider()
                    SegmentRow(
                        "Watch haptics", listOf("Off" to HapticProfile.OFF, "Soft" to HapticProfile.SOFT, "Normal" to HapticProfile.NORMAL, "Strong" to HapticProfile.STRONG),
                        c.alerts.watchHaptics, { v -> edit { it.copy(alerts = it.alerts.copy(watchHaptics = v)) } }
                    )
                    RowDivider()
                    ToggleRow(
                        "Quiet hours", c.alerts.quietFromMin != null,
                        { on -> edit { it.copy(alerts = it.alerts.copy(quietFromMin = if (on) 22 * 60 else null, quietToMin = if (on) 7 * 60 else null)) } },
                        hint = "Motion alerts are muted between the times below"
                    )
                    if (c.alerts.quietFromMin != null) {
                        RowDivider()
                        StepperRow("From", c.alerts.quietFromMin ?: 0, 0..1425, { v -> edit { it.copy(alerts = it.alerts.copy(quietFromMin = v)) } }, step = 30, format = ::clock)
                        RowDivider()
                        StepperRow("Until", c.alerts.quietToMin ?: 0, 0..1425, { v -> edit { it.copy(alerts = it.alerts.copy(quietToMin = v)) } }, step = 30, format = ::clock)
                    }
                }

                SettingsGroup(glass, "This camera") {
                    InfoRow("Name", c.label)
                    RowDivider()
                    InfoRow("Pairing code", hubState.pairingFingerprint)
                    RowDivider()
                    InfoRow("Wi-Fi address", hubState.lanAddress ?: "Not on Wi-Fi")
                    RowDivider()
                    InfoRow("Stream", if (hubState.encoding) "${hubState.streamKbps} kbps" else "Off")
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private fun clock(min: Int): String = "%02d:%02d".format(min / 60, min % 60)
