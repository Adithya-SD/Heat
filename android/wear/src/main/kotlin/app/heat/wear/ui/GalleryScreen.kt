package app.heat.wear.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import app.heat.glass.Heat
import app.heat.glass.HeatIcon
import app.heat.glass.HeatIconView
import app.heat.glass.HeatText
import app.heat.wear.Gallery
import app.heat.wear.Rotary
import app.heat.wear.WatchHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.pow

/** Everything kept on the watch, newest first, each as a full picture card with its details written inside it. */
@Composable
fun GalleryScreen(hub: WatchHub, back: () -> Unit) {
    val items by hub.gallery.items.collectAsState()
    var open by remember { mutableStateOf<Gallery.Item?>(null) }
    val o = open
    if (o != null) {
        BackHandler { open = null }
        Viewer(o, onDelete = { hub.gallery.delete(o); open = null })
        return
    }
    val list = rememberScalingLazyListState()
    ScalingLazyColumn(Modifier.fillMaxSize().background(Color.Black), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { HeatText("Gallery", size = 15, weight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp)) }
        if (items.isEmpty()) item {
            HeatText(
                "Nothing yet. Pictures, clips and motion alerts appear here.",
                size = 12, color = Heat.TextDim, align = TextAlign.Center, modifier = Modifier.fillMaxWidth(0.8f)
            )
        }
        for (it in items) item(key = it.file.name) { Card(it) { open = it } }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Card(item: Gallery.Item, onClick: () -> Unit) {
    val thumb by produceState<Bitmap?>(null, item.thumb.path) {
        value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(item.thumb.path) }.getOrNull() }
    }
    Box(
        Modifier.fillMaxWidth(0.86f).aspectRatio(4f / 3f).clip(RoundedCornerShape(18.dp)).background(Color(0x22FFFFFF)).clickable(onClick = onClick)
    ) {
        thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        // The details sit inside the picture, on a soft shade so they read over anything.
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                .padding(start = 10.dp, end = 10.dp, top = 14.dp, bottom = 7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeatIconView(iconOf(item), if (item.kind == Gallery.Kind.ALERT) Heat.Orange else Heat.Text, 12.dp)
                Spacer(Modifier.width(5.dp))
                HeatText(titleOf(item), size = 11, weight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.width(5.dp))
                HeatText(timeOf(item), size = 10, color = Heat.TextDim, maxLines = 1)
            }
        }
        if (item.kind == Gallery.Kind.CLIP) Box(
            Modifier.align(Alignment.Center).size(30.dp).clip(CircleShape).background(Color(0x66000000)), contentAlignment = Alignment.Center
        ) { HeatIconView(HeatIcon.Play, Color.White, 14.dp) }
    }
}

private fun iconOf(i: Gallery.Item) = when (i.kind) {
    Gallery.Kind.PHOTO -> HeatIcon.Capture
    Gallery.Kind.CLIP -> HeatIcon.Record
    Gallery.Kind.ALERT -> HeatIcon.Alert
}

private fun titleOf(i: Gallery.Item) = when (i.kind) {
    Gallery.Kind.PHOTO -> i.camera
    Gallery.Kind.CLIP -> "${i.camera} · ${formatDuration(i.durationMs)}"
    Gallery.Kind.ALERT -> "Motion · ${i.camera}"
}

private fun timeOf(i: Gallery.Item) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(i.atMs))

/**
 * One picture or clip, full screen. Pinch or turn the bezel to zoom, drag to look around, double-tap to jump between
 * fitted and close-up. A clip plays on a loop; a tap pauses it. Delete asks twice.
 */
@Composable
private fun Viewer(item: Gallery.Item, onDelete: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val shownScale by animateFloatAsState(scale, tween(140), label = "viewerZoom")

    DisposableEffect(Unit) {
        val mine: (Float) -> Unit = { d -> scale = (scale * 1.12f.pow(d)).coerceIn(1f, 6f); if (scale <= 1.001f) pan = Offset.Zero }
        Rotary.sink = mine
        onDispose { if (Rotary.sink === mine) Rotary.sink = null }
    }

    var paused by remember { mutableStateOf(false) }
    val zoomable = Modifier.fillMaxSize()
        .pointerInput(item.file) {
            detectTransformGestures { _, p, z, _ ->
                scale = (scale * z).coerceIn(1f, 6f)
                pan = if (scale <= 1.001f) Offset.Zero else pan + p
            }
        }
        .pointerInput(item.file) {
            detectTapGestures(
                onDoubleTap = { if (scale > 1.05f) { scale = 1f; pan = Offset.Zero } else scale = 2.6f },
                onTap = { paused = !paused }
            )
        }
        .graphicsLayer { scaleX = shownScale; scaleY = shownScale; translationX = pan.x; translationY = pan.y }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (item.kind == Gallery.Kind.CLIP) ClipPlayer(item, paused, zoomable)
        else {
            val full by produceState<Bitmap?>(null, item.file.path) {
                value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(item.file.path) }.getOrNull() }
            }
            full?.let { Image(it.asImageBitmap(), null, zoomable, contentScale = ContentScale.Fit) }
        }

        // Details and delete stay out of the way at the top and bottom.
        Box(Modifier.align(Alignment.TopCenter).padding(top = 16.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x88000000)).padding(horizontal = 8.dp, vertical = 3.dp)) {
            HeatText("${titleOf(item)} · ${timeOf(item)}", size = 10, color = Heat.Text, maxLines = 1)
        }
        var confirm by remember { mutableStateOf(false) }
        LaunchedEffect(confirm) { if (confirm) { delay(3_000); confirm = false } }
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).size(30.dp).clip(CircleShape)
                .background(if (confirm) Color(0xAAFF4D4D) else Color(0x88000000))
                .clickable { if (confirm) onDelete() else confirm = true },
            contentAlignment = Alignment.Center
        ) { HeatIconView(HeatIcon.Trash, Color.White, 14.dp) }
    }
}

@Composable
private fun ClipPlayer(item: Gallery.Item, paused: Boolean, modifier: Modifier) {
    val player = remember(item.file) { MediaPlayer() }
    var ready by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(0 to 0) }
    DisposableEffect(player) { onDispose { runCatching { player.release() } } }
    LaunchedEffect(paused, ready) { if (ready) runCatching { if (paused) player.pause() else player.start() } }
    Box(modifier, contentAlignment = Alignment.Center) {
        val (w, h) = size
        AndroidView(
            factory = { c ->
                TextureView(c).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, sw: Int, sh: Int) {
                            runCatching {
                                player.setSurface(Surface(st))
                                player.setDataSource(item.file.path)
                                player.isLooping = true
                                player.setOnVideoSizeChangedListener { _, vw, vh -> size = vw to vh }
                                player.setOnPreparedListener { ready = true; it.start() }
                                player.prepareAsync()
                            }
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, sw: Int, sh: Int) = Unit
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            },
            modifier = if (w > 0 && h > 0) Modifier.fillMaxWidth().aspectRatio(w.toFloat() / h) else Modifier.fillMaxSize()
        )
        if (paused) Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0x66000000)), contentAlignment = Alignment.Center) {
            HeatIconView(HeatIcon.Play, Color.White, 16.dp)
        }
    }
}
