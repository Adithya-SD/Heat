package app.lenslab

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.heat.core.camera.ZoomSmoother
import app.heat.glass.Heat
import app.heat.glass.HeatIcon
import app.heat.glass.HeatIconView
import app.heat.glass.HeatText
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.exp

/** One camera id the phone answers for, and what it says about itself. */
data class Cam(
    val id: String, val front: Boolean, val listed: Boolean, val f35: Float?,
    val physical: List<String>, val zoomMin: Float, val zoomMax: Float, val sensor: Int
) {
    val label: String get() = buildString {
        append(id)
        if (front) append(" front")
        if (physical.isNotEmpty()) append(" · ").append(physical.joinToString("+"))
        f35?.let { append(" · %.0fmm".format(it)) }
        if (!listed) append(" · hidden")
    }
}

/**
 * Lens Lab: a camera for trying out lenses. It lists every camera id the phone will describe (the public ones and
 * the usual hidden numbers), opens any of them, zooms with the same two-spring smoothing Heat uses, and shows which
 * physical lens is serving each frame. Nothing here is Heat; it exists to find out what a phone can do.
 */
class LabActivity : ComponentActivity() {
    private val thread = HandlerThread("lab-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private lateinit var manager: CameraManager

    private var cams by mutableStateOf<List<Cam>>(emptyList())
    private var current by mutableStateOf<Cam?>(null)
    private var status by mutableStateOf("Starting")
    private var activePhysical by mutableStateOf<String?>(null)
    private var zoomShown by mutableStateOf(1f)
    private var openMs by mutableStateOf(0L)

    // Camera thread only.
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var texture: SurfaceTexture? = null
    private var textureView: TextureView? = null
    private var smoother: ZoomSmoother? = null
    private var lastFrameNs = 0L
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = getSystemService(CameraManager::class.java)
        cams = discover()
        setContent { Screen() }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) open(current ?: cams.firstOrNull())
    }

    override fun onResume() {
        super.onResume()
        if (texture != null) open(current ?: cams.firstOrNull())
    }

    override fun onPause() {
        handler.post { close() }
        super.onPause()
    }

    override fun onDestroy() {
        thread.quitSafely()
        super.onDestroy()
    }

    // ── cameras ──────────────────────────────────────────────────────

    private fun discover(): List<Cam> {
        val listed = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        val ids = listed + (0..99).map { it.toString() }.filter { it !in listed }
        val out = ArrayList<Cam>()
        for (id in ids) {
            val c = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
            val logical = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps
            val zr = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            out += Cam(
                id = id,
                front = c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT,
                listed = id in listed,
                f35 = focal35(c),
                physical = if (logical) c.physicalCameraIds.toList() else emptyList(),
                zoomMin = zr?.lower ?: 1f, zoomMax = zr?.upper ?: 1f,
                sensor = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            )
        }
        Log.i(TAG, "cameras: " + out.joinToString { it.label })
        return out
    }

    private fun focal35(c: CameraCharacteristics): Float? {
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val s = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val d = hypot(s.width, s.height)
        return if (d > 0f) f * 43.27f / d else null
    }

    @SuppressLint("MissingPermission")
    private fun open(cam: Cam?) {
        cam ?: return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        current = cam
        status = "Opening ${cam.id}"
        val started = SystemClock.elapsedRealtime()
        handler.post {
            close()
            val gen = ++generation
            smoother = ZoomSmoother(1f.coerceIn(cam.zoomMin, cam.zoomMax), cam.zoomMin, cam.zoomMax)
            try {
                manager.openCamera(cam.id, { handler.post(it) }, object : CameraDevice.StateCallback() {
                    override fun onOpened(d: CameraDevice) {
                        if (gen != generation) { d.close(); return }
                        device = d
                        startSession(d, cam, started, gen)
                    }
                    override fun onDisconnected(d: CameraDevice) { d.close(); status = "Disconnected" }
                    override fun onError(d: CameraDevice, error: Int) { d.close(); status = "Camera ${cam.id} error $error" }
                })
            } catch (t: Throwable) {
                status = "Cannot open ${cam.id}: ${t.message}"
            }
        }
    }

    private fun startSession(d: CameraDevice, cam: Cam, started: Long, gen: Int) {
        val st = texture ?: return
        val size = previewSize(cam.id)
        st.setDefaultBufferSize(size.width, size.height)
        val surface = Surface(st)
        d.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(surface)), { handler.post(it) },
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (gen != generation) { s.close(); return }
                    session = s
                    lastFrameNs = 0
                    repeat(surface)
                    openMs = SystemClock.elapsedRealtime() - started
                    status = "${cam.id} open in $openMs ms"
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { status = "Session failed on ${cam.id}" }
            }))
        previewTarget = surface
    }

    private var previewTarget: Surface? = null

    private fun repeat(surface: Surface? = previewTarget) {
        val d = device ?: return
        val s = session ?: return
        surface ?: return
        runCatching {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(surface)
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, smoother?.zoom ?: 1f)
            s.setRepeatingRequest(b.build(), frames, handler)
        }
    }

    /** The zoom moves one step per captured frame, at the camera's own pace, exactly as in Heat. */
    private val frames = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(s: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
            val sm = smoother ?: return
            val dt = if (lastFrameNs == 0L) 1f / 30f else ((timestamp - lastFrameNs) / 1e9f).coerceIn(0.001f, 0.25f)
            lastFrameNs = timestamp
            if (sm.settled) return
            sm.step(dt)
            zoomShown = sm.zoom
            repeat()
        }

        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val p = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
            if (p != activePhysical) activePhysical = p
        }
    }

    private fun setZoom(z: Float) {
        handler.post {
            val sm = smoother ?: return@post
            sm.setTarget(z)
            if (sm.settled.not()) repeat()
        }
    }

    private fun previewSize(id: String): Size {
        val map = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        return map?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
            .filter { it.width * 3 == it.height * 4 && it.width <= 1920 }.maxByOrNull { it.width } ?: Size(1440, 1080)
    }

    private fun close() {
        generation++
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
    }

    private fun capture() {
        val bmp: Bitmap = textureView?.takeIf { it.isAvailable }?.bitmap ?: return
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "LensLab_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Lens Lab")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        Toast.makeText(this, "Saved to Pictures/Lens Lab", Toast.LENGTH_SHORT).show()
    }

    // ── screen ───────────────────────────────────────────────────────

    @Composable
    private fun Screen() {
        val cam = current
        Column(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
            HeatText("Lens Lab", size = 18, weight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in cams) Chip(c.label, c.id == cam?.id) { open(c) }
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().aspectRatio(3f / 4f).pointerInput(cam?.id) {
                    detectTransformGestures { _, _, z, _ -> if (z != 1f) setZoom(((smoother?.target ?: 1f) * z)) }
                }
            ) {
                AndroidView(factory = { ctx ->
                    TextureView(ctx).apply {
                        textureView = this
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { texture = st; open(current ?: cams.firstOrNull()) }
                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { texture = null; handler.post { close() }; return true }
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                        }
                    }
                }, modifier = Modifier.fillMaxSize())
                Column(Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x99000000)).padding(8.dp)) {
                    HeatText(status, size = 12)
                    if (cam != null) HeatText("zoom %.2f× of %.2f–%.1f".format(zoomShown, cam.zoomMin, cam.zoomMax), size = 12, color = Heat.TextDim)
                    activePhysical?.let { HeatText("serving lens: $it", size = 12, color = Heat.Orange) }
                }
            }
            Spacer(Modifier.weight(1f))
            if (cam != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    val stops = listOf(cam.zoomMin, 1f, 2f, 3f, 5f, 10f).filter { it in cam.zoomMin..cam.zoomMax }.distinct()
                    for (z in stops) {
                        Box(
                            Modifier.padding(horizontal = 5.dp).size(44.dp).clip(CircleShape).background(Heat.Matte)
                                .border(1.dp, if (kotlin.math.abs(ln(zoomShown / z)) < 0.05f) Heat.Orange else Color(0x33FFFFFF), CircleShape)
                                .clickable { setZoom(z) },
                            contentAlignment = Alignment.Center
                        ) { HeatText(if (z < 1f) "%.1f".format(z) else "%.0f".format(z), size = 13, weight = FontWeight.SemiBold) }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(70.dp).clip(CircleShape).border(3.dp, Color.White, CircleShape).padding(6.dp).clip(CircleShape)
                        .background(Color.White).clickable { capture() }
                )
            }
            Spacer(Modifier.height(18.dp))
        }
    }

    @Composable
    private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
        Row(
            Modifier.clip(CircleShape).background(if (selected) Color(0xFF3A2414) else Heat.Matte)
                .border(1.dp, if (selected) Heat.Orange else Color(0x22FFFFFF), CircleShape)
                .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HeatIconView(HeatIcon.Camera, if (selected) Heat.Orange else Heat.TextDim, 14.dp)
            Spacer(Modifier.width(6.dp))
            HeatText(text, size = 12, color = if (selected) Heat.Text else Heat.TextDim, maxLines = 1)
        }
    }

    private companion object {
        const val TAG = "LensLab"
    }
}
