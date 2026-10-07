package app.heat.wear

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import app.heat.core.control.Haptics
import app.heat.core.model.HapticProfile
import app.heat.core.model.Msg

class HeatWearApp : Application() {
    /** One hub for the life of the process, so alerts still buzz the wrist when the screen is off. */
    val hub: WatchHub by lazy { WatchHub(this).also { it.start() } }
}

/** Wrist feedback for the bezel (ticks) and for alerts (patterns by profile and escalation). */
class WatchHaptics(context: Context) : Haptics {
    private val vibrator: Vibrator? = context.getSystemService(Vibrator::class.java)

    override fun tick() = play(VibrationEffect.EFFECT_TICK)
    override fun stepped() = play(VibrationEffect.EFFECT_TICK)
    override fun action() = play(VibrationEffect.EFFECT_CLICK)

    private fun play(effect: Int) {
        runCatching { vibrator?.vibrate(VibrationEffect.createPredefined(effect)) }
    }

    /** A single episode feels different from something that will not go away: stronger and longer as [alert] escalates. */
    fun alert(profile: HapticProfile, alert: Msg.Alert) {
        val v = vibrator ?: return
        val scale = when (profile) {
            HapticProfile.OFF -> return
            HapticProfile.SOFT -> 0.5f
            HapticProfile.NORMAL -> 1f
            HapticProfile.STRONG -> 1.6f
        }
        val level = 1 + alert.escalation.coerceIn(0, 3)
        val timings = ArrayList<Long>()
        timings += 0L
        repeat(level) {
            timings += (120 * scale).toLong()
            timings += 90L
        }
        val amps = ArrayList<Int>()
        amps += 0
        repeat(level) {
            amps += (255 * (scale / 1.6f)).toInt().coerceIn(40, 255)
            amps += 0
        }
        runCatching {
            v.vibrate(VibrationEffect.createWaveform(timings.toLongArray(), amps.toIntArray(), -1))
        }
    }
}

internal fun Bundle?.orEmpty(): Bundle = this ?: Bundle()
