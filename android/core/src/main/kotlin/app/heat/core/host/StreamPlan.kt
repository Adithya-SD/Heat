package app.heat.core.host

import app.heat.core.link.CarrierKind
import app.heat.core.model.StreamQuality
import app.heat.core.model.Tier

/** One encoder configuration. Resolution changes need an encoder restart; bitrate changes do not. */
data class StreamProfile(
    val width: Int, val height: Int, val fps: Int, val minBps: Int, val maxBps: Int,
    /** Seconds between keyframes. A keyframe is the biggest thing on the link, so a slow link wants fewer of them. */
    val gopSec: Int = 1
)

/**
 * How much video to send, from the link, what the viewer asked for, what the
 * user allows, the muddiness slider and the phone's temperature.
 *
 * Two hard facts shape the numbers. A round watch face shows a 4:3 picture
 * at about 360x270, so 640x480 already out-resolves the glass: beyond it,
 * battery and heat buy detail the watch throws away. And Bluetooth carries a
 * few hundred kilobits at best, so it gets its own small profile instead of a
 * big one starved of bits.
 */
object StreamPlan {
    val WIFI = StreamProfile(640, 480, 30, minBps = 400_000, maxBps = 6_000_000, gopSec = 1)
    val BLUETOOTH = StreamProfile(320, 240, 15, minBps = 60_000, maxBps = 360_000, gopSec = 2)

    /** The range the bitrate governor may roam, wide enough for either profile: carriers cap it from above. */
    val GOVERNOR_RANGE = StreamProfile(640, 480, 30, minBps = 60_000, maxBps = 6_000_000)

    /**
     * The most a carrier can carry, whatever the encoder is currently set to. Needed because the encoder keeps
     * running on the wide profile while a watch is still connecting over Bluetooth (a restart for a link that
     * will probably be Wi-Fi a second later would make the picture flicker), and offering Bluetooth megabits
     * only turns every frame into a drop.
     */
    fun carrierCap(link: CarrierKind?): Int = when (link) {
        CarrierKind.BLUETOOTH -> BLUETOOTH.maxBps
        else -> Int.MAX_VALUE
    }

    fun profileFor(link: CarrierKind?): StreamProfile = when (link) {
        CarrierKind.BLUETOOTH -> BLUETOOTH
        else -> WIFI // LAN, relay, or unknown: start with the wide profile; the governor backs off fast.
    }

    /** The most bits the current conditions justify. The governor adapts underneath this. */
    fun ceiling(
        profile: StreamProfile, tier: Tier, quality: StreamQuality, muddiness: Int, thermal: Int,
        carrier: CarrierKind? = null
    ): Int {
        val tierShare = when (tier) {
            Tier.LOW -> 0.10
            Tier.BALANCED -> 0.30
            Tier.HIGH -> 0.60
            Tier.MAX -> 1.0
        }
        val qualityShare = when (quality) {
            StreamQuality.AUTO -> 1.0
            StreamQuality.LOW -> 0.10
            StreamQuality.BALANCED -> 0.30
            StreamQuality.HIGH -> 0.60
            StreamQuality.MAX -> 1.0
        }
        val soft = 1.0 - 0.9 * muddiness.coerceIn(0, 100) / 100.0
        val heat = when (thermal) {
            0 -> 1.0
            1 -> 0.7
            else -> 0.4
        }
        val raw = profile.maxBps * minOf(tierShare, qualityShare) * soft * heat
        return minOf(raw.toInt().coerceIn(profile.minBps, profile.maxBps), carrierCap(carrier))
    }
}

/**
 * Closed-loop bitrate control from the one honest congestion signal there is:
 * the share of frames the link had to discard because it could not keep up.
 *
 * Backing off is fast and climbing is slow, deliberately: an eager climb
 * oscillates between sharp-and-stuttering and soft-and-smooth, which reads as
 * a broken app, while a slow climb just looks like the picture improving.
 */
class BitrateGovernor(private val profile: StreamProfile) {
    var bps: Int = (profile.minBps + (profile.maxBps - profile.minBps) / 4)
        private set

    private var lastOffered = 0L
    private var lastDropped = 0L
    private var cleanSamples = 0
    private var lastChangeMs = 0L
    private var lastWasBad = false

    /** The rate that last caused loss, shaved: do not climb past it for a while. Stops the climb-drop sawtooth. */
    private var softCap = Int.MAX_VALUE
    private var softCapUntilMs = 0L

    /** Start fresh (new link, new session): counters belong to the previous one. */
    fun reset(startBps: Int? = null) {
        startBps?.let { bps = it.coerceIn(profile.minBps, profile.maxBps) }
        lastOffered = 0
        lastDropped = 0
        cleanSamples = 0
        softCap = Int.MAX_VALUE
        softCapUntilMs = 0
    }

    /** Feed cumulative counters once a second. Returns the bitrate to use now. */
    fun sample(offered: Long, dropped: Long, ceiling: Int, nowMs: Long): Int {
        val dOffered = offered - lastOffered
        val dDropped = dropped - lastDropped
        lastOffered = offered
        lastDropped = dropped
        val cap = ceiling.coerceIn(profile.minBps, profile.maxBps)

        // A lowered ceiling (thermal, user change) applies immediately.
        if (bps > cap) {
            bps = cap
            lastChangeMs = nowMs
        }
        if (dOffered < MIN_SAMPLE) return bps

        val ratio = dDropped.toDouble() / dOffered
        // An isolated blip (one bad second among good ones) is not congestion; a sustained or severe one is.
        val bad = ratio > BACK_OFF
        val sustained = bad && lastWasBad
        lastWasBad = bad
        if (ratio > SEVERE || sustained) {
            cleanSamples = 0
            if (nowMs - lastChangeMs >= BACK_OFF_HOLD_MS) {
                softCap = (bps * 0.85).toInt().coerceAtLeast(profile.minBps)
                softCapUntilMs = nowMs + SOFT_CAP_MS
                bps = (bps * 0.7).toInt().coerceAtLeast(profile.minBps)
                lastChangeMs = nowMs
            }
        } else if (bad) {
            cleanSamples = 0
        } else if (ratio < CLEAN) {
            cleanSamples++
            val limit = if (nowMs < softCapUntilMs) minOf(cap, softCap) else cap
            if (cleanSamples >= CLEAN_NEEDED && nowMs - lastChangeMs >= CLIMB_HOLD_MS && bps < limit) {
                bps = (bps * 1.15).toInt().coerceAtMost(limit)
                lastChangeMs = nowMs
                cleanSamples = 0
            }
        } else {
            cleanSamples = 0
        }
        return bps
    }

    private companion object {
        const val MIN_SAMPLE = 8L
        const val BACK_OFF = 0.08
        const val SEVERE = 0.30
        const val CLEAN = 0.02
        const val CLEAN_NEEDED = 3
        const val BACK_OFF_HOLD_MS = 1_200L
        const val CLIMB_HOLD_MS = 2_500L
        const val SOFT_CAP_MS = 30_000L
    }
}
