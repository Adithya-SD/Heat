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
import app.heat.core.camera.CameraProfile
import app.heat.core.camera.LensEntry
import app.heat.core.camera.LensGraph
import app.heat.core.camera.LensStrategy
import app.heat.core.camera.ZoomSmoother
import app.heat.core.model.AntiFlicker
import app.heat.core.model.Facing
import app.heat.core.model.NightMode
import app.heat.core.model.WhiteBalance
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Camera2, driven directly.
 *
 * One capture session feeds up to three consumers at once:
 *  - a **preview** surface (the on-screen TextureView), only while the UI is visible;
 *  - an **encoder** surface, straight into the hardware H.264 encoder (zero copy,
 *    no CPU colour conversion: the earlier app converted every frame on the CPU);
 *  - a small **analysis** YUV reader for the motion engine, only while something needs it.
 *
 * ## Zoom
 * Every requested zoom goes through a [ZoomSmoother], and the engine advances it once per captured frame from
 * the frame's own sensor timestamp, so each frame is exposed at exactly the zoom the smoother says it should be.
 * The picture moves like it is on rails whether the target came from a pinch, a slider, a lens button or the
 * watch's bezel. Several lenses are arranged on one zoom axis by [LensGraph]: if the phone exposes one logical
 * camera that spans them, its firmware changes lens; if it exposes the lenses as separate cameras, the engine
 * changes device at the boundary (with hysteresis so hovering on the boundary cannot flap).
 *
 * Every public method just posts to the camera thread, and every callback is validated against a generation
 * counter, so a stale callback from a session that has since been replaced can never touch the current one.
 * Repeated updates are coalesced: only the newest request is ever applied. The camera being taken by another
 * app, unplugged by the system or failing to configure are all expected, reported, and retried, never crashes.
 */
@SuppressLint("MissingPermission")
class CameraEngine(context: Context, private val listener: Listener) {

    interface Listener {
        /** Luminance plane of an analysis frame. [y] is borrowed; copy what you keep. */
        fun onLuma(y: ByteArray, width: Int, height: Int, rowStride: Int, timestampMs: Long)
        fun onInfo(info: Info)
        fun onError(message: String?)

        /** The zoom actually being shown (smoothed), a few times a second while it moves and once when it settles. */
        fun onZoom(zoom: Float, lens: LensEntry?) = Unit

        /** True while the camera is being switched to another lens device and no new frames are flowing. */
        fun onLensSwitch(switching: Boolean) = Unit
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
        val encoderSizes: List<Size>,
        val manualFocus: Boolean,
        val manualExposure: Boolean,
        val lenses: List<LensEntry>,
        val strategy: LensStrategy,
        /** One readable paragraph of what was found and what was decided, for the diagnostics page. */
        val diagnostics: String
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
        /** -1 = autofocus, otherwise 0 (far) .. 100 (nearest). */
        val focus: Int = -1,
        val whiteBalance: WhiteBalance = WhiteBalance.AUTO,
        val iso: Int = 0,
        val shutterDenom: Int = 0,
        val blendLenses: Boolean = true,
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
    private var openId: String? = null
    private var wantedId: String? = null
    private val badIds = HashSet<String>()
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

    // Lens planning and zoom.
    private var profiles: List<CameraProfile>? = null
    private var graph: LensGraph? = null
    private var graphFacing: Facing? = null
    private var graphBlend = true
    private var smoother: ZoomSmoother? = null
    private var lastFrameNs = 0L
    private var lastZoomReportMs = 0L
    private var reportedSettled = true
    private var switching = false
    private var switchStartedMs = 0L
    private var openAfterClose = false
    private var holdZoomUntilMs = 0L

    // Updates from any thread land here; only the newest is ever applied.
    private val pendingDesired = AtomicReference<Desired?>(null)

    /** Milliseconds between analysis frames the motion engine gets. */
    @Volatile var analysisIntervalMs = 100L

    /** When false the analysis reader just discards frames: no copy, no motion work. */
    @Volatile var analysisEnabled = true

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

