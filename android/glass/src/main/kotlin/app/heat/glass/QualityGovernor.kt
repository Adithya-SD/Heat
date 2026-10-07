package app.heat.glass

/**
 * Decides how much glass the device can afford, from nothing but whether recent
 * frames made their deadline.
 *
 * Pure logic with no Android in it, so the policy is unit tested. The rules are
 * deliberately lopsided: back off quickly (a janky UI is the worst outcome),
 * climb slowly (an oscillating look is the second worst), and be more
 * reluctant to climb again after a failed attempt.
 */
class QualityGovernor(
    private val maxQuality: Int,
    private val windowFrames: Int = 90,
    private val dropAbove: Double = 0.15,
    private val cleanBelow: Double = 0.03
) {
    var quality: Int = maxQuality
        private set

    private var frames = 0
    private var janky = 0
    private var cleanWindows = 0
    private var cleanNeeded = 5

    /** Feed one frame. Returns the new quality when it changed, else null. */
    fun onFrame(missedDeadline: Boolean): Int? {
        frames++
        if (missedDeadline) janky++
        if (frames < windowFrames) return null

        val ratio = janky.toDouble() / frames
        frames = 0
        janky = 0

        if (ratio > dropAbove && quality > 0) {
            quality--
            cleanWindows = 0
            // A step down means the higher tier did not fit: wait longer before trying it again.
            cleanNeeded = (cleanNeeded * 2).coerceAtMost(40)
            return quality
        }
        if (ratio < cleanBelow) {
            cleanWindows++
            if (cleanWindows >= cleanNeeded && quality < maxQuality) {
                quality++
                cleanWindows = 0
                return quality
            }
        } else {
            cleanWindows = 0
        }
        return null
    }
}
