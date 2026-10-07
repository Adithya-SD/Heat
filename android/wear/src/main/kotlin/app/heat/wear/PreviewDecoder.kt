package app.heat.wear

import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * H.264 decoder writing straight to a Surface, driven from the session's reader thread.
 *
 * Built for one thing: showing the newest frame as soon as it exists. Input is
 * copied into the codec at once (the sink buffer is borrowed), every ready
 * output is released for rendering immediately, and when the picture is not
 * being displayed frames are simply dropped instead of queued, because on a
 * wrist a stale frame is worse than a missing one.
 */
class PreviewDecoder {
    private var codec: MediaCodec? = null
    private val info = MediaCodec.BufferInfo()
    @Volatile private var running = false
    @Volatile var width = 0
        private set
    @Volatile var height = 0
        private set
    @Volatile var framesRendered = 0L
        private set
    private var gotKeyframe = false

    /** Called (on the feeding thread) when the decoder had to be (re)started and wants a fresh keyframe. */
    var onNeedKeyframe: (() -> Unit)? = null

    @Synchronized
    fun start(surface: Surface, w: Int, h: Int) {
        stop()
        try {
            val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, w * h)
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_OPERATING_RATE, 60)
            }
            codec = MediaCodec.createDecoderByType(MIME).apply {
                configure(format, surface, null, 0)
                start()
            }
            width = w
            height = h
            gotKeyframe = false
            running = true
            Log.i(TAG, "decoder up ${w}x$h")
            onNeedKeyframe?.invoke()
        } catch (t: Throwable) {
            Log.w(TAG, "decoder start failed", t)
            running = false
        }
    }

    /** Feed one access unit. [keyframe] gates decoding: nothing is fed until the first keyframe after a start. */
    @Synchronized
    fun submit(keyframe: Boolean, ptsUs: Long, data: ByteArray, offset: Int, length: Int) {
        val c = codec ?: return
        if (!running || length <= 0) return
        if (!gotKeyframe) {
            if (!keyframe) return
            gotKeyframe = true
        }
        try {
            val i = c.dequeueInputBuffer(0)
            if (i >= 0) {
                val buf: ByteBuffer? = c.getInputBuffer(i)
                if (buf != null && buf.capacity() >= length) {
                    buf.clear()
                    buf.put(data, offset, length)
                    c.queueInputBuffer(i, 0, length, ptsUs, 0)
                } else {
                    c.queueInputBuffer(i, 0, 0, ptsUs, 0)
                }
            }
            // Release every ready frame so what lands on screen is always the newest.
            while (true) {
                val o = c.dequeueOutputBuffer(info, 0)
                when {
                    o >= 0 -> {
                        c.releaseOutputBuffer(o, true)
                        framesRendered++
                    }
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    else -> break
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "decode error: ${t.message}")
            gotKeyframe = false
            onNeedKeyframe?.invoke()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        codec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        codec = null
    }

    private companion object {
        const val TAG = "HeatDecoder"
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    }
}

/**
 * The matrix that turns a square surface (which stretches the decoded frame to a square) into a correctly
 * proportioned, correctly rotated picture on a round face: un-stretch to the frame's own aspect, rotate upright
 * by [rotation], then scale so the picture spans the glass the way the earlier app's default did (the long
 * edge a little past the diameter for landscape, the height for portrait).
 *
 * Also usable in reverse (invert it) to turn a touch into sensor-space fractions: the stretched-buffer
 * coordinates this maps *from* are exactly those.
 */
fun previewMatrix(w: Float, h: Float, videoW: Int, videoH: Int, rotation: Int): Matrix {
    val m = Matrix()
    if (w <= 0f || h <= 0f || videoW <= 0 || videoH <= 0) return m
    val cx = w / 2f
    val cy = h / 2f
    val a = videoW.toFloat() / videoH
    val sx = if (a >= 1f) 1f else a
    val sy = if (a >= 1f) 1f / a else 1f
    val quarter = (rotation / 90) % 2 != 0
    val rw = if (quarter) sy else sx
    val rh = if (quarter) sx else sy
    val shown = rw / rh
    val punch = 1.06f
    val targetW = if (shown >= 1f) punch else shown * punch
    val k = targetW / rw
    m.setScale(sx, sy, cx, cy)
    m.postRotate(rotation.toFloat(), cx, cy)
    m.postScale(k, k, cx, cy)
    return m
}
