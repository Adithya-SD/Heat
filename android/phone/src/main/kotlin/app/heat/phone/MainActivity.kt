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
import androidx.compose.runtime.rememberCoroutineScope
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
import app.heat.core.room.RoomCode
import app.heat.phone.rooms.RoomViewer
import app.heat.phone.ui.LiveScreen
import app.heat.phone.ui.RoomViewerScreen
import app.heat.phone.ui.RoomsScreen
import app.heat.phone.ui.SettingsScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** A room code that arrived as a link (heat://link/CODE, or the page's #link=CODE); consumed once by the UI. */
    private val invite = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        app.heat.platform.PeerLauncher.announce(this, intent.getBooleanExtra(app.heat.platform.PeerLauncher.EXTRA_FROM_PEER, false))
        readInvite(intent)
        setContent { PhoneRoot(invite) }
    }

    override fun onStart() { super.onStart(); app.heat.platform.PeerLauncher.onScreen = true }
    override fun onStop() { app.heat.platform.PeerLauncher.onScreen = false; super.onStop() }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        readInvite(intent)
    }

    private fun readInvite(intent: android.content.Intent?) {
        val text = intent?.dataString ?: return
        RoomCode.parseInvite(text)?.let { invite.value = it }
    }
}

/** Camera and notifications to run, Bluetooth so the watch can connect over the fast direct route. */
private fun permissionsWanted(): Array<String> =
    arrayOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.BLUETOOTH_CONNECT)

@Composable
private fun PhoneRoot(invite: androidx.compose.runtime.MutableState<String?>) {
    val ctx = LocalContext.current
    fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    var cameraGranted by remember { mutableStateOf(has(Manifest.permission.CAMERA)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        cameraGranted = has(Manifest.permission.CAMERA)
    }
    LaunchedEffect(Unit) {
        if (!cameraGranted) launcher.launch(permissionsWanted())
    }
    LaunchedEffect(cameraGranted) {
        if (cameraGranted) CameraService.start(ctx)
    }

    val hub by HubHolder.hub.collectAsState()
    val glass = remember { GlassState() }
    var screen by rememberSaveable { mutableStateOf("live") }
    BackHandler(enabled = screen != "live") { screen = if (screen == "view") "rooms" else "live" }

    // A joined room. Not saveable on purpose: leaving the app leaves the room, so no decoder or link is left running.
    var viewer by remember { mutableStateOf<RoomViewer?>(null) }
    val scope = rememberCoroutineScope()
    fun join(code: String) {
        val h = hub ?: return
        scope.launch {
            viewer?.stop()
            val v = h.rooms.join(code)
            v.start()
            viewer = v
            screen = "view"
        }
    }
    fun leave() {
        viewer?.stop()
        viewer = null
        screen = "rooms"
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { viewer?.stop() } }
    LaunchedEffect(invite.value, hub) {
        val code = invite.value
        if (code != null && hub != null) {
            invite.value = null
            hub?.rooms?.remember(code)
            join(code)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val h = hub
        when {
            !cameraGranted -> Column(
                Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
            ) {
                HeatText("Heat needs the camera", size = 22, align = TextAlign.Center)
                HeatText("It watches the room and streams to your watch. Nothing leaves your network.", size = 15, color = Heat.TextDim, align = TextAlign.Center, modifier = Modifier.padding(vertical = 14.dp))
                GlassPillButton(glass, "Allow camera", { launcher.launch(permissionsWanted()) }, refract = false)
            }

            h == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { HeatText("Starting camera…", color = Heat.TextDim) }
            screen == "settings" -> SettingsScreen(h, glass) { screen = "live" }
            screen == "rooms" -> RoomsScreen(h, onBack = { screen = "live" }, onJoin = { join(it) })
            screen == "view" && viewer != null -> RoomViewerScreen(viewer!!, onClose = { leave() })
            else -> LiveScreen(h, onRooms = { screen = "rooms" }, onSettings = { screen = "settings" })
        }
    }
}
