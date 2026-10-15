package app.heat.platform

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import app.heat.core.host.FrameFeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer

/**
 * How pictures and clips are kept. Every value is a setting and every setting is remembered.
 *
 * Clipping is off until it is switched on; until then the capture button takes a picture. Nothing the user has not
 * collected stays forever: the folder holds at most [maxItems] things (the oldest uncollected go first), and
 * anything uncollected is deleted [expireDays] days after it was made. "Collected" means saved to the phone's own
 * gallery with Save, which also keeps it here.
 */
class CapturePrefs(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("heat-capture", Context.MODE_PRIVATE)
    private val _version = MutableStateFlow(0)

    /** Changes whenever any value does, so screens and the gallery re-read. */
    val version: StateFlow<Int> = _version.asStateFlow()

    var clipping: Boolean
        get() = p.getBoolean("clipping", false)
        set(v) = put { putBoolean("clipping", v) }

    /** Length of a clip in seconds. 0 means it runs until stopped. */
    var clipSec: Int
        get() = p.getInt("clipSec", 5)
        set(v) = put { putInt("clipSec", v.coerceIn(0, 120)) }

    var maxItems: Int
        get() = p.getInt("maxItems", 20)
        set(v) = put { putInt("maxItems", v.coerceIn(1, 500)) }

    /** Days an uncollected picture or clip is kept. 0 means never delete. */
    var expireDays: Int
        get() = p.getInt("expireDays", 2)
        set(v) = put { putInt("expireDays", v.coerceIn(0, 365)) }

    private fun put(block: android.content.SharedPreferences.Editor.() -> Unit) {
        p.edit().apply(block).apply()
        _version.value++
    }
}

/**
 * Pictures and clips kept on a device: stills taken with the capture button, clips recorded with the record button,
 * and the picture each motion alert brings.
 *
 * Nothing extra is asked of the camera. A watch still is the newest frame of the stream already arriving, decoded on
 * its own at the stream's full size; a phone still is its live preview. A clip is the stream's H.264 written straight
 * into an MP4 as it arrives (starting from the last keyframe where one is held), so it begins the instant the button
 * is pressed and costs no re-encoding.
 *
 * Files are named `<kind>_<epochMs>_<durationMs>_<camera>.<ext>`, with a small `.thumb.jpg` beside each, so the list
 * is rebuilt from the folder alone. A `.kept` file beside an item marks it collected.
 */
class Gallery(private val context: Context, folder: String = "heat/gallery") {
    enum class Kind { PHOTO, CLIP, ALERT }

    class Item(val file: File, val thumb: File, val kind: Kind, val atMs: Long, val durationMs: Long, val camera: String, val kept: Boolean)

    val prefs = CapturePrefs(context)
    private val dir = File(context.filesDir, folder).apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _items = MutableStateFlow<List<Item>>(emptyList())

    /** Newest first. */
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    private val _recording = MutableStateFlow<Long?>(null)

    /** When the clip being recorded started (elapsed realtime), or null. */
    val recording: StateFlow<Long?> = _recording.asStateFlow()

    private var recorder: ClipRecorder? = null
    private var onClipEnd: (() -> Unit)? = null

    init {
        scope.launch { rescan() }
        // Settings can shorten the list or the keeping time; the hourly pass catches items that expire while the app idles.
        scope.launch { prefs.version.collect { rescan() } }
        scope.launch { while (true) { delay(60 * 60_000L); rescan() } }
    }

    @Synchronized
    fun rescan() {
        val all = dir.listFiles().orEmpty()
            .filter { !it.name.endsWith(".thumb.jpg") && (it.name.endsWith(".jpg") || it.name.endsWith(".mp4")) }
            .mapNotNull { parse(it) }
            .sortedByDescending { it.atMs }
        val now = System.currentTimeMillis()
        val days = prefs.expireDays
        // Uncollected items past their time go; collected ones stay until the user removes them.
        all.filter { !it.kept && days > 0 && now - it.atMs > days * 86_400_000L }.forEach { delete(it, refresh = false) }
        var live = all.filter { it.file.exists() }
        // Over the limit: the oldest uncollected go first.
        val limit = prefs.maxItems
        var excess = live.size - limit
        if (excess > 0) {
            for (it in live.filter { !it.kept }.sortedBy { it.atMs }) {
                if (excess <= 0) break
                delete(it, refresh = false)
                excess--
            }
            live = live.filter { it.file.exists() }
        }
        Log.i(TAG, "scan: ${all.size} found, ${live.size} kept; limit=$limit expireDays=$days")
        _items.value = live
    }

