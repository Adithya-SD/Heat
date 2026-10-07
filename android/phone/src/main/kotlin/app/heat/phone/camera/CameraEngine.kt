package app.heat.phone.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.media.MediaCodec
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import app.heat.core.model.AntiFlicker
import app.heat.core.model.Facing
import app.heat.core.model.NightMode
import java.util.concurrent.Executor
import kotlin.math.abs

/**
 * Camera2, driven directly.
 *
 * One capture session feeds up to three consumers at once:
 *  - a **preview** surface (the on-screen TextureView), only while the UI is visible;
 *  - an **encoder** surface, straight into the hardware H.264 encoder (zero copy,
 *    no CPU colour conversion: the earlier app converted every frame on the CPU);
 *  - a small **analysis** YUV reader for the motion engine, always on.
 *
 * Every public method just posts to the camera thread, and every callback is
 * validated against a generation counter, so a stale callback from a session
 * that has since been replaced can never touch the current one. The camera
 * being taken by another app, unplugged by the system or failing to configure
 * are all expected, reported, and retried, never crashes.
 */
@SuppressLint("MissingPermission")
class CameraEngine(context: Context, private val listener: Listener) {

    interface Listener {
        /** Luminance plane of an analysis frame. [y] is borrowed; copy what you keep. */
        fun onLuma(y: ByteArray, width: Int, height: Int, rowStride: Int, timestampMs: Long)
        fun onInfo(info: Info)
        fun onError(message: String?)
    }

    data class Info(
        val cameraId: String,
        val facing: Facing,
        val sensorOrientation: Int,
        val zoomMin: Float,
        val zoomMax: Float,
        val hasTorch: Boolean,
        val evMin: Int,
        val evMax: Int,
        val previewSize: Size,
        val analysisSize: Size,
        val encoderSizes: List<Size>
    )

    /** What the user wants from the sensor. Changing anything but [facing] only updates the repeating request. */
    data class Desired(
        val facing: Facing = Facing.REAR,
        val zoom: Float = 1f,
        val exposure: Int = 0,
        val torch: Boolean = false,
        val night: NightMode = NightMode.AUTO,
        val lockExposure: Boolean = false,
        val antiFlicker: AntiFlicker = AntiFlicker.AUTO,
        /** Lower the frame rate: nobody is watching and nothing needs 30 fps. */
        val lowPower: Boolean = false
    )

    private val manager = context.applicationContext.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("heat-camera", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    // All of the following is touched only on the camera thread.
    private var wanted = false
    private var desired = Desired()
    private var previewSurface: Surface? = null
    private var previewSize: Size? = null
    private var encoderSurface: Surface? = null
    private var device: CameraDevice? = null
    private var opening = false
    private var openedFacing: Facing? = null
    private var session: CameraCaptureSession? = null
    private var builtPreview: Surface? = null
    private var builtEncoder: Surface? = null
    private var building = false
    private var reader: ImageReader? = null
    private var chars: CameraCharacteristics? = null
    private var info: Info? = null
    private var generation = 0
    private var retryDelayMs = 500L
    private var droppedEncoder = false
    private var lastLumaMs = 0L
    private var yBuf = ByteArray(0)

    @Volatile var analysisIntervalMs = 66L

    /** Sizes the hardware encoder input can take, valid once the camera is open. */
    val encoderSizes: List<Size> get() = info?.encoderSizes.orEmpty()

    // ── public API (any thread) ──────────────────────────────────────

    fun start(initial: Desired) = handler.post {
        desired = initial
        wanted = true
        retryDelayMs = 500L
        reconcile()
    }

    fun stop() = handler.post {
        wanted = false
        closeAll()
    }

    fun update(next: Desired) = handler.post {
        val facingChanged = next.facing != desired.facing
        desired = next
        if (facingChanged && device != null) {
            closeAll()
        }
        reconcile()
    }

    fun setPreview(surface: Surface?, size: Size?) = handler.post {
        previewSurface = surface
        previewSize = size
        reconcile()
    }

    fun setEncoder(surface: Surface?) = handler.post {
        if (surface == null) droppedEncoder = false
        encoderSurface = surface
        reconcile()
    }

    fun release() {
        handler.post {
            wanted = false
            closeAll()
            thread.quitSafely()
        }
    }

    // ── state machine ────────────────────────────────────────────────

    private fun reconcile() {
        if (!wanted) return
        val dev = device
        if (dev == null) {
            if (!opening) openCamera()
            return
        }
        val wantPreview = previewSurface?.takeIf { it.isValid }
        val wantEncoder = if (droppedEncoder) null else encoderSurface?.takeIf { it.isValid }
        val needRebuild = session == null || wantPreview !== builtPreview || wantEncoder !== builtEncoder
        if (needRebuild) {
            if (!building) buildSession(dev, wantPreview, wantEncoder)
        } else {
            applyRequest()
        }
    }

    private fun pickCameraId(facing: Facing): String? {
        val want = if (facing == Facing.FRONT) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        var best: String? = null
        var bestSpan = -1f
        for (id in manager.cameraIdList) {
            val c = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            if (c.get(CameraCharacteristics.LENS_FACING) != want) continue
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
            if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE !in caps) continue
            // Prefer the logical camera that spans every lens (widest zoom range): the HAL then picks the lens for us.
            val r = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            val span = if (r != null) r.upper - r.lower else 0f
            if (span > bestSpan) {
                bestSpan = span
                best = id
            }
        }
        return best
    }

