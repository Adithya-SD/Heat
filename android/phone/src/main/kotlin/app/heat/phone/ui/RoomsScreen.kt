package app.heat.phone.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heat.core.room.RoomCode
import app.heat.glass.Heat
import app.heat.glass.HeatIcon
import app.heat.glass.HeatIconView
import app.heat.glass.HeatText
import app.heat.phone.rooms.RoomManager
import app.heat.phone.service.CameraHub
import kotlinx.coroutines.delay
import app.heat.rooms.RoomHost
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Heat Link: open this phone's camera to a room that anyone with the code can watch (from the Heat app, or from the
 * Heat page in any browser), or join somebody else's. Same matte chrome as the camera view; ember means "live".
 */
@Composable
fun RoomsScreen(hub: CameraHub, onBack: () -> Unit, onJoin: (String) -> Unit) {
    BackHandler(onBack = onBack)
    val rooms = hub.rooms
    val hosting by rooms.hosting.collectAsState()
    val recent by rooms.recent.collectAsState()
    val viewers by hub.host.viewerCount.collectAsState()
    val ctx = LocalContext.current

    Column(Modifier.fillMaxSize().background(Heat.Ink).windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).matte(CircleShape).tappable(onBack), contentAlignment = Alignment.Center) {
                HeatIconView(HeatIcon.Back, Heat.Text, 20.dp)
            }
            Spacer(Modifier.width(14.dp))
            HeatText("Heat Link", size = 24, weight = FontWeight.Bold)
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // ── share this camera ──
            Column(Modifier.fillMaxWidth().matte(RoundedCornerShape(24.dp)).padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HeatIconView(HeatIcon.Link, Heat.Ember1, 22.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        HeatText("Share this camera", size = 18, weight = FontWeight.SemiBold)
                        HeatText("Anyone with the code can watch and control it, from the Heat app or a browser.", size = 13, color = Heat.TextDim)
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(hosting.open) { if (it) rooms.open() else rooms.close() }
                }
                if (hosting.open && hosting.code.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    CodeCard(hosting.code, hosting.phase, viewers)
                    Spacer(Modifier.height(14.dp))
                    QrCard(RoomManager.inviteLink(hosting.code))
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionChip(HeatIcon.Copy, "Copy code", Modifier.weight(1f)) { copy(ctx, RoomCode.format(hosting.code), "Room code copied") }
                        ActionChip(HeatIcon.Share, "Share link", Modifier.weight(1f)) { share(ctx, hosting.code) }
                    }
                    Spacer(Modifier.height(10.dp))
                    var confirm by remember { mutableStateOf(false) }
                    LaunchedEffect(confirm) { if (confirm) { delay(4_000); confirm = false } }
                    HeatText("This code stays the same, even after the app restarts, until you change it. Hand it out once.", size = 12, color = Heat.TextFaint, modifier = Modifier.padding(bottom = 10.dp))
                    ActionChip(HeatIcon.Eye, "Watch this room instead", Modifier.fillMaxWidth()) { rooms.close(); rooms.remember(hosting.code); onJoin(hosting.code) }
                    Spacer(Modifier.height(10.dp))
                    ActionChip(
                        HeatIcon.Flip,
                        if (confirm) "Tap again: the old code stops working" else "New code…",
                        Modifier.fillMaxWidth(), dim = !confirm
                    ) { if (confirm) { confirm = false; rooms.newCode() } else confirm = true }
                } else if (!hosting.open) {
                    Spacer(Modifier.height(10.dp))
                    HeatText("Closed. Turn it on to get a code.", size = 13, color = Heat.TextFaint)
                }
            }

            // ── join a room ──
            var asking by remember { mutableStateOf<String?>(null) }
            JoinCard(recent, onJoin = { asking = it }, onForget = { rooms.forget(it) })
            asking?.let { code ->
                RolePrompt(code, onReceive = { asking = null; rooms.remember(code); onJoin(code) }, onHost = { asking = null; rooms.hostIn(code) }, onCancel = { asking = null })
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Switch(on: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        Modifier.size(width = 52.dp, height = 32.dp).matte(CircleShape, if (on) Heat.Ember0 else Heat.MatteHi).tappable({ onChange(!on) }).padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(Modifier.size(26.dp).background(Color.White, CircleShape))
    }
}

@Composable
private fun CodeCard(code: String, phase: RoomHost.Phase, viewers: Int) {
    Column(Modifier.fillMaxWidth().matte(RoundedCornerShape(18.dp), Heat.MatteHi).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        HeatText(RoomCode.format(code), size = 28, weight = FontWeight.Bold, color = Heat.EmberGlow, align = TextAlign.Center, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (dot, label) = when (phase) {
                RoomHost.Phase.READY -> Heat.Green to "Open"
                RoomHost.Phase.CONNECTING -> Heat.Amber to "Connecting…"
                RoomHost.Phase.OFFLINE -> Heat.Amber to "Offline — will retry"
                RoomHost.Phase.TAKEN -> Heat.Red to "Code in use elsewhere"
            }
            Box(Modifier.size(9.dp).background(dot, CircleShape))
            Spacer(Modifier.width(8.dp))
            HeatText(label, size = 13, color = Heat.TextDim)
            if (viewers > 0) {
                Spacer(Modifier.width(12.dp))
                HeatIconView(HeatIcon.Eye, Heat.Ember1, 15.dp)
                Spacer(Modifier.width(4.dp))
                HeatText("$viewers watching", size = 13, color = Heat.Ember1)
            }
        }
    }
}

