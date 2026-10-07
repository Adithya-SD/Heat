package app.heat.phone

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.heat.glass.GlassState
import app.heat.glass.Heat
import app.heat.phone.service.CameraService
import app.heat.phone.service.HubHolder
import app.heat.phone.ui.GlassPillButton
import app.heat.glass.HeatText
import app.heat.phone.ui.LiveScreen
import app.heat.phone.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PhoneRoot() }
    }
}

@Composable
private fun PhoneRoot() {
    val ctx = LocalContext.current
    fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    var cameraGranted by remember { mutableStateOf(has(Manifest.permission.CAMERA)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        cameraGranted = has(Manifest.permission.CAMERA)
    }
    LaunchedEffect(Unit) {
        if (!cameraGranted) launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS))
    }
    LaunchedEffect(cameraGranted) {
        if (cameraGranted) CameraService.start(ctx)
    }

    val hub by HubHolder.hub.collectAsState()
    val glass = remember { GlassState() }
    var screen by rememberSaveable { mutableStateOf("live") }
    BackHandler(enabled = screen != "live") { screen = "live" }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val h = hub
        when {
            !cameraGranted -> Column(
                Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
            ) {
                HeatText("Heat needs the camera", size = 22, align = TextAlign.Center)
                HeatText("It watches the room and streams to your watch. Nothing leaves your network.", size = 15, color = Heat.TextDim, align = TextAlign.Center, modifier = Modifier.padding(vertical = 14.dp))
                GlassPillButton(glass, "Allow camera", { launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS)) }, refract = false)
            }

            h == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { HeatText("Starting camera…", color = Heat.TextDim) }
            screen == "settings" -> SettingsScreen(h, glass) { screen = "live" }
            else -> LiveScreen(h, glass) { screen = "settings" }
        }
    }
}
