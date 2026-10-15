package app.heat.core.host

/**
 * Picks the frame rate the link can honestly carry, so the picture degrades the way a person would choose: smooth and
 * sharp when the road is clear (up to 60 fps), then fewer frames (never a smeared, blocky picture) as the road
 * narrows, and only at the very end does the bitrate itself fall to its floor.
 *
 * The rule is bits per pixel per frame. Below about [MIN_BPP] a hardware H.264 encoder starts to smear and block,
 * so rather than let it, the camera is asked for fewer frames and each one gets more bits. The bitrate itself comes
 * from [BitrateGovernor] (which reacts to real frame loss); this decides what to do with it.
 *
 * Down is immediate (a congested link must be relieved now); up needs the better rate to have been affordable, with
 * margin, for a few seconds, so the rate does not flap between two values.
 */
class FrameRateGovernor(private val tiers: IntArray = TIERS) {
    var fps: Int = 30
        private set

    private var betterSinceMs = 0L

    /** Start fresh (new link, new profile): begin in the middle and let the numbers decide. */
    fun reset(startFps: Int = 30) {
        fps = startFps
        betterSinceMs = 0
    }

    /**
     * [bps] is the bitrate in force, [pixels] the encoded picture's width times height, [maxFps] the most the camera
     * and the profile allow. Returns the frame rate to ask the camera for.
     */
    fun update(bps: Int, pixels: Int, maxFps: Int, nowMs: Long): Int {
        if (pixels <= 0) return fps
        val cap = tiers.filter { it <= maxFps }.maxOrNull() ?: tiers.min()
        fun affordable(t: Int, margin: Double) = bps >= pixels.toDouble() * t * MIN_BPP * margin
        val best = tiers.filter { it <= cap && affordable(it, 1.0) }.maxOrNull() ?: tiers.min()

        if (fps > cap) fps = cap
        if (best < fps) {
            fps = best
            betterSinceMs = 0
        } else if (best > fps) {
            // Climb one tier at a time, and only when the next one is affordable with a margin.
            val next = tiers.filter { it > fps && it <= cap }.minOrNull()
            if (next != null && affordable(next, UP_MARGIN)) {
                if (betterSinceMs == 0L) betterSinceMs = nowMs
                if (nowMs - betterSinceMs >= UP_AFTER_MS) {
                    fps = next
                    betterSinceMs = 0
                }
            } else betterSinceMs = 0
        } else betterSinceMs = 0
        return fps
    }

    companion object {
        val TIERS = intArrayOf(5, 10, 15, 20, 30, 45, 60)

        /** Bits per pixel per frame below which hardware H.264 at these sizes visibly degrades. */
        const val MIN_BPP = 0.07
        const val UP_MARGIN = 1.3
        const val UP_AFTER_MS = 4_000L
    }
}