    private fun parse(f: File): Item? {
        val parts = f.nameWithoutExtension.split('_', limit = 4)
        if (parts.size < 4) return null
        val kind = runCatching { Kind.valueOf(parts[0]) }.getOrNull() ?: return null
        return Item(f, File(dir, f.nameWithoutExtension + ".thumb.jpg"), kind, parts[1].toLongOrNull() ?: return null,
            parts[2].toLongOrNull() ?: 0, parts[3].replace('-', ' '), File(dir, f.nameWithoutExtension + ".kept").exists())
    }

    private fun base(kind: Kind, atMs: Long, durationMs: Long, camera: String) =
        "${kind.name}_${atMs}_${durationMs}_${camera.replace(Regex("[^A-Za-z0-9]+"), "-").take(24).ifEmpty { "Camera" }}"

    fun delete(item: Item, refresh: Boolean = true) {
        Log.i(TAG, "delete ${item.file.name} kept=${item.kept} age=${(System.currentTimeMillis() - item.atMs) / 1000}s refresh=$refresh", Throwable("who deletes"))
        item.file.delete()
        item.thumb.delete()
        File(dir, item.file.nameWithoutExtension + ".kept").delete()
        if (refresh) _items.value = _items.value.filter { it.file != item.file }
    }

    /** Saves a copy to the device's own gallery (Pictures/Heat or Movies/Heat) and keeps the item here. */
    fun save(item: Item, done: (Boolean) -> Unit) {
        scope.launch {
            val ok = runCatching {
                val video = item.kind == Kind.CLIP
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "Heat_${item.atMs}.${if (video) "mp4" else "jpg"}")
                    put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/Heat")
                }
                val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val uri = context.contentResolver.insert(collection, values) ?: error("no uri")
                context.contentResolver.openOutputStream(uri)?.use { out -> item.file.inputStream().use { it.copyTo(out) } }
                File(dir, item.file.nameWithoutExtension + ".kept").writeText("1")
            }.isSuccess
            rescan()
            done(ok)
        }
    }

    /** An alert's picture, exactly as the camera sent it. */
    fun saveAlert(jpeg: ByteArray, atMs: Long, camera: String) = scope.launch {
        val b = base(Kind.ALERT, atMs, 0, camera)
        File(dir, "$b.jpg").writeBytes(jpeg)
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.let { writeThumb(it, File(dir, "$b.thumb.jpg")) }
        rescan()
    }

    /** A picture already in hand (the phone's own preview). */
    fun savePhoto(bitmap: Bitmap, camera: String, done: (Boolean) -> Unit = {}) {
        scope.launch {
            val ok = runCatching {
                val b = base(Kind.PHOTO, System.currentTimeMillis(), 0, camera)
                File(dir, "$b.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                writeThumb(bitmap, File(dir, "$b.thumb.jpg"))
            }.isSuccess
            rescan()
            done(ok)
        }
    }

    /** The newest frame from [decoder], at full size and the right way up. Calls back with the result on success. */
    fun takePhoto(decoder: PreviewDecoder, width: Int, height: Int, rotation: Int, camera: String, done: (Boolean) -> Unit) = scope.launch {
        val bmp = decodeNewest(decoder.group(), width, height, rotation)
        if (bmp == null) { done(false); return@launch }
        savePhoto(bmp, camera, done)
    }

    /**
     * Starts a clip. [first] are frames already held (from the last keyframe), written first; further frames come from
     * the returned sink's `write`. Ends by itself after the clip length from [prefs] (unless that is 0), or on
     * [stopClip]. [onEnd] runs when it does, so the owner can stop feeding it. Null if a clip is already running.
     */
    @Synchronized
    fun startClip(width: Int, height: Int, rotation: Int, camera: String, first: List<FrameFeed.Frame>, thumbFrom: Bitmap?, onEnd: () -> Unit): ClipSink? {
        if (recorder != null) return null
        val atMs = System.currentTimeMillis()
        val tmp = File(dir, "rec_${atMs}.part")
        val r = ClipRecorder(tmp, width, height, rotation)
        first.forEach { r.write(it.key, it.ptsUs, it.data, 0, it.data.size) }
        recorder = r
        onClipEnd = onEnd
        _recording.value = SystemClock.elapsedRealtime()
        scope.launch {
            val bmp = thumbFrom ?: decodeNewest(first.take(1), width, height, rotation)
            bmp?.let { writeThumb(it, File(dir, "rec_${atMs}.thumb.part")) }
        }
        r.onFinished = { durationMs ->
            scope.launch {
                val b = base(Kind.CLIP, atMs, durationMs, camera)
                if (durationMs > 0 && tmp.renameTo(File(dir, "$b.mp4"))) File(dir, "rec_${atMs}.thumb.part").renameTo(File(dir, "$b.thumb.jpg"))
                else { tmp.delete(); File(dir, "rec_${atMs}.thumb.part").delete() }
                rescan()
            }
        }
        val len = prefs.clipSec
        if (len > 0) scope.launch { delay(len * 1000L); if (recorder === r) stopClip() }
        scope.launch { delay(MAX_CLIP_MS); if (recorder === r) stopClip() }
        return ClipSink { key, pts, data, off, n -> r.write(key, pts, data, off, n) }
    }

    fun interface ClipSink {
        fun write(keyframe: Boolean, ptsUs: Long, data: ByteArray, offset: Int, length: Int)
    }

    @Synchronized
    fun stopClip() {
        val r = recorder ?: return
        recorder = null
        _recording.value = null
        onClipEnd?.invoke()
        onClipEnd = null
        r.finish()
    }

    /** Convenience for a viewer that already has a decoder (the watch): replay its held group, then follow its tap. */
    fun startClip(decoder: PreviewDecoder, width: Int, height: Int, rotation: Int, camera: String): Boolean {
        val group = decoder.group()
        if (group.isEmpty() || width <= 0 || height <= 0) return false
        val sink = startClip(width, height, rotation, camera, group, null) { decoder.tap = null } ?: return true
        decoder.tap = { key, pts, data, off, n -> sink.write(key, pts, data, off, n) }
        return true
    }

    private fun writeThumb(src: Bitmap, f: File) {
        val s = THUMB.toFloat() / maxOf(src.width, src.height)
        val t = if (s < 1f) Bitmap.createScaledBitmap(src, (src.width * s).toInt().coerceAtLeast(1), (src.height * s).toInt().coerceAtLeast(1), true) else src
        f.outputStream().use { t.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }

    companion object {
        private const val TAG = "HeatGallery"
        private const val THUMB = 240
        private const val MAX_CLIP_MS = 10 * 60_000L

        /**
         * Decodes a group of pictures on its own codec (no surface) and returns its last frame as a bitmap, rotated
         * upright. Runs off the main thread; takes a few tens of milliseconds for a usual group.
         */
        fun decodeNewest(frames: List<FrameFeed.Frame>, width: Int, height: Int, rotation: Int): Bitmap? {
            if (frames.isEmpty() || width <= 0 || height <= 0) return null
            val codec = runCatching { MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrNull() ?: return null
            var out: Bitmap? = null
            try {
                val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                }
                codec.configure(fmt, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                val lastPts = frames.last().ptsUs
                var i = 0
                var inputDone = false
                val deadline = SystemClock.elapsedRealtime() + 4_000
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (!inputDone) {
                        val ix = codec.dequeueInputBuffer(5_000)
                        if (ix >= 0) {
                            if (i < frames.size) {
                                val f = frames[i++]
                                codec.getInputBuffer(ix)!!.apply { clear(); put(f.data) }
                                codec.queueInputBuffer(ix, 0, f.data.size, f.ptsUs, 0)
                            } else {
                                codec.queueInputBuffer(ix, 0, 0, lastPts + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            }
                        }
                    }
                    val ox = codec.dequeueOutputBuffer(info, 5_000)
                    if (ox >= 0) {
                        if (info.size > 0 && info.presentationTimeUs >= lastPts) {
                            codec.getOutputImage(ox)?.use { out = toBitmap(it) }
                        }
                        codec.releaseOutputBuffer(ox, false)
                        if (out != null || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "still decode failed", t)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            val b = out ?: return null
            if (rotation % 360 == 0) return b
            return Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        }

        /** YUV 4:2:0 (any layout) to ARGB, BT.601 limited range, the way camera encoders produce it. */
        private fun toBitmap(img: Image): Bitmap {
            val crop = img.cropRect
            val w = crop.width()
            val h = crop.height()
            val (yp, up, vp) = img.planes
            val yb = yp.buffer; val ub = up.buffer; val vb = vp.buffer
            val px = IntArray(w * h)
            for (row in 0 until h) {
                val yRow = (row + crop.top) * yp.rowStride
                val cRow = ((row + crop.top) / 2)
                for (col in 0 until w) {
                    val x = col + crop.left
                    val y = (yb.get(yRow + x * yp.pixelStride).toInt() and 0xff) - 16
                    val cx = x / 2
                    val u = (ub.get(cRow * up.rowStride + cx * up.pixelStride).toInt() and 0xff) - 128
                    val v = (vb.get(cRow * vp.rowStride + cx * vp.pixelStride).toInt() and 0xff) - 128
                    val yy = 1192 * y.coerceAtLeast(0)
                    val r = ((yy + 1634 * v) shr 10).coerceIn(0, 255)
                    val g = ((yy - 833 * v - 400 * u) shr 10).coerceIn(0, 255)
                    val b = ((yy + 2066 * u) shr 10).coerceIn(0, 255)
                    px[row * w + col] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }
    }
}

/**
 * Writes an H.264 stream into an MP4 as it arrives. Its own thread does the writing, so the network thread that
 * hands frames over only copies them. The first keyframe's SPS and PPS describe the track; frames before the first
 * keyframe are skipped (they could not be decoded anyway).
 */
internal class ClipRecorder(private val file: File, private val width: Int, private val height: Int, private val rotation: Int) {
    private val thread = HandlerThread("heat-clip").apply { start() }
    private val handler = Handler(thread.looper)
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var firstPts = -1L
    private var lastPts = -1L
    @Volatile var onFinished: ((Long) -> Unit)? = null

    fun write(key: Boolean, ptsUs: Long, data: ByteArray, offset: Int, length: Int) {
        val copy = data.copyOfRange(offset, offset + length)
        handler.post { writeNow(key, ptsUs, copy) }
    }

    private fun writeNow(key: Boolean, ptsUs: Long, data: ByteArray) {
        try {
            if (track < 0) {
                if (!key) return
                val (sps, pps) = parameterSets(data) ?: return
                val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(START + sps))
                    setByteBuffer("csd-1", ByteBuffer.wrap(START + pps))
                }
                val m = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                m.setOrientationHint(((rotation % 360) + 360) % 360)
                track = m.addTrack(fmt)
                m.start()
                muxer = m
                firstPts = ptsUs
            }
            if (ptsUs <= lastPts) return
            lastPts = ptsUs
            val info = MediaCodec.BufferInfo().apply {
                set(0, data.size, ptsUs - firstPts, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            }
            muxer?.writeSampleData(track, ByteBuffer.wrap(data), info)
        } catch (t: Throwable) {
            Log.w("HeatGallery", "clip write failed", t)
        }
    }

    fun finish() {
        handler.post {
            val duration = if (firstPts >= 0 && lastPts > firstPts) (lastPts - firstPts) / 1000 else 0
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            muxer = null
            onFinished?.invoke(duration)
            thread.quitSafely()
        }
    }

    private companion object {
        val START = byteArrayOf(0, 0, 0, 1)

        /** The SPS and PPS NAL units (without start codes) from an Annex-B access unit. */
        fun parameterSets(au: ByteArray): Pair<ByteArray, ByteArray>? {
            var sps: ByteArray? = null
            var pps: ByteArray? = null
            val starts = ArrayList<Int>()
            var i = 0
            while (i + 3 <= au.size) {
                if (au[i].toInt() == 0 && au[i + 1].toInt() == 0 && au[i + 2].toInt() == 1) { starts += i + 3; i += 3 } else i++
            }
            for ((n, s) in starts.withIndex()) {
                var e = if (n + 1 < starts.size) starts[n + 1] - 3 else au.size
                while (e > s && au[e - 1].toInt() == 0) e--          // a 4-byte start code leaves a zero behind
                if (s >= e) continue
                when (au[s].toInt() and 0x1f) {
                    7 -> sps = au.copyOfRange(s, e)
                    8 -> pps = au.copyOfRange(s, e)
                }
            }
            return if (sps != null && pps != null) sps!! to pps!! else null
        }
    }
}
