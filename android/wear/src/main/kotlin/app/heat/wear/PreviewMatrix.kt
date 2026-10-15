package app.heat.wear

import android.graphics.Matrix

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