    fun update(next: Desired) {
        if (pendingDesired.getAndSet(next) != null) return    // a newer one replaces it; the queued task will see it
        handler.post {
            val n = pendingDesired.getAndSet(null) ?: return@post
            applyDesired(n)
        }
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

    private fun applyDesired(next: Desired) {
        val facingChanged = next.facing != desired.facing
        val blendChanged = next.blendLenses != desired.blendLenses
        desired = next
        Log.i(TAG, "desired: zoom ${next.zoom} facing ${next.facing} (open=$openId smoother=${smoother?.zoom}->${smoother?.target})")
        if (facingChanged) {
            smoother = null
            wantedId = null
            if (device != null) closeAll()
        }
        if (blendChanged) graph = null
        ensureGraph()
        smoother?.setTarget(next.zoom)
        reconcile()
    }

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
            if (!building) Log.i(TAG, "session build: none=${session == null} preview=${wantPreview !== builtPreview} encoder=${wantEncoder !== builtEncoder}")
            if (!building) buildSession(dev, wantPreview, wantEncoder)
        } else {
            applyRequest()
        }
    }

    // ── lens planning ────────────────────────────────────────────────

    private fun profile(id: String, c: CameraCharacteristics): CameraProfile {
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
        val facing = if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) Facing.FRONT else Facing.REAR
        val physical = if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps) c.physicalCameraIds.toList() else emptyList()
        val zr = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        val physicalF35 = HashMap<String, Float>()
        for (pid in physical) {
            val pc = runCatching { manager.getCameraCharacteristics(pid) }.getOrNull() ?: continue
            focal35(pc)?.let { physicalF35[pid] = it }
        }
        return CameraProfile(
            id = id,
            facing = facing,
            usable = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE in caps,
            physicalIds = physical,
            zoomMin = zr?.lower ?: 1f,
            zoomMax = zr?.upper ?: 1f,
            focal35 = focal35(c),
            physicalFocal35 = physicalF35
        )
    }

    /** 35mm-equivalent focal length: the lens's focal length scaled by how much smaller the sensor is than a 36x24 frame. */
    private fun focal35(c: CameraCharacteristics): Float? {
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val s = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val diag = hypot(s.width, s.height)
        return if (diag > 0f) f * 43.27f / diag else null
    }

    private fun ensureGraph() {
        if (graph != null && graphFacing == desired.facing && graphBlend == desired.blendLenses) {
            // A plan without a smoother cannot happen: whoever built one needs the other.
            val g = graph
            if (smoother == null && g != null) smoother = ZoomSmoother(desired.zoom, g.zoomMin, g.zoomMax)
            return
        }
        val all = profiles ?: runCatching {
            // Phones (Samsung especially) keep some lenses off the public list: the tele, and logical cameras that front
            // every rear lens at once. They still answer by id, so ask for the usual hidden ones too. One that cannot be
            // opened later is dropped from the plan like any other bad lens.
            val listed = manager.cameraIdList.toList()
            val hidden = HIDDEN_IDS.filter { it !in listed }.filter { id ->
                runCatching { manager.getCameraCharacteristics(id) }.isSuccess
            }
            if (hidden.isNotEmpty()) Log.i(TAG, "hidden cameras answer: $hidden")
            (listed + hidden).mapNotNull { id ->
                runCatching { profile(id, manager.getCameraCharacteristics(id)) }.getOrNull()
            }
        }.getOrDefault(emptyList()).also { profiles = it }
        graph = LensGraph.build(all.filter { it.id !in badIds }, desired.facing, desired.blendLenses)
        graphFacing = desired.facing
        graphBlend = desired.blendLenses
        graph?.let { g ->
            // A new plan can have a new zoom range: carry the current position over into a smoother that fits it.
            val from = smoother?.zoom ?: desired.zoom
            smoother = ZoomSmoother(from, g.zoomMin, g.zoomMax).also { it.setTarget(desired.zoom) }
            Log.i(TAG, "lens plan: ${g.strategy} device=${g.deviceId} ${g.note}")
        }
    }

    /** Which camera device should be serving [z]. Only ever differs from the plan's device when lenses are separate cameras. */
    private fun deviceFor(z: Float): String? {
        val g = graph ?: return null
        if (g.strategy != LensStrategy.SWITCH) return g.deviceId
        val ladder = (g.switchTargets + (g.deviceId to 1f)).filterKeys { it !in badIds }.toList().sortedBy { it.second }
        val pick = ladder.lastOrNull { it.second <= z * 1.0001f } ?: ladder.first()
        val cur = openId
        if (cur != null && cur != pick.first) {
            val curRatio = ladder.firstOrNull { it.first == cur }?.second
            // Hysteresis: leaving a lens downward needs a clear step past its edge, so hovering on a boundary cannot flap.
            if (curRatio != null && pick.second < curRatio && z > curRatio * 0.98f) return cur
        }
        return pick.first
    }

    /**
     * Which device should serve the zoom while it moves. A lens change freezes the picture for the better part of a
     * second, so it is never done in the middle of a motion: going wider than the open lens can show, the change
     * happens first (at the current framing) and the motion follows; going tighter, the open lens carries the whole
     * motion as long as it can crop that far, and the sharper lens takes over only once the picture has settled.
     */
    private fun deviceForPath(sm: ZoomSmoother): String? {
        val lo = minOf(sm.zoom, sm.target)
        val hi = maxOf(sm.zoom, sm.target)
        val cur = openId
        if (!sm.settled && cur != null) {
            val ratio = lensRatioOfOpenDevice()
            val reach = ratio * (chars?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper ?: 1f)
            if (ratio <= lo * 1.0001f && hi <= reach) return cur
        }
        return deviceFor(lo)
    }

    private fun lensRatioOfOpenDevice(): Float {
        val g = graph ?: return 1f
        val id = openId ?: return 1f
        return if (g.strategy == LensStrategy.SWITCH && id != g.deviceId) g.switchTargets[id] ?: 1f else 1f
    }

    // ── opening ──────────────────────────────────────────────────────

    private fun openCamera() {
        ensureGraph()
        val g = graph
        val id = wantedId?.takeIf { it !in badIds } ?: deviceFor(smoother?.zoom ?: desired.zoom) ?: g?.deviceId
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
                    openId = id
                    if (switching) Log.i(TAG, "lens switch: device open at +${SystemClock.elapsedRealtime() - switchStartedMs} ms")
                    openedFacing = desired.facing
                    retryDelayMs = 500L
                    buildReader()
                    listener.onInfo(computeInfo(id).also { info = it })
                    listener.onError(null)
                    lastFrameNs = 0
                    reconcile()
                }

                override fun onClosed(camera: CameraDevice) {
                    if (switching) Log.i(TAG, "lens switch: old device closed at +${SystemClock.elapsedRealtime() - switchStartedMs} ms")
                    if (openAfterClose) {
                        openAfterClose = false
                        handler.post { if (wanted && device == null && !opening) openCamera() }
                    }
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
                    Log.w(TAG, "camera $id error $error")
                    camera.close()
                    if (gen == generation) {
                        device = null
                        openId = null
                        opening = false
                        session = null
                        // A lens camera that cannot be opened is dropped from the plan; the main lens carries on.
                        if (g != null && id != g.deviceId && error != CameraDevice.StateCallback.ERROR_CAMERA_IN_USE) {
                            badIds += id
                            wantedId = null
                            graph = null
                        }
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
            Log.w(TAG, "openCamera($id) failed", e)
            opening = false
            if (g != null && id != g.deviceId) {
                badIds += id
                wantedId = null
                graph = null
            }
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
        val g = graph
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val ev = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val isFront = c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val encSizes = map.getOutputSizes(MediaCodec::class.java)?.filter { it.width * 3 == it.height * 4 }?.sortedBy { it.width }.orEmpty()
        val afOff = CameraCharacteristics.CONTROL_AF_MODE_OFF in (c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty())
        val manualFocus = afOff && (c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) > 0f
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
        val aeOff = CameraCharacteristics.CONTROL_AE_MODE_OFF in (c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.toList().orEmpty())
        val manualExposure = aeOff && CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
        val zr = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        return Info(
            cameraId = id,
            facing = if (isFront) Facing.FRONT else Facing.REAR,
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            zoomMin = g?.zoomMin ?: zr?.lower ?: 1f,
            zoomMax = g?.zoomMax ?: (zr?.upper ?: 1f).coerceAtMost(10f),
            hasTorch = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            evMin = ev?.lower ?: 0,
            evMax = ev?.upper ?: 0,
            previewSize = pickPreviewSize(map.getOutputSizes(android.graphics.SurfaceTexture::class.java)),
            analysisSize = reader?.let { Size(it.width, it.height) } ?: Size(320, 240),
            encoderSizes = encSizes,
            manualFocus = manualFocus,
            manualExposure = manualExposure,
            lenses = g?.lenses.orEmpty(),
            strategy = g?.strategy ?: LensStrategy.LOGICAL,
            diagnostics = diagnosticsText(id, g, zr)
        )
    }

    private fun diagnosticsText(id: String, g: LensGraph?, zr: Range<Float>?): String = buildString {
        append("Open camera: ").append(id)
        append(" · zoom range ").append(zr?.let { "${it.lower}-${it.upper}x" } ?: "n/a").append('\n')
        append("Plan: ").append(g?.strategy ?: "none").append(" · ").append(g?.note ?: "no plan").append('\n')
        val all = profiles.orEmpty()
        append("Cameras seen: ")
        append(all.joinToString { p ->
            val logical = if (p.physicalIds.isNotEmpty()) "[logical ${p.physicalIds.joinToString("+")}]" else ""
            "${p.id}${if (p.facing == Facing.FRONT) "F" else ""}${if (!p.usable) "x" else ""}$logical"
        })
        if (badIds.isNotEmpty()) append('\n').append("Could not open: ").append(badIds.joinToString())
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
                    if (!analysisEnabled) return@setOnImageAvailableListener
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
                            if (switching) Log.i(TAG, "lens switch: session ready at +${SystemClock.elapsedRealtime() - switchStartedMs} ms")
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

    // ── requests and zoom ────────────────────────────────────────────

    /** One call per captured frame: this is what makes the zoom move at the camera's own pace. */
    private val frameCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(s: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
            if (s !== session) return
            if (switching) {
                switching = false
                // The new lens shows the old framing for a moment (the screen fades it in over the frozen frame), and
                // only then does the zoom move, so the motion is seen on the new lens instead of hidden in the fade.
                holdZoomUntilMs = SystemClock.elapsedRealtime() + POST_SWITCH_HOLD_MS
                Log.i(TAG, "lens switch complete in ${SystemClock.elapsedRealtime() - switchStartedMs} ms")
                listener.onLensSwitch(false)
            }
            onFrame(timestamp)
        }
    }

    private fun onFrame(timestampNs: Long) {
        val sm = smoother ?: return
        val dt = if (lastFrameNs == 0L) 1f / 30f else ((timestampNs - lastFrameNs) / 1e9f).coerceIn(0.001f, 0.25f)
        lastFrameNs = timestampNs
        if (graph?.strategy == LensStrategy.SWITCH) {
            // Heading wider than this lens can show (1x -> 0.5x): change lens *first*, at the framing we are at now
            // (the wide lens crops to it exactly), and only then start moving. The camera change takes a few hundred
            // milliseconds; spending them before the motion means the motion itself is never interrupted. Heading
            // tighter, the wide lens zooms in on itself up to the next lens's own view, where the hand-over is seamless.
            val want = deviceForPath(sm)
            if (want != null && want != openId) {
                switchTo(want)
                return
            }
        }
        if (sm.settled) {
            if (!reportedSettled) {
                reportedSettled = true
                reportZoom(sm.zoom, force = true)
            }
            return
        }
        if (SystemClock.elapsedRealtime() < holdZoomUntilMs) { lastFrameNs = timestampNs; return }
        if (reportedSettled) Log.i(TAG, "zoom moving: ${sm.zoom} -> ${sm.target} on device $openId")
        reportedSettled = false
        sm.step(dt)

        // Separate lens cameras: cross the boundary by changing device. The smoother keeps its place meanwhile.
        val want = deviceForPath(sm)
        if (want != null && want != openId && graph?.strategy == LensStrategy.SWITCH) {
            switchTo(want)
            return
        }
        applyRequest()
        reportZoom(sm.zoom, force = false)
    }

    private fun reportZoom(zoom: Float, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastZoomReportMs < 90) return
        lastZoomReportMs = now
        listener.onZoom(zoom, graph?.lensFor(zoom))
    }

    private fun switchTo(id: String) {
        Log.i(TAG, "lens switch: ${openId} -> $id at ${smoother?.zoom}")
        wantedId = id
        switching = true
        switchStartedMs = SystemClock.elapsedRealtime()
        listener.onLensSwitch(true)
        openAfterClose = true
        closeAll()
        // The next camera opens the moment the old one reports itself closed (some HALs refuse a second rear camera
        // until the first has let go). If that callback never comes, a timer opens it anyway.
        retryDelayMs = 450L
        scheduleRetry()
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
            val minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            if (desired.focus >= 0 && minFocus > 0f && CameraCharacteristics.CONTROL_AF_MODE_OFF in afModes) {
                // Fixed focus: 0 is infinity, 100 is the nearest the lens can reach (focus distance is in diopters).
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, minFocus * desired.focus / 100f)
            } else if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in afModes) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }

            val fps = pickFps(c, desired)
            val manual = desired.iso > 0 || desired.shutterDenom > 0
            val canManual = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in
                (c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()) &&
                CameraCharacteristics.CONTROL_AE_MODE_OFF in (c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.toList().orEmpty())
            if (manual && canManual) {
                applyManualExposure(b, c)
            } else {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
                val ev = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                if (ev != null) b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, desired.exposure.coerceIn(ev.lower, ev.upper))
                b.set(CaptureRequest.CONTROL_AE_LOCK, desired.lockExposure)
            }
            b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, antibanding(c, desired.antiFlicker))

            val awbModes = c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList().orEmpty()
            val awb = awbMode(desired.whiteBalance)
            if (awb in awbModes) b.set(CaptureRequest.CONTROL_AWB_MODE, awb)
            b.set(CaptureRequest.CONTROL_AWB_LOCK, desired.lockExposure)

            val zr = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (zr != null) {
                val z = (smoother?.zoom ?: desired.zoom) / lensRatioOfOpenDevice()
                b.set(CaptureRequest.CONTROL_ZOOM_RATIO, z.coerceIn(zr.lower, zr.upper))
            }

            if (desired.torch && c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            }
            s.setRepeatingRequest(b.build(), frameCallback, handler)
        } catch (e: Exception) {
            Log.w(TAG, "applyRequest failed", e)
        }
    }

    /** ISO and shutter fixed by hand. Whichever is left on auto gets a sensible stand-in, since the sensor cannot be half manual. */
    private fun applyManualExposure(b: CaptureRequest.Builder, c: CameraCharacteristics) {
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        val isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val timeRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val iso = if (desired.iso > 0) desired.iso else 400
        val denom = if (desired.shutterDenom > 0) desired.shutterDenom else 60
        val ns = (1_000_000_000L / denom)
        if (isoRange != null) b.set(CaptureRequest.SENSOR_SENSITIVITY, iso.coerceIn(isoRange.lower, isoRange.upper))
        val exposure = if (timeRange != null) ns.coerceIn(timeRange.lower, timeRange.upper) else ns
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
        // A frame cannot be shorter than its exposure: a slow shutter lowers the frame rate rather than being refused.
        b.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(exposure, 33_333_333L))
    }

    private fun awbMode(w: WhiteBalance): Int = when (w) {
        WhiteBalance.AUTO -> CaptureRequest.CONTROL_AWB_MODE_AUTO
        WhiteBalance.DAYLIGHT -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
        WhiteBalance.CLOUDY -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        WhiteBalance.TUNGSTEN -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
        WhiteBalance.FLUORESCENT -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
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
        openId = null
        runCatching { reader?.close() }
        reader = null
    }

    private companion object {
        const val TAG = "HeatCamera"
        /** After a lens change, how long the new lens holds the old framing before the zoom moves. Keep equal to the UI's. */
        const val POST_SWITCH_HOLD_MS = 180L
        val HIDDEN_IDS = (2..9).map { it.toString() } + (20..29).map { it.toString() } + (50..59).map { it.toString() }
    }
}