    private fun openCamera() {
        val id = pickCameraId(desired.facing)
        if (id == null) {
            listener.onError("No ${desired.facing.name.lowercase()} camera")
            scheduleRetry()
            return
        }
        opening = true
        val gen = ++generation
        try {
            chars = manager.getCameraCharacteristics(id)
            manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (gen != generation || !wanted) {
                        camera.close()
                        return
                    }
                    opening = false
                    device = camera
                    openedFacing = desired.facing
                    retryDelayMs = 500L
                    buildReader()
                    listener.onInfo(computeInfo(id).also { info = it })
                    listener.onError(null)
                    reconcile()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "camera disconnected")
                    if (gen == generation) {
                        closeAll()
                        listener.onError("Camera disconnected")
                        scheduleRetry()
                    } else camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.w(TAG, "camera error $error")
                    camera.close()
                    if (gen == generation) {
                        device = null
                        opening = false
                        session = null
                        listener.onError(
                            when (error) {
                                CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "Camera is in use by another app"
                                CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "Too many cameras open"
                                CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "Camera disabled by policy"
                                else -> "Camera error $error"
                            }
                        )
                        scheduleRetry()
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "openCamera failed", e)
            opening = false
            listener.onError("Cannot open camera: ${e.message}")
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (!wanted) return
        val gen = generation
        handler.postDelayed({
            if (wanted && gen == generation && device == null && !opening) openCamera()
        }, retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(5_000L)
    }

    private fun computeInfo(id: String): Info {
        val c = chars!!
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val zoom = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        val ev = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val isFront = c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val encSizes = map.getOutputSizes(MediaCodec::class.java)?.filter { it.width * 3 == it.height * 4 }?.sortedBy { it.width }.orEmpty()
        return Info(
            cameraId = id,
            facing = if (isFront) Facing.FRONT else Facing.REAR,
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            zoomMin = zoom?.lower ?: 1f,
            zoomMax = (zoom?.upper ?: 1f).coerceAtMost(10f),
            hasTorch = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            evMin = ev?.lower ?: 0,
            evMax = ev?.upper ?: 0,
            previewSize = pickPreviewSize(map.getOutputSizes(android.graphics.SurfaceTexture::class.java)),
            analysisSize = reader?.let { Size(it.width, it.height) } ?: Size(320, 240),
            encoderSizes = encSizes
        )
    }

    /** Largest 4:3 size up to 1280x960: sharp enough for a phone screen, cheap enough to keep the pipeline fast. */
    private fun pickPreviewSize(sizes: Array<Size>?): Size {
        val fourThree = sizes.orEmpty().filter { it.width * 3 == it.height * 4 && it.width <= 1280 }
        return fourThree.maxByOrNull { it.width } ?: Size(1280, 960)
    }

    private fun buildReader() {
        reader?.close()
        val map = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        // Smallest 4:3 YUV size that is at least 320 wide: the engine averages down to a coarse grid anyway.
        val size = map?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
            .filter { it.width * 3 == it.height * 4 && it.width >= 320 }
            .minByOrNull { it.width } ?: Size(640, 480)
        reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3).also { r ->
            r.setOnImageAvailableListener({ rd ->
                val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastLumaMs < analysisIntervalMs) return@setOnImageAvailableListener
                    lastLumaMs = now
                    val plane = img.planes[0]
                    val buf = plane.buffer
                    val n = buf.remaining()
                    if (yBuf.size < n) yBuf = ByteArray(n)
                    buf.get(yBuf, 0, n)
                    listener.onLuma(yBuf, img.width, img.height, plane.rowStride, now)
                } finally {
                    img.close()
                }
            }, handler)
        }
    }

    private fun buildSession(dev: CameraDevice, preview: Surface?, encoder: Surface?) {
        val rd = reader ?: return
        building = true
        val gen = ++generation
        runCatching { session?.close() }
        session = null

        val outputs = ArrayList<OutputConfiguration>(3)
        preview?.let { outputs += OutputConfiguration(it) }
        encoder?.let { outputs += OutputConfiguration(it) }
        outputs += OutputConfiguration(rd.surface)

        try {
            dev.createCaptureSession(
                SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            building = false
                            if (gen != generation || dev !== device) {
                                s.close()
                                return
                            }
                            session = s
                            builtPreview = preview
                            builtEncoder = encoder
                            applyRequest()
                            // Surfaces may have changed while we were building.
                            if (previewSurface?.takeIf { it.isValid } !== preview ||
                                (if (droppedEncoder) null else encoderSurface?.takeIf { it.isValid }) !== encoder
                            ) reconcile()
                        }

                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            building = false
                            s.close()
                            if (gen != generation) return
                            Log.w(TAG, "session configure failed (preview=${preview != null}, encoder=${encoder != null})")
                            if (encoder != null) {
                                // The stream is the optional part: lose it before losing the picture.
                                droppedEncoder = true
                                listener.onError("Video streaming is not supported with this camera mode")
                                reconcile()
                            } else if (preview != null) {
                                previewSurface = null
                                listener.onError("Preview unavailable")
                                reconcile()
                            } else {
                                listener.onError("Camera session failed")
                                closeAll()
                                scheduleRetry()
                            }
                        }

                        override fun onClosed(s: CameraCaptureSession) {
                            if (session === s) session = null
                        }
                    })
            )
        } catch (e: Exception) {
            building = false
            Log.w(TAG, "createCaptureSession threw", e)
            listener.onError("Camera session error: ${e.message}")
            closeAll()
            scheduleRetry()
        }
    }

    private fun applyRequest() {
        val dev = device ?: return
        val s = session ?: return
        val rd = reader ?: return
        val c = chars ?: return
        try {
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            builtPreview?.let { b.addTarget(it) }
            builtEncoder?.let { b.addTarget(it) }
            b.addTarget(rd.surface)

            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            val afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty()
            if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in afModes) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, pickFps(c, desired))
            b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, antibanding(c, desired.antiFlicker))

            val ev = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            if (ev != null) b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, desired.exposure.coerceIn(ev.lower, ev.upper))
            b.set(CaptureRequest.CONTROL_AE_LOCK, desired.lockExposure)
            b.set(CaptureRequest.CONTROL_AWB_LOCK, desired.lockExposure)

            val zr = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (zr != null) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, desired.zoom.coerceIn(zr.lower, zr.upper))

            if (desired.torch && c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            }
            s.setRepeatingRequest(b.build(), null, handler)
        } catch (e: Exception) {
            Log.w(TAG, "applyRequest failed", e)
        }
    }

    /** Day: as fast as the sensor allows. Night: the lowest range the sensor offers, so the shutter can stay open. */
    private fun pickFps(c: CameraCharacteristics, d: Desired): Range<Int> {
        val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
        if (ranges.isEmpty()) return Range(15, 30)
        fun best(filter: (Range<Int>) -> Boolean, score: (Range<Int>) -> Int): Range<Int>? =
            ranges.filter(filter).maxByOrNull(score)
        val picked = when {
            d.lowPower -> best({ it.upper in 10..15 }, { it.lower * 100 + it.upper })
            d.night == NightMode.NIGHT -> best({ it.upper >= 15 }, { -it.lower * 100 + it.upper })
            else -> best({ it.upper >= 30 }, { it.lower * 100 + it.upper })
        }
        return picked ?: ranges.maxByOrNull { it.upper * 100 - abs(it.lower - it.upper) } ?: Range(15, 30)
    }

    private fun antibanding(c: CameraCharacteristics, mode: AntiFlicker): Int {
        val avail = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)?.toList().orEmpty()
        val want = when (mode) {
            AntiFlicker.HZ50 -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ
            AntiFlicker.HZ60 -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
            AntiFlicker.OFF -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF
            AntiFlicker.AUTO -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
        }
        return if (want in avail) want else CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
    }

    private fun closeAll() {
        generation++
        building = false
        opening = false
        runCatching { session?.close() }
        session = null
        builtPreview = null
        builtEncoder = null
        runCatching { device?.close() }
        device = null
        runCatching { reader?.close() }
        reader = null
    }

    private companion object {
        const val TAG = "HeatCamera"
    }
}
