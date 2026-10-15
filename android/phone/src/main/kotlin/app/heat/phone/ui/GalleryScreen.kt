package app.heat.phone.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.heat.glass.Heat
import app.heat.glass.HeatIcon
import app.heat.glass.HeatIconView
import app.heat.glass.HeatText
import app.heat.phone.service.CameraHub
import app.heat.platform.Gallery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * What the phone has captured: pictures from the live view, clips, and the pictures that came with motion alerts.
 * A grid of cards, each with its details written inside it; tapping one opens a full viewer you can pinch to zoom
 * (or play, for a clip). Nothing here lasts forever: uncollected items are deleted after the time in settings, so
 * every card says how long it has left, and Save copies it to the phone's own gallery and keeps it.
 */
@Composable
fun GalleryScreen(hub: CameraHub, onBack: () -> Unit) {
    val items by hub.gallery.items.collectAsState()
    val prefVersion by hub.gallery.prefs.version.collectAsState()
    var open by remember { mutableStateOf<Gallery.Item?>(null) }
    val o = open
    BackHandler { if (open != null) open = null else onBack() }

    if (o != null) {
        val current = items.firstOrNull { it.file == o.file } ?: o
        Viewer(hub, current, onClose = { open = null })
        return
    }
    Column(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).matte(CircleShape).tappable(onBack), contentAlignment = Alignment.Center) { HeatIconView(HeatIcon.Back, Heat.Text, 20.dp) }
            Spacer(Modifier.width(12.dp))
            Column {
                HeatText("Gallery", size = 22, weight = FontWeight.Bold)
                val p = hub.gallery.prefs
                @Suppress("UNUSED_VARIABLE") val v = prefVersion
                HeatText(
                    "${items.size} of ${p.maxItems}" + if (p.expireDays > 0) " · uncollected items go after ${p.expireDays} day${if (p.expireDays == 1) "" else "s"}" else "",
                    size = 12, color = Heat.TextDim
                )
            }
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                HeatText("Nothing yet. Pictures, clips and motion alerts appear here.", size = 14, color = Heat.TextDim, modifier = Modifier.padding(40.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
            }
        } else LazyVerticalGrid(
            GridCells.Fixed(2), Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(items, key = { it.file.name }) { Card(hub, it) { open = it } }
        }
    }
}

@Composable
private fun Card(hub: CameraHub, item: Gallery.Item, onClick: () -> Unit) {
    val thumb by produceState<Bitmap?>(null, item.thumb.path) {
        value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(item.thumb.path) }.getOrNull() }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(18.dp)).background(Heat.Matte).tappable(onClick)) {
        thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                .padding(start = 10.dp, end = 10.dp, top = 18.dp, bottom = 8.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HeatIconView(iconOf(item), if (item.kind == Gallery.Kind.ALERT) Heat.Orange else Heat.Text, 13.dp)
                    Spacer(Modifier.width(5.dp))
                    HeatText(titleOf(item), size = 12, weight = FontWeight.SemiBold, maxLines = 1)
                }
                HeatText(timeOf(item) + "  ·  " + leftOf(hub, item), size = 11, color = Heat.TextDim, maxLines = 1)
            }
        }
        if (item.kind == Gallery.Kind.CLIP) Box(
            Modifier.align(Alignment.Center).size(38.dp).clip(CircleShape).background(Color(0x66000000)), contentAlignment = Alignment.Center
        ) { HeatIconView(HeatIcon.Play, Color.White, 16.dp) }
        if (item.kept) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp).clip(CircleShape).background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
            HeatIconView(HeatIcon.Check, Heat.Green, 12.dp)
        }
    }
}

private fun iconOf(i: Gallery.Item) = when (i.kind) {
    Gallery.Kind.PHOTO -> HeatIcon.Capture
    Gallery.Kind.CLIP -> HeatIcon.Record
    Gallery.Kind.ALERT -> HeatIcon.Alert
}

private fun titleOf(i: Gallery.Item) = when (i.kind) {
    Gallery.Kind.PHOTO -> i.camera
    Gallery.Kind.CLIP -> "${i.camera} · ${clock(i.durationMs)}"
    Gallery.Kind.ALERT -> "Motion · ${i.camera}"
}

private fun clock(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

private fun timeOf(i: Gallery.Item): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(i.atMs))

private fun leftOf(hub: CameraHub, i: Gallery.Item): String {
    if (i.kept) return "saved"
    val days = hub.gallery.prefs.expireDays
    if (days <= 0) return "kept"
    val ms = i.atMs + days * 86_400_000L - System.currentTimeMillis()
    val h = (ms / 3_600_000L).coerceAtLeast(0)
    return if (h > 24) "${(h + 23) / 24}d left" else "${h}h left"
}

/** One picture or clip, full screen: pinch to zoom, drag to look around, double-tap to jump in and out; clips play on a loop. */
@Composable
private fun Viewer(hub: CameraHub, item: Gallery.Item, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val zoomable = Modifier.fillMaxSize()
        .pointerInput(item.file) {
            detectTransformGestures { _, p, z, _ ->
                scale = (scale * z).coerceIn(1f, 8f)
                pan = if (scale <= 1.001f) Offset.Zero else pan + p
            }
        }
        .pointerInput(item.file) {
            detectTapGestures(onDoubleTap = { if (scale > 1.05f) { scale = 1f; pan = Offset.Zero } else scale = 2.8f })
        }
        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (item.kind == Gallery.Kind.CLIP) {
            AndroidView(
                factory = { c -> VideoView(c).apply { setVideoPath(item.file.path); setOnPreparedListener { it.isLooping = true; start() } } },
                modifier = Modifier.align(Alignment.Center).then(zoomable)
            )
        } else {
            val full by produceState<Bitmap?>(null, item.file.path) {
                value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(item.file.path) }.getOrNull() }
            }
            full?.let { Image(it.asImageBitmap(), null, zoomable, contentScale = ContentScale.Fit) }
        }

        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).matte(CircleShape).tappable(onClose), contentAlignment = Alignment.Center) { HeatIconView(HeatIcon.Back, Heat.Text, 20.dp) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.matte(RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp)) {
                    HeatText(titleOf(item), size = 14, weight = FontWeight.SemiBold, maxLines = 1)
                    HeatText(timeOf(item) + "  ·  " + leftOf(hub, item), size = 12, color = Heat.TextDim, maxLines = 1)
                }
            }
            Spacer(Modifier.weight(1f))
            var confirm by remember { mutableStateOf(false) }
            var saved by remember(item.file) { mutableStateOf(false) }
            LaunchedEffect(confirm) { if (confirm) { delay(3_000); confirm = false } }
            Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally)) {
                Box(
                    Modifier.size(54.dp).matte(CircleShape).tappable({
                        if (!item.kept) hub.gallery.save(item) { ok ->
                            saved = ok
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                android.widget.Toast.makeText(ctx, if (ok) "Saved to your gallery" else "Could not save", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }),
                    contentAlignment = Alignment.Center
                ) { HeatIconView(if (item.kept || saved) HeatIcon.Check else HeatIcon.Save, if (item.kept || saved) Heat.Green else Heat.Text, 24.dp) }
                Box(
                    Modifier.size(54.dp).matte(CircleShape, if (confirm) Color(0xFF6B1E1E) else Heat.Matte).tappable({
                        if (confirm) { hub.gallery.delete(item); onClose() } else confirm = true
                    }),
                    contentAlignment = Alignment.Center
                ) { HeatIconView(HeatIcon.Trash, if (confirm) Heat.Red else Heat.Text, 24.dp) }
            }
        }
    }
}
