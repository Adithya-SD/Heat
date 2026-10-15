package app.heat.platform

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import app.heat.core.host.FrameFeed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * H.264 decoder writing straight to a Surface, built to put the newest picture on screen as soon as it exists.
 *
 * Everything that touches the codec happens on one private thread, so the network thread that delivers frames
 * never waits on MediaCodec (a slow configure used to stall the whole session, control messages included).
 * Frames arrive through a [FrameFeed], which is what makes the rest possible:
 *
 *  - It keeps the current group of pictures, so a decoder that starts late (the screen was still opening when
 *    the first keyframe arrived) replays it and shows a picture at once instead of waiting a round trip.
 *  - If the codec falls behind, the backlog is dropped and decoding resumes at the next keyframe: no growing
 *    lag, never a smeared picture.
 *  - Frames are never dropped silently in the middle of a group (the old decoder skipped a frame when no input
 *    buffer was free, which corrupts everything after it).
 *
 * The codec runs in asynchronous mode: input buffers are handed to us when they are free, and every decoded
 * frame is released for display immediately.
 */
class PreviewDecoder(private val onNeedKeyframe: () -> Unit) {
    private val feed = FrameFeed()
    private val thread = HandlerThread("heat-decode", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val pumping = AtomicBoolean(false)

    // Touched only on [handler]'s thread.
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var width = 0
    private var height = 0

    // The size of the frames the feed holds. Outlives the codec, so a late start still replays what arrived early.
    private var feedWidth = 0
    private var feedHeight = 0
    private val freeInputs = ArrayDeque<Int>()
    private var restarts = 0
    private var lastRestartMs = 0L

    private val _rendered = MutableStateFlow(0L)

    /** Frames put on screen since the decoder last started. Goes above 0 when the first picture appears. */
    val rendered: StateFlow<Long> = _rendered.asStateFlow()

    // ── network thread ───────────────────────────────────────────────

    /** Offer one access unit. Never blocks. [data] is borrowed and copied. */
    fun submit(keyframe: Boolean, ptsUs: Long, data: ByteArray, offset: Int, length: Int) {
        when (feed.offer(keyframe, ptsUs, data, offset, length)) {
            FrameFeed.Offer.ACCEPTED -> if (pumping.compareAndSet(false, true)) handler.post { pumping.set(false); pump() }
            FrameFeed.Offer.NEED_KEYFRAME -> onNeedKeyframe()
            FrameFeed.Offer.DROPPED -> Unit
        }
    }

    // ── any thread ───────────────────────────────────────────────────

    /**
     * Show frames on [surface]. Safe to call again with the same arguments (nothing happens) or with a new
     * size (the codec is rebuilt and the old size's frames are forgotten).
     */
    fun attach(surface: Surface, width: Int, height: Int) {
        handler.post { start(surface, width, height) }
    }

    /** The surface is going away (screen off, view destroyed). The group of pictures is kept for the next [attach]. */
    fun detach(afterStop: (() -> Unit)? = null) {
        handler.post {
            stop(keepFeed = true)
            afterStop?.invoke()
        }
    }

    /** Forget every frame: the camera changed. */
    fun reset() {
        handler.post { feed.clear() }
    }

    fun release() {
        handler.post {
            stop(keepFeed = false)
            thread.quitSafely()
        }
    }

    // ── decoder thread ───────────────────────────────────────────────

    private fun start(newSurface: Surface, w: Int, h: Int) {
        if (codec != null && surface === newSurface && w == width && h == height) return
        stop(keepFeed = true)
        if (!newSurface.isValid || w <= 0 || h <= 0) return
        if (feedWidth != 0 && (feedWidth != w || feedHeight != h)) {
            // The camera changed its picture size: what we hold is the wrong shape. Get a fresh group.
            feed.clear()
            onNeedKeyframe()
        }
        feedWidth = w
        feedHeight = h
        try {
            val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(256 * 1024, w * h))
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_OPERATING_RATE, 60)
            }
            val c = MediaCodec.createDecoderByType(MIME)
            c.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    if (codec !== this@PreviewDecoder.codec) return
                    freeInputs.addLast(index)
                    pump()
                }

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    try {
                        codec.releaseOutputBuffer(index, info.size > 0)
                        if (info.size > 0) _rendered.value = _rendered.value + 1
                    } catch (_: IllegalStateException) {
                        // The codec was stopped under us; the restart path owns what happens next.
                    }
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.w(TAG, "codec error: ${e.diagnosticInfo}")
                    handler.post { recover() }
                }
            }, handler)
            c.configure(format, newSurface, null, 0)
            c.start()
            codec = c
            surface = newSurface
            width = w
            height = h
            _rendered.value = 0
            feed.start()
            Log.i(TAG, "decoder up ${w}x$h")
        } catch (t: Throwable) {
            Log.w(TAG, "decoder start failed", t)
            stop(keepFeed = true)
        }
    }

    private fun stop(keepFeed: Boolean) {
        codec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        codec = null
        surface = null
        freeInputs.clear()
        width = 0
        height = 0
        feed.stop()
        if (!keepFeed) {
            feed.clear()
            feedWidth = 0
            feedHeight = 0
        }
    }

    /** The codec failed. Try again on the same surface, but never in a tight loop. */
    private fun recover() {
        val s = surface ?: return
        val w = width
        val h = height
        val now = SystemClock.elapsedRealtime()
        restarts = if (now - lastRestartMs > 10_000) 1 else restarts + 1
        lastRestartMs = now
        stop(keepFeed = false)
        if (restarts > 4) {
            Log.w(TAG, "decoder keeps failing; giving up for now")
            return
        }
        start(s, w, h)
        onNeedKeyframe()
    }

    private fun pump() {
        val c = codec ?: return
        while (freeInputs.isNotEmpty()) {
            val f = feed.next() ?: break
            val index = freeInputs.removeFirst()
            try {
                val buf = c.getInputBuffer(index)
                if (buf == null || buf.capacity() < f.data.size) {
                    // Cannot be fed: hand the buffer back empty and resync rather than leave a hole in the group.
                    c.queueInputBuffer(index, 0, 0, f.ptsUs, 0)
                    feed.clear()
                    onNeedKeyframe()
                    break
                }
                buf.clear()
                buf.put(f.data)
                c.queueInputBuffer(index, 0, f.data.size, f.ptsUs, 0)
            } catch (t: Throwable) {
                Log.w(TAG, "feed failed: ${t.message}")
                recover()
                return
            }
        }
    }

    private companion object {
        const val TAG = "HeatDecoder"
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    }
}
