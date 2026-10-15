package app.heat.phone.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import app.heat.core.link.MediaFlags

/**
 * Hardware H.264 encoder fed straight from the camera through its input
 * Surface: no CPU copy, no colour conversion.
 *
 * Tuned for a wrist, not an archive. Every choice trades quality for latency:
 *  - **CBR with a one-second keyframe interval.** A bitrate that spikes on scene
 *    change is exactly what saturates a link, and a short GOP bounds how long
 *    any loss can show.
 *  - **Realtime priority, latency 0, no B-frames.** The codec never buffers
 *    frames looking for better compression.
 *  - **Config on every keyframe.** The receiver may start at any moment; each
 *    keyframe carries SPS/PPS so it is independently decodable.
 */
class H264Encoder(
    private val onFrame: (flags: Int, ptsUs: Long, data: ByteArray, offset: Int, length: Int) -> Unit
) {
    private val thread = HandlerThread("heat-encoder", Process.THREAD_PRIORITY_URGENT_AUDIO).apply { start() }
    private val handler = Handler(thread.looper)

    private var codec: MediaCodec? = null
    private var config: ByteArray? = null
    private var scratch = ByteArray(64 * 1024)

    @Volatile var width = 0
        private set
    @Volatile var height = 0
        private set
    @Volatile var bitrate = 0
        private set
    @Volatile var running = false
        private set
    @Volatile var framesOut = 0L
        private set

    /** Starts the encoder and returns its input Surface (give it to the camera), or null if the device cannot. */
    @Synchronized
    fun start(width: Int, height: Int, fps: Int, bitrate: Int, gopSec: Int = 1): Surface? {
        stop()
        return try {
            val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, gopSec.coerceAtLeast(1))
                // The camera runs at its own rate; this makes the encoder skip frames to hold the profile's rate.
                setInteger(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps)
                setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                setInteger(MediaFormat.KEY_LATENCY, 0)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            }
            val c = MediaCodec.createEncoderByType(MIME)
            c.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    try {
                        val out = codec.getOutputBuffer(index)
                        if (out != null && info.size > 0) handleOutput(out, info)
                    } catch (t: Throwable) {
                        Log.w(TAG, "output handling failed", t)
                    } finally {
                        runCatching { codec.releaseOutputBuffer(index, false) }
                    }
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    // Some encoders deliver SPS/PPS only here, not as a config buffer.
                    val sps = format.getByteBuffer("csd-0")
                    val pps = format.getByteBuffer("csd-1")
                    if (sps != null) {
                        val a = ByteArray(sps.remaining()).also { sps.duplicate().get(it) }
                        val b = pps?.let { p -> ByteArray(p.remaining()).also { p.duplicate().get(it) } } ?: ByteArray(0)
                        config = a + b
                    }
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.w(TAG, "codec error: ${e.diagnosticInfo}", e)
                }
            }, handler)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = c.createInputSurface()
            c.start()
            codec = c
            config = null
            this.width = width
            this.height = height
            this.bitrate = bitrate
            framesOut = 0
            running = true
            Log.i(TAG, "encoder up: ${width}x$height @ ${fps}fps ${bitrate / 1000} kbps")
            surface
        } catch (t: Throwable) {
            Log.w(TAG, "encoder start failed", t)
            stop()
            null
        }
    }

    private fun handleOutput(out: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) {
        out.position(info.offset)
        out.limit(info.offset + info.size)

        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            config = ByteArray(info.size).also { out.get(it) }
            return
        }
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val prefix = if (key) config else null
        val total = (prefix?.size ?: 0) + info.size
        if (scratch.size < total) scratch = ByteArray(total + total / 2)
        if (prefix != null) System.arraycopy(prefix, 0, scratch, 0, prefix.size)
        out.get(scratch, prefix?.size ?: 0, info.size)
        framesOut++
        onFrame(if (key) MediaFlags.KEYFRAME else 0, info.presentationTimeUs, scratch, 0, total)
    }

    /** Change bitrate live, no restart. */
    fun setBitrate(bps: Int) {
        val c = codec ?: return
        if (bps == bitrate) return
        bitrate = bps
        runCatching {
            c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) })
        }.onFailure { Log.w(TAG, "bitrate change rejected: ${it.message}") }
    }

    /** Produce a keyframe now: a viewer just attached or lost sync. */
    fun requestKeyframe() {
        val c = codec ?: return
        runCatching {
            c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
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
        config = null
    }

    fun release() {
        stop()
        thread.quitSafely()
    }

    private companion object {
        const val TAG = "HeatEncoder"
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    }
}
