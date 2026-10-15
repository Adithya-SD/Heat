package app.heat.core.host

import app.heat.core.link.DeviceId
import app.heat.core.model.AlertKind

/**
 * When a viewer with several cameras should jump to one that has just seen something.
 *
 * Jump to the camera that raised a motion alert if it is not the one on screen, and only if nothing was jumped to in
 * the last [dwellMs]: two cameras alerting together, or one flickering at the edge of its threshold, must not make the
 * picture dance between them. A camera that is already on screen never "switches to itself".
 */
class FollowMotion(@Volatile var dwellMs: Long = 0) {
    private var lastSwitchMs = Long.MIN_VALUE / 2

    /** True if [camera]'s [kind] alert at [nowMs] should put it on screen; records the switch when it says yes. */
    fun shouldSwitch(enabled: Boolean, selected: DeviceId?, camera: DeviceId, kind: AlertKind, nowMs: Long): Boolean {
        if (!enabled || kind != AlertKind.MOTION || selected == camera) return false
        if (nowMs - lastSwitchMs < dwellMs) return false
        lastSwitchMs = nowMs
        return true
    }

    /** The user chose a camera by hand: that counts as a switch, so a stray alert a moment later does not undo it. */
    fun noteManualSwitch(nowMs: Long) { lastSwitchMs = nowMs }
}
