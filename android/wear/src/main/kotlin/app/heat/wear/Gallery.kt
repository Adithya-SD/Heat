package app.heat.wear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import app.heat.core.host.FrameFeed
import app.heat.platform.PreviewDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer

/**
 * Pictures and clips kept on the watch: stills taken with the capture button, clips recorded with the record
 * button, and the picture each motion alert brings.
 *
 * Nothing extra is asked of the camera. A still is the newest frame of the stream already arriving, decoded on its
 * own at the stream's full size (not a screenshot of the round screen). A clip is the stream's H.264 written
 * straight into an MP4 as it arrives, starting from the last keyframe, so it begins the instant the button is
 * pressed and costs no re-encoding at all.
 *
 * Files are named `<kind>_<epochMs>_<durationMs>_<camera>.<ext>`, with a small `.thumb.jpg` beside each, so the
 * list is rebuilt from the folder alone.
 */
class Gallery(context: Context) {
    enum class Kind { PHOTO, CLIP, ALERT }

    class Item(val file: File, val thumb: File, val kind: Kind, val atMs: Long, val durationMs: Long, val camera: String)

    private val dir = File(context.filesDir, "heat/gallery").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _items = MutableStateFlow<List<Item>>(emptyList())

    /** Newest first. */
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    private val _recording = MutableStateFlow<Long?>(null)

    /** When the clip being recorded started (elapsed realtime), or null. */
    val recording: StateFlow<Long?> = _recording.asStateFlow()

    private var recorder: ClipRecorder? = null
    private var recordingDecoder: PreviewDecoder? = null

    init {
        scope.launch { rescan() }
    }

    private fun rescan() {
        val list = dir.listFiles().orEmpty()
            .filter { !it.name.endsWith(".thumb.jpg") && (it.name.endsWith(".jpg") || it.name.endsWith(".mp4")) }
            .mapNotNull { parse(it) }
            .sortedByDescending { it.atMs }
        // Keep the folder bounded: the oldest go first.
        list.drop(MAX_ITEMS).forEach { delete(it, rescan = false) }
        _items.value = list.take(MAX_ITEMS)
    }

    private fun parse(f: File): Item? {
        val parts = f.nameWithoutExtension.split('_', limit = 4)
        if (parts.size < 4) return null
        val kind = runCatching { Kind.valueOf(parts[0]) }.getOrNull() ?: return null
        return Item(f, File(dir, f.nameWithoutExtension + ".thumb.jpg"), kind, parts[1].toLongOrNull() ?: return null,
            parts[2].toLongOrNull() ?: 0, parts[3].replace('-', ' '))
    }

    private fun base(kind: Kind, atMs: Long, durationMs: Long, camera: String) =
        "${kind.name}_${atMs}_${durationMs}_${camera.replace(Regex("[^A-Za-z0-9]+"), "-").take(24).ifEmpty { "Camera" }}"

    fun delete(item: Item, rescan: Boolean = true) {
        item.file.delete()
        item.thumb.delete()
        if (rescan) _items.value = _items.value.filter { it.file != item.file }
    }

    /** An alert's picture, exactly as the camera sent it. */
    fun saveAlert(jpeg: ByteArray, atMs: Long, camera: String) = scope.launch {
        val b = base(Kind.ALERT, atMs, 0, camera)
        File(dir, "$b.jpg").writeBytes(jpeg)
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.let { writeThumb(it, File(dir, "$b.thumb.jpg")) }
        rescan()
    }

    /** The newest frame from [decoder], at full size and the right way up. Calls back with the result on success. */
    fun takePhoto(decoder: PreviewDecoder, width: Int, height: Int, rotation: Int, camera: String, done: (Boolean) -> Unit) = scope.launch {
        val bmp = decodeNewest(decoder.group(), width, height, rotation)
        if (bmp == null) { done(false); return@launch }
        val b = base(Kind.PHOTO, System.currentTimeMillis(), 0, camera)
        File(dir, "$b.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        writeThumb(bmp, File(dir, "$b.thumb.jpg"))
        rescan()
        done(true)
    }

    /** Starts a clip from the last keyframe of [decoder]'s stream. False if there is nothing to record yet. */
    @Synchronized
    fun startClip(decoder: PreviewDecoder, width: Int, height: Int, rotation: Int, camera: String): Boolean {
        if (recorder != null) return true
        val group = decoder.group()
        if (group.isEmpty() || width <= 0 || height <= 0) return false
        val atMs = System.currentTimeMillis()
        val tmp = File(dir, "rec_${atMs}.part")
        val r = ClipRecorder(tmp, width, height, rotation)
        group.forEach { r.write(it.key, it.ptsUs, it.data, 0, it.data.size) }
        decoder.tap = { key, pts, data, off, len -> r.write(key, pts, data, off, len) }
        recorder = r
        recordingDecoder = decoder
        _recording.value = SystemClock.elapsedRealtime()
        // The clip's thumbnail is its first picture.
        scope.launch {
            decodeNewest(group.take(1), width, height, rotation)?.let { writeThumb(it, File(dir, "rec_${atMs}.thumb.part")) }
        }
        r.onFinished = { durationMs ->
            scope.launch {
                val b = base(Kind.CLIP, atMs, durationMs, camera)
                if (durationMs > 0 && tmp.renameTo(File(dir, "$b.mp4"))) File(dir, "rec_${atMs}.thumb.part").renameTo(File(dir, "$b.thumb.jpg"))
                else { tmp.delete(); File(dir, "rec_${atMs}.thumb.part").delete() }
                rescan()
            }
        }
        scope.launch {
            kotlinx.coroutines.delay(MAX_CLIP_MS)
            if (recorder === r) stopClip()
        }
        return true
    }

    @Synchronized
    fun stopClip() {
        val r = recorder ?: return
        recordingDecoder?.tap = null
        recorder = null
        recordingDecoder = null
        _recording.value = null
        r.finish()
    }

    private fun writeThumb(src: Bitmap, f: File) {
        val s = THUMB.toFloat() / maxOf(src.width, src.height)
        val t = if (s < 1f) Bitmap.createScaledBitmap(src, (src.width * s).toInt().coerceAtLeast(1), (src.height * s).toInt().coerceAtLeast(1), true) else src
        f.outputStream().use { t.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }

    companion object {
        private const val TAG = "HeatGallery"
        private const val MAX_ITEMS = 200
        private const val THUMB = 240
        private const val MAX_CLIP_MS = 5 * 60_000L

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
 * hands frames over only copies them. The first keyframe's SPS and PPS describe the track.
 */
private class ClipRecorder(private val file: File, private val width: Int, private val height: Int, private val rotation: Int) {
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
