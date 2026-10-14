package app.heat.core.host

/**
 * What stands between the network and a video decoder.
 *
 * Two problems are solved here, both in plain Kotlin so they are tested without a device:
 *
 *  - **The picture can start before the decoder exists.** Frames begin arriving the moment the link is up;
 *    the screen, its surface and the codec take a few hundred milliseconds longer. The feed keeps the
 *    current group of pictures (the last keyframe and the deltas after it), so a decoder that starts late
 *    replays it and shows a picture immediately, instead of asking the camera for a keyframe and waiting
 *    a round trip (or, worse, for the next natural one).
 *  - **A slow decoder must not accumulate lag.** If the consumer falls more than [maxLive] frames behind,
 *    the backlog is thrown away and everything is held back until the next keyframe: deltas cannot be
 *    skipped one by one, because each is built on the one before. The picture freezes for a moment; it
 *    never smears and never runs seconds behind.
 *
 * One producer thread calls [offer]; one consumer thread calls [next]. Frames are copied in, so the
 * producer's buffer can be reused immediately.
 */
class FrameFeed(
    private val maxFrames: Int = 120,
    private val maxBytes: Int = 3_000_000,
    private val maxLive: Int = 5
) {
    class Frame(val key: Boolean, val ptsUs: Long, val data: ByteArray)

    enum class Offer {
        ACCEPTED,

        /** The feed just lost sync (overflow or a slow consumer). Ask the camera for a keyframe. */
        NEED_KEYFRAME,

        /** Discarded because we are already waiting for a keyframe. Do not ask again. */
        DROPPED
    }

    private val frames = ArrayList<Frame>()
    private var cursor = 0
    private var bytes = 0
    private var live = false
    private var catchingUp = false
    private var awaitingKey = true

    /** Frames the consumer has not taken yet. */
    val backlog: Int @Synchronized get() = frames.size - cursor

    @Synchronized
    fun offer(key: Boolean, ptsUs: Long, data: ByteArray, offset: Int, length: Int): Offer {
        if (length <= 0) return Offer.DROPPED
        if (key) {
            // A keyframe supersedes everything before it, including frames nobody has decoded yet.
            frames.clear()
            bytes = 0
            cursor = 0
            awaitingKey = false
            add(key = true, ptsUs, data, offset, length)
            return Offer.ACCEPTED
        }
        if (awaitingKey || frames.isEmpty()) return Offer.DROPPED

        add(key = false, ptsUs, data, offset, length)
        if (frames.size > maxFrames || bytes > maxBytes) return loseSync()
        if (live && !catchingUp && frames.size - cursor > maxLive) return loseSync()
        return Offer.ACCEPTED
    }

    /** The next frame to decode, or null when the consumer has caught up. */
    @Synchronized
    fun next(): Frame? {
        if (cursor < frames.size) return frames[cursor++]
        catchingUp = false
        return null
    }

    /** A decoder is (re)starting: replay the retained group of pictures from its keyframe. */
    @Synchronized
    fun start() {
        live = true
        cursor = 0
        catchingUp = frames.isNotEmpty()
    }

    /** The decoder is gone (screen off, surface lost). Frames keep being retained for the next [start]. */
    @Synchronized
    fun stop() {
        live = false
        catchingUp = false
    }

    /** Forget everything, e.g. the picture size changed or the camera changed. */
    @Synchronized
    fun clear() {
        frames.clear()
        bytes = 0
        cursor = 0
        awaitingKey = true
        catchingUp = false
    }

    private fun add(key: Boolean, ptsUs: Long, data: ByteArray, offset: Int, length: Int) {
        frames += Frame(key, ptsUs, data.copyOfRange(offset, offset + length))
        bytes += length
    }

    private fun loseSync(): Offer {
        frames.clear()
        bytes = 0
        cursor = 0
        catchingUp = false
        awaitingKey = true
        return Offer.NEED_KEYFRAME
    }
}