@Composable
private fun QrCard(link: String) {
    val matrix = remember(link) {
        runCatching {
            QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0))
        }.getOrNull()
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(196.dp).background(Color.White, RoundedCornerShape(16.dp)).padding(14.dp)) {
            if (matrix != null) {
                Canvas(Modifier.fillMaxSize()) {
                    val n = matrix.width
                    val cell = size.minDimension / n
                    for (y in 0 until n) for (x in 0 until n) if (matrix.get(x, y)) {
                        drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.6f, cell + 0.6f))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        HeatText("Scan with a camera, or open the link", size = 12, color = Heat.TextFaint, align = TextAlign.Center)
    }
}

@Composable
private fun ActionChip(icon: HeatIcon, label: String, modifier: Modifier = Modifier, dim: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier.matte(RoundedCornerShape(16.dp), Heat.MatteHi).tappable(onClick).padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
    ) {
        HeatIconView(icon, if (dim) Heat.TextDim else Heat.Ember1, 18.dp)
        Spacer(Modifier.width(8.dp))
        HeatText(label, size = 14, weight = FontWeight.Medium, color = if (dim) Heat.TextDim else Heat.Text, maxLines = 2)
    }
}

@Composable
private fun JoinCard(recent: List<String>, onJoin: (String) -> Unit, onForget: (String) -> Unit) {
    val ctx = LocalContext.current
    var field by remember { mutableStateOf(TextFieldValue("")) }
    val complete = RoomCode.isComplete(field.text)
    fun set(raw: String) {
        val f = RoomCode.format(raw)
        field = TextFieldValue(f, TextRange(f.length))
    }
    Column(Modifier.fillMaxWidth().matte(RoundedCornerShape(24.dp)).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeatIconView(HeatIcon.Eye, Heat.Ember1, 22.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                HeatText("Join a room", size = 18, weight = FontWeight.SemiBold)
                HeatText("Enter a room code. You will be asked whether to host or receive.", size = 13, color = Heat.TextDim)
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().matte(RoundedCornerShape(16.dp), Heat.MatteHi).padding(horizontal = 16.dp, vertical = 16.dp), contentAlignment = Alignment.Center) {
            if (field.text.isEmpty()) HeatText("ABCD-EFGH", size = 22, color = Heat.TextFaint, align = TextAlign.Center)
            BasicTextField(
                field, { set(it.text) },
                textStyle = TextStyle(color = Heat.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center, letterSpacing = 1.sp),
                cursorBrush = SolidColor(Heat.Ember1),
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (complete) onJoin(RoomCode.normalize(field.text)) }),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(12.dp))
        ActionChip(HeatIcon.Copy, "Paste", Modifier.fillMaxWidth(), dim = true) {
            val clip = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            RoomCode.parseInvite(clip)?.let { set(it) }
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.fillMaxWidth().matte(RoundedCornerShape(16.dp), if (complete) Heat.Ember0 else Heat.MatteHi)
                .tappable({ if (complete) onJoin(RoomCode.normalize(field.text)) }).padding(vertical = 13.dp),
            contentAlignment = Alignment.Center
        ) { HeatText("Join", size = 15, weight = FontWeight.SemiBold, color = if (complete) Color.White else Heat.TextFaint) }
        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            HeatText("RECENT ROOMS", size = 11, weight = FontWeight.SemiBold, color = Heat.TextFaint)
            Spacer(Modifier.height(6.dp))
            for (code in recent) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).matte(RoundedCornerShape(14.dp), Heat.MatteHi).tappable({ onJoin(code) }).padding(start = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HeatText(RoomCode.format(code), size = 16, weight = FontWeight.Medium, modifier = Modifier.weight(1f).padding(vertical = 13.dp), maxLines = 1)
                    Box(Modifier.size(44.dp).tappable({ onForget(code) }), contentAlignment = Alignment.Center) { HeatIconView(HeatIcon.Close, Heat.TextFaint, 16.dp) }
                }
            }
        }
    }
}

private fun copy(ctx: Context, text: String, toast: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Heat room code", text))
    android.widget.Toast.makeText(ctx, toast, android.widget.Toast.LENGTH_SHORT).show()
}

private fun share(ctx: Context, code: String) {
    val link = RoomManager.inviteLink(code)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Watch my Heat camera")
        putExtra(Intent.EXTRA_TEXT, "Watch my Heat camera: $link\nOr enter the code ${RoomCode.format(code)} in the Heat app.")
    }
    ctx.startActivity(Intent.createChooser(send, "Share this camera").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}


/** A room code was entered or opened: is this phone going to receive from the room, or host in it? Asked every time; either can be changed later. */
@Composable
fun RolePrompt(code: String, onReceive: () -> Unit, onHost: () -> Unit, onCancel: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Column(Modifier.fillMaxWidth().matte(RoundedCornerShape(26.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HeatText("Join as", size = 20, weight = FontWeight.Bold)
            HeatText("Room ${RoomCode.format(code)}", size = 14, color = Heat.TextDim)
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().matte(RoundedCornerShape(16.dp), Heat.Ember0).tappable(onReceive).padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    HeatText("Receiver", size = 16, weight = FontWeight.SemiBold, color = Color.White)
                    HeatText("Watch its cameras, get their alerts", size = 12, color = Color(0xCCFFFFFF))
                }
            }
            Box(Modifier.fillMaxWidth().matte(RoundedCornerShape(16.dp), Heat.MatteHi).tappable(onHost).padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    HeatText("Host", size = 16, weight = FontWeight.SemiBold)
                    HeatText("Add this phone\'s camera to the room", size = 12, color = Heat.TextDim)
                }
            }
            Box(Modifier.fillMaxWidth().tappable(onCancel).padding(vertical = 10.dp), contentAlignment = Alignment.Center) { HeatText("Cancel", size = 14, color = Heat.TextDim) }
        }
    }
}
