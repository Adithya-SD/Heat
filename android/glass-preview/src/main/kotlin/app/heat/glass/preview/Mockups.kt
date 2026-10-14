package app.heat.glass.preview

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ClipMode
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.PaintStrokeJoin
import org.jetbrains.skia.PathEffect
import org.jetbrains.skia.PathBuilder
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.io.File
import java.util.Random
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Code-derived mock-ups of the real screens.
 *
 * Every measurement below is taken from the Compose source (paddings, sizes, shapes, which surfaces are shaded
 * and which are flat), the glass is drawn by the *production shader* with the app's own look parameters, and the
 * phone layout is calibrated against a real screenshot taken on the device. What is approximate: the camera
 * picture (a synthetic room), and the font (Segoe UI here, the system font on the devices).
 */
internal object Mockups {
    private val ORANGE = Color.makeRGB(0xFF, 0x7A, 0x2F)
    private val GREEN = Color.makeRGB(0x34, 0xD2, 0x7A)
    private val AMBER = Color.makeRGB(0xFF, 0xC2, 0x47)
    private val TEXT = Color.makeRGB(0xF5, 0xF5, 0xF7)
    private val DIM = Color.makeARGB(0xB8, 0xF5, 0xF5, 0xF7)
    private val FAINT = Color.makeARGB(0x80, 0xF5, 0xF5, 0xF7)

    private fun argb(a: Float, r: Int, g: Int, b: Int) = Color.makeARGB((a * 255).roundToInt().coerceIn(0, 255), r, g, b)
    private fun fillP(color: Int) = Paint().also { it.color = color; it.isAntiAlias = true }
    private fun strokeP(color: Int, w: Float) = Paint().also {
        it.color = color; it.mode = PaintMode.STROKE; it.strokeWidth = w; it.isAntiAlias = true
        it.strokeCap = PaintStrokeCap.ROUND; it.strokeJoin = PaintStrokeJoin.ROUND
    }

    private val mgr = FontMgr.default
    private fun face(bold: Boolean) =
        mgr.matchFamilyStyle(if (bold) "Segoe UI Semibold" else "Segoe UI", FontStyle.NORMAL)
            ?: mgr.matchFamilyStyle("Arial", if (bold) FontStyle.BOLD else FontStyle.NORMAL)

    /** Draws [s] vertically centred on [cy]. align: 0 left, 1 centre, 2 right of [x]. Returns the width. */
    /** Segoe UI sets larger than the devices' system font at the same size; measured against the real screenshot. */
    private const val FONT_SCALE = 0.84f

    private fun text(c: Canvas, s: String, x: Float, cy: Float, size: Float, color: Int, bold: Boolean = false, align: Int = 0): Float {
        val font = Font(face(bold), size * FONT_SCALE)
        val w = font.measureTextWidth(s)
        val m = font.metrics
        val x0 = when (align) { 1 -> x - w / 2; 2 -> x - w; else -> x }
        c.drawString(s, x0, cy - (m.ascent + m.descent) / 2, font, fillP(color))
        return w
    }

    private fun textW(s: String, size: Float, bold: Boolean = false) = Font(face(bold), size * FONT_SCALE).measureTextWidth(s)

    // ── glass pieces ─────────────────────────────────────────────────

    /** The flat fallback glass, exactly as `drawFlatGlass` draws it. */
    private fun flatGlass(c: Canvas, x: Float, y: Float, w: Float, h: Float, radius: Float, d: Float, tint: Float = 0.045f) {
        val r = min(radius, min(w, h) / 2)
        val boost = max(tint - 0.045f, 0f)
        c.save()
        c.clipRRect(RRect.makeXYWH(x, y, w, h, r), ClipMode.INTERSECT, true)
        val rows = ceil(h).toInt().coerceAtLeast(2)
        for (i in 0 until rows) {
            val f = i / (rows - 1f)
            val a = (0.20f + boost) + ((0.07f + boost * 0.6f) - (0.20f + boost)) * f
            c.drawRect(Rect.makeXYWH(x, y + i, w, 1.2f), fillP(argb(a, 255, 255, 255)))
        }
        c.restore()
        val rim = 1.2f * d
        val rr = RRect.makeXYWH(x + rim / 2, y + rim / 2, w - rim, h - rim, max(r - rim / 2, 0f))
        c.drawRRect(rr, strokeP(argb(0.30f, 255, 255, 255), rim))
        // the lit top-left half of the rim
        c.save()
        val half = PathBuilder().moveTo(x, y).lineTo(x + w, y).lineTo(x, y + h).closePath().snapshot()
        c.clipPath(half, ClipMode.INTERSECT, true)
        c.drawRRect(rr, strokeP(argb(0.60f, 255, 255, 255), rim))
        c.restore()
    }

    private enum class Ic { Bolt, Flip, Gear, Shield, Back, Eye, Plus, Minus, Wifi, Bluetooth, Aim, List }

    /** The same primitives as `HeatIconView`, on the same 24-unit grid. */
    private fun icon(c: Canvas, ic: Ic, cx: Float, cy: Float, size: Float, color: Int) {
        val u = size / 24f
        val ox = cx - size / 2
        val oy = cy - size / 2
        val st = strokeP(color, 1.9f * u)
        fun poly(vararg pts: Pair<Float, Float>, close: Boolean = false, fill: Boolean = false) {
            val p = PathBuilder()
            pts.forEachIndexed { i, (x, y) -> if (i == 0) p.moveTo(ox + x * u, oy + y * u) else p.lineTo(ox + x * u, oy + y * u) }
            if (close) p.closePath()
            c.drawPath(p.snapshot(), if (fill) fillP(color) else st)
        }
        fun arc(x: Float, y: Float, s: Float, start: Float, sweep: Float) =
            c.drawArc(ox + x * u, oy + y * u, ox + (x + s) * u, oy + (y + s) * u, start, sweep, false, st)
        when (ic) {
            Ic.Bolt -> poly(13f to 2f, 5f to 13.5f, 11f to 13.5f, 10f to 22f, 19f to 10f, 13f to 10f, close = true, fill = true)
            Ic.Flip -> {
                arc(4.5f, 4.5f, 15f, 200f, 140f); arc(4.5f, 4.5f, 15f, 20f, 140f)
                poly(17.5f to 3f, 17.5f to 7.5f, 13f to 7.5f); poly(6.5f to 21f, 6.5f to 16.5f, 11f to 16.5f)
            }
            Ic.Gear -> {
                c.drawCircle(ox + 12 * u, oy + 12 * u, 3.2f * u, st)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val cs = Math.cos(a).toFloat(); val sn = Math.sin(a).toFloat()
                    c.drawLine(ox + (12f + 6.2f * cs) * u, oy + (12f + 6.2f * sn) * u, ox + (12f + 9f * cs) * u, oy + (12f + 9f * sn) * u, strokeP(color, 2.6f * u))
                }
                c.drawCircle(ox + 12 * u, oy + 12 * u, 6.2f * u, st)
            }
            Ic.Shield -> {
                val p = PathBuilder().moveTo(ox + 12 * u, oy + 2.5f * u).lineTo(ox + 19.5f * u, oy + 5.5f * u).lineTo(ox + 19.5f * u, oy + 11.5f * u)
                    .cubicTo(ox + 19.5f * u, oy + 16.5f * u, ox + 16f * u, oy + 20f * u, ox + 12 * u, oy + 21.5f * u)
                    .cubicTo(ox + 8f * u, oy + 20f * u, ox + 4.5f * u, oy + 16.5f * u, ox + 4.5f * u, oy + 11.5f * u)
                    .lineTo(ox + 4.5f * u, oy + 5.5f * u).closePath().snapshot()
                c.drawPath(p, st)
            }
            Ic.Back -> poly(15f to 5f, 8f to 12f, 15f to 19f)
            Ic.Plus -> { poly(12f to 5f, 12f to 19f); poly(5f to 12f, 19f to 12f) }
            Ic.Minus -> poly(5f to 12f, 19f to 12f)
            Ic.Eye -> {
                c.drawOval(Rect.makeXYWH(ox + 2.5f * u, oy + 6.5f * u, 19f * u, 11f * u), st)
                c.drawCircle(ox + 12 * u, oy + 12 * u, 2.8f * u, fillP(color))
            }
            Ic.Wifi -> {
                arc(2.5f, 5f, 19f, 225f, 90f); arc(6f, 8.5f, 12f, 225f, 90f)
                c.drawCircle(ox + 12 * u, oy + 18 * u, 1.4f * u, fillP(color))
            }
            Ic.Bluetooth -> poly(7f to 7.5f, 17f to 16.5f, 12f to 21f, 12f to 3f, 17f to 7.5f, 7f to 16.5f)
            Ic.Aim -> {
                c.drawCircle(ox + 12 * u, oy + 12 * u, 6.5f * u, st)
                poly(12f to 2f, 12f to 6f); poly(12f to 18f, 12f to 22f); poly(2f to 12f, 6f to 12f); poly(18f to 12f, 22f to 12f)
            }
            Ic.List -> {
                poly(8f to 6f, 20f to 6f); poly(8f to 12f, 20f to 12f); poly(8f to 18f, 20f to 18f)
                for (y in listOf(6f, 12f, 18f)) c.drawCircle(ox + 4 * u, oy + y * u, 1.2f * u, fillP(color))
            }
        }
    }

    // ── a believable camera picture (synthetic) ──────────────────────

    /** A dusk living room, portrait 3:4, with a person standing near the middle (where the default tracker box sits). */
    private fun room(w: Int, h: Int): Image {
        val s = Surface.makeRasterN32Premul(w, h)
        val c = s.canvas
        fun lerp(a: Int, b: Int, t: Float) = (a + (b - a) * t).roundToInt()
        val floorY = (h * 0.66f).toInt()
        for (y in 0 until h) {
            val col = if (y < floorY) {
                val t = y / floorY.toFloat()
                Color.makeRGB(lerp(0x86, 0x5c, t), lerp(0x77, 0x4e, t), lerp(0x6a, 0x45, t))
            } else {
                val t = (y - floorY) / (h - floorY).toFloat()
                Color.makeRGB(lerp(0x52, 0x2a, t), lerp(0x3a, 0x1e, t), lerp(0x27, 0x14, t))
            }
            c.drawRect(Rect.makeXYWH(0f, y.toFloat(), w.toFloat(), 1f), fillP(col))
        }
        for (i in 1..14) c.drawLine(0f, floorY + i * i * 3.2f, w.toFloat(), floorY + i * i * 3.2f, strokeP(argb(0.10f, 0, 0, 0), 2f))
        c.drawRect(Rect.makeXYWH(0f, floorY - 10f, w.toFloat(), 12f), fillP(argb(0.35f, 40, 28, 20)))

        // window with last daylight, and its light on the floor
        val wx = w * 0.07f; val wy = h * 0.09f; val ww = w * 0.46f; val wh = h * 0.33f
        for (i in 0 until wh.toInt()) {
            val t = i / wh
            c.drawRect(Rect.makeXYWH(wx, wy + i, ww, 1.2f), fillP(Color.makeRGB(lerp(0xB8, 0xFF, t), lerp(0xD4, 0xEE, t), lerp(0xFF, 0xC8, t))))
        }
        c.drawRect(Rect.makeXYWH(wx, wy, ww, wh), strokeP(Color.makeRGB(0x3a, 0x2a, 0x20), w * 0.012f))
        c.drawLine(wx + ww / 2, wy, wx + ww / 2, wy + wh, strokeP(Color.makeRGB(0x3a, 0x2a, 0x20), w * 0.009f))
        c.drawLine(wx, wy + wh / 2, wx + ww, wy + wh / 2, strokeP(Color.makeRGB(0x3a, 0x2a, 0x20), w * 0.009f))
        val shaft = PathBuilder().moveTo(wx + ww * 0.1f, floorY.toFloat()).lineTo(wx + ww * 0.85f, floorY.toFloat())
            .lineTo(w * 0.62f, h * 0.93f).lineTo(w * 0.05f, h * 0.93f).closePath().snapshot()
        c.drawPath(shaft, fillP(argb(0.10f, 255, 236, 190)))

        // picture frame, rug, sofa, lamp, plant
        c.drawRRect(RRect.makeXYWH(w * 0.63f, h * 0.13f, w * 0.27f, h * 0.17f, 6f), fillP(Color.makeRGB(0x2b, 0x20, 0x1a)))
        c.drawRect(Rect.makeXYWH(w * 0.645f, h * 0.145f, w * 0.24f, h * 0.14f), fillP(Color.makeRGB(0xd8, 0x8a, 0x4a)))
        c.drawCircle(w * 0.72f, h * 0.2f, w * 0.05f, fillP(Color.makeRGB(0x2f, 0x7a, 0x82)))
        c.drawOval(Rect.makeXYWH(w * 0.12f, h * 0.79f, w * 0.7f, h * 0.12f), fillP(argb(0.55f, 120, 54, 40)))
        c.drawRRect(RRect.makeXYWH(w * 0.05f, h * 0.46f, w * 0.74f, h * 0.13f, 26f), fillP(Color.makeRGB(0x2e, 0x4a, 0x54)))
        c.drawRRect(RRect.makeXYWH(w * 0.03f, h * 0.54f, w * 0.78f, h * 0.15f, 30f), fillP(Color.makeRGB(0x38, 0x5a, 0x66)))
        for (i in 0 until 3) c.drawRRect(RRect.makeXYWH(w * (0.09f + i * 0.22f), h * 0.545f, w * 0.19f, h * 0.075f, 18f), fillP(Color.makeRGB(0x42, 0x68, 0x76)))
        c.drawRRect(RRect.makeXYWH(w * 0.82f, h * 0.30f, w * 0.008f, h * 0.40f, 3f), fillP(Color.makeRGB(0x1c, 0x18, 0x16)))
        for (i in 5 downTo 1) c.drawCircle(w * 0.855f, h * 0.285f, w * (0.04f + i * 0.035f), fillP(argb(0.05f, 255, 200, 110)))
        c.drawPath(PathBuilder().moveTo(w * 0.80f, h * 0.31f).lineTo(w * 0.91f, h * 0.31f).lineTo(w * 0.885f, h * 0.245f).lineTo(w * 0.825f, h * 0.245f).closePath().snapshot(), fillP(Color.makeRGB(0xff, 0xd9, 0x8a)))
        c.drawRRect(RRect.makeXYWH(w * 0.89f, h * 0.64f, w * 0.07f, h * 0.05f, 6f), fillP(Color.makeRGB(0x6a, 0x3a, 0x26)))
        for ((dx, dy, r) in listOf(Triple(0.925f, 0.60f, 0.045f), Triple(0.90f, 0.57f, 0.04f), Triple(0.95f, 0.575f, 0.038f))) {
            c.drawCircle(w * dx, h * dy, w * r, fillP(Color.makeRGB(0x2f, 0x6e, 0x3a)))
        }

        // a person standing at the centre of the frame
        val px = w * 0.47f
        c.drawOval(Rect.makeXYWH(px - w * 0.1f, h * 0.745f, w * 0.2f, h * 0.03f), fillP(argb(0.35f, 0, 0, 0)))
        c.drawRRect(RRect.makeXYWH(px - w * 0.055f, h * 0.53f, w * 0.045f, h * 0.22f, 8f), fillP(Color.makeRGB(0x20, 0x26, 0x3a)))
        c.drawRRect(RRect.makeXYWH(px + w * 0.005f, h * 0.53f, w * 0.045f, h * 0.22f, 8f), fillP(Color.makeRGB(0x20, 0x26, 0x3a)))
        c.drawRRect(RRect.makeXYWH(px - w * 0.075f, h * 0.40f, w * 0.15f, h * 0.17f, 24f), fillP(Color.makeRGB(0xb0, 0x4a, 0x38)))
        c.drawRRect(RRect.makeXYWH(px - w * 0.105f, h * 0.41f, w * 0.04f, h * 0.15f, 12f), fillP(Color.makeRGB(0xb0, 0x4a, 0x38)))
        c.drawRRect(RRect.makeXYWH(px + w * 0.065f, h * 0.41f, w * 0.04f, h * 0.15f, 12f), fillP(Color.makeRGB(0xb0, 0x4a, 0x38)))
        c.drawCircle(px, h * 0.37f, w * 0.052f, fillP(Color.makeRGB(0xe2, 0xb8, 0x96)))
        c.drawCircle(px, h * 0.358f, w * 0.054f, fillP(Color.makeRGB(0x2a, 0x1c, 0x16)))
        c.drawCircle(px, h * 0.375f, w * 0.048f, fillP(Color.makeRGB(0xe2, 0xb8, 0x96)))

        // vignette, then soften and add sensor grain
        for (i in 0 until 70) {
            val a = ((70 - i) / 70f).let { it * it * 0.42f }
            c.drawRect(Rect.makeXYWH(i.toFloat(), i.toFloat(), w - 2f * i, h - 2f * i), strokeP(argb(a, 0, 0, 0), 1.4f).also { it.strokeCap = PaintStrokeCap.BUTT })
        }
        val soft = Surface.makeRasterN32Premul(w, h)
        soft.canvas.drawImage(s.makeImageSnapshot(), 0f, 0f, Paint().also { it.imageFilter = ImageFilter.makeBlur(1.6f, 1.6f, FilterTileMode.CLAMP) })
        val rnd = Random(7)
        val g = Paint().also { it.color = Color.makeARGB(26, 255, 255, 255) }
        val k = Paint().also { it.color = Color.makeARGB(26, 0, 0, 0) }
        repeat(w * h / 14) { soft.canvas.drawRect(Rect.makeXYWH(rnd.nextInt(w).toFloat(), rnd.nextInt(h).toFloat(), 1.5f, 1.5f), if (rnd.nextBoolean()) g else k) }
        return soft.makeImageSnapshot()
    }

    private fun png(img: Image, f: File) = f.writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)

    // ── entry point ──────────────────────────────────────────────────

    fun render(effect: RuntimeEffect, out: File) {
        phoneLive(effect, out)
        phoneSettings(effect, out)
        watchCamera(effect, out)
        watchSettings(out)
    }

    /** Where the app's own look parameters land in pixels at density [d]. */
    private fun look(d: Float) = Look(ior = 1.5f, thick = 12f * d, dispersion = 0.12f, spec = 1f, blur = max(0.4f * d, 0.5f), shadow = 1f, saturation = 1.18f)

    // ── phone: live screen ───────────────────────────────────────────

    private fun phoneLive(effect: RuntimeEffect, out: File) {
        val W = 1080; val H = 2340
        val d = 1.75f // calibrated against a real screenshot of this phone (see the side-by-side)
        val pad = 16 * d
        val top = 101f + pad
        val rowH = 56 * d
        val rowTop = H - pad - rowH

        // backdrop: black, the 3:4 camera picture in the middle, the tracker box
        val bs = Surface.makeRasterN32Premul(W, H)
        bs.canvas.drawRect(Rect.makeWH(W.toFloat(), H.toFloat()), fillP(Color.BLACK))
        val picH = W * 4f / 3f
        val picY = (H - picH) / 2f
        bs.canvas.drawImageRect(room(900, 1200), Rect.makeWH(900f, 1200f), Rect.makeXYWH(0f, picY, W.toFloat(), picH), SamplingMode.LINEAR, null, true)
        run {
            val z = Rect.makeXYWH(W * 0.34f, picY + picH * 0.34f, W * 0.32f, picH * 0.32f)
            bs.canvas.drawRRect(RRect.makeXYWH(z.left, z.top, z.width, z.height, 10 * d), Paint().also {
                it.color = argb(0.45f, 255, 255, 255); it.mode = PaintMode.STROKE; it.strokeWidth = 1.5f * d; it.isAntiAlias = true
                it.pathEffect = PathEffect.makeDash(floatArrayOf(14f, 10f), 0f)
            })
        }

        // geometry from LiveScreen.kt
        val topPillH = 40 * d
        val ready = textW("Ready", 15 * d, true)
        val topPillW = (18 + 9 + 10 + 10 + 17 + 4 + 18) * d + ready + textW("0", 15 * d)
        val iconSize = 52 * d
        val gap = 12 * d
        val btnY = rowTop + (rowH - iconSize) / 2
        val x1 = pad
        val x2 = x1 + iconSize + gap
        val pillX = x2 + iconSize + gap
        val x5 = W - pad - iconSize
        val x4 = x5 - gap - iconSize
        val pillW = x4 - gap - pillX

        val lenses = mutableListOf<Lens>()
        lenses += Lens(pad, top, pad + topPillW, top + topPillH, radius = topPillH / 2, bezel = 14 * d)
        for (x in listOf(x1, x2, x4, x5)) lenses += Lens(x, btnY, x + iconSize, btnY + iconSize, radius = iconSize / 2, bezel = 52 * 0.3f * d)
        lenses += Lens(pillX, rowTop, pillX + pillW, rowTop + rowH, radius = rowH / 2, bezel = 56 * 0.32f * d, tint = 0.06f)

        val glass = render(effect, bs.makeImageSnapshot(), W, H, lenses, look(d), quality = 2)
        val s = Surface.makeRasterN32Premul(W, H)
        val c = s.canvas
        c.drawImage(glass, 0f, 0f)

        // system bar, as the device draws it
        text(c, "7:52", pad * 1.6f, 62f, 17 * d, TEXT, true)
        c.drawRRect(RRect.makeXYWH(W - 132f, 46f, 74f, 32f, 16f), fillP(argb(0.85f, 200, 200, 205)))
        text(c, "21", W - 95f, 62f, 15 * d, Color.BLACK, true, 1)
        c.drawRRect(RRect.makeXYWH(W / 2f - 120f, H - 22f, 240f, 7f, 3.5f), fillP(argb(0.55f, 255, 255, 255)))

        // top pill content
        val cy = top + topPillH / 2
        var x = pad + 18 * d
        c.drawCircle(x + 4.5f * d, cy, 4.5f * d, fillP(FAINT))
        x += (9 + 10) * d
        x += text(c, "Ready", x, cy, 15 * d, TEXT, true) + 10 * d
        icon(c, Ic.Eye, x + 8.5f * d, cy, 17 * d, FAINT)
        x += (17 + 4) * d
        text(c, "0", x, cy, 15 * d, FAINT)

        // battery pill (flat glass)
        val batW = 28 * d + textW("21%", 14 * d)
        val batH = 39 * d
        val batX = W - pad - batW
        flatGlass(c, batX, cy - batH / 2, batW, batH, batH / 2, d)
        text(c, "21%", batX + batW / 2, cy, 14 * d, DIM, false, 1)

        // zoom capsule (flat glass)
        val zw = (4 * 54 + 10) * d; val zh = 46 * d
        val zx = (W - zw) / 2; val zy = rowTop - 14 * d - zh
        flatGlass(c, zx, zy, zw, zh, zh / 2, d)
        for ((i, label) in listOf("0.5", "1×", "2×", "5×").withIndex()) {
            val bx = zx + 5 * d + i * 54 * d
            val on = i == 1
            if (on) c.drawRRect(RRect.makeXYWH(bx, zy + 5 * d, 54 * d, 36 * d, 18 * d), fillP(argb(0.27f, 255, 255, 255)))
            text(c, label, bx + 27 * d, zy + zh / 2, 15 * d, if (on) ORANGE else TEXT, true, 1)
        }

        // bottom row content
        fun glyph(ic: Ic, x: Float) = icon(c, ic, x + iconSize / 2, btnY + iconSize / 2, iconSize * 0.46f, TEXT)
        glyph(Ic.Bolt, x1); glyph(Ic.Aim, x2); glyph(Ic.Flip, x4); glyph(Ic.Gear, x5)
        val px = pillX + pillW / 2
        text(c, "Arm", px, rowTop + rowH / 2 - 9 * d, 17 * d, ORANGE, true, 1)
        text(c, "in 10s", px, rowTop + rowH / 2 + 11 * d, 12 * d, DIM, false, 1)

        png(s.makeImageSnapshot(), File(out, "mock_phone_live.png"))
    }

    // ── phone: settings ──────────────────────────────────────────────

    private fun phoneSettings(effect: RuntimeEffect, out: File) {
        val W = 1080; val H = 2340
        val d = 1.75f
        val bs = Surface.makeRasterN32Premul(W, H)
        val bc = bs.canvas
        val stops = listOf(0f to intArrayOf(0x1B, 0x10, 0x18), 0.5f to intArrayOf(0x0A, 0x0A, 0x10), 1f to intArrayOf(0x14, 0x09, 0x0A))
        for (y in 0 until H) {
            val f = y / (H - 1f)
            val i = if (f < 0.5f) 0 else 1
            val (f0, c0) = stops[i]; val (f1, c1) = stops[i + 1]
            val t = ((f - f0) / (f1 - f0)).coerceIn(0f, 1f)
            bc.drawRect(Rect.makeXYWH(0f, y.toFloat(), W.toFloat(), 1f), fillP(Color.makeRGB((c0[0] + (c1[0] - c0[0]) * t).toInt(), (c0[1] + (c1[1] - c0[1]) * t).toInt(), (c0[2] + (c1[2] - c0[2]) * t).toInt())))
        }
        val backBtn = 46 * d
        val back = Lens(16 * d, 101f + 12 * d, 16 * d + backBtn, 101f + 12 * d + backBtn, radius = backBtn / 2, bezel = 46 * 0.3f * d)
        val glass = render(effect, bs.makeImageSnapshot(), W, H, listOf(back), look(d), quality = 2)
        val s = Surface.makeRasterN32Premul(W, H)
        val mc = s.canvas
        mc.drawImage(glass, 0f, 0f)
        val layer = Surface.makeRasterN32Premul(W, H)
        val c = layer.canvas

        text(c, "7:52", 16 * d * 1.6f, 62f, 17 * d, TEXT, true)
        icon(c, Ic.Back, 16 * d + backBtn / 2, 101f + 12 * d + backBtn / 2, backBtn * 0.46f, TEXT)
        text(c, "Settings", 16 * d + backBtn + 14 * d, 101f + 12 * d + backBtn / 2 - 8 * d, 24 * d, TEXT, true)
        text(c, "Camera", 16 * d + backBtn + 14 * d, 101f + 12 * d + backBtn / 2 + 14 * d, 13 * d, FAINT)

        var y = 101f + 12 * d + backBtn + 12 * d
        val cardX = 16 * d; val cardW = W - 32 * d

        fun groupStart(title: String): Float {
            y += 8 * d
            text(c, title.uppercase(), cardX + 8 * d, y + 6 * d, 12 * d, FAINT, true)
            y += 20 * d
            return y
        }
        fun card(top: Float, bottom: Float) = flatGlass(mc, cardX, top, cardW, bottom - top, 22 * d, d)
        fun divider() { c.drawRect(Rect.makeXYWH(cardX + 18 * d, y, cardW - 36 * d, 1f), fillP(argb(0.15f, 255, 255, 255))); y += 1 }

        fun segment(label: String, hint: String?, options: List<String>, sel: Int) {
            val top = y
            y += 12 * d
            text(c, label, cardX + 18 * d, y + 9 * d, 16 * d, TEXT); y += 20 * d
            if (hint != null) { text(c, hint, cardX + 18 * d, y + 7 * d, 12 * d, FAINT); y += 15 * d }
            y += 10 * d
            val bx = cardX + 18 * d; val bw = cardW - 36 * d; val bh = 42 * d
            c.drawRRect(RRect.makeXYWH(bx, y, bw, bh, 14 * d), fillP(argb(0.13f, 255, 255, 255)))
            val seg = (bw - 6 * d) / options.size
            for ((i, o) in options.withIndex()) {
                val sx = bx + 3 * d + i * seg
                if (i == sel) c.drawRRect(RRect.makeXYWH(sx, y + 3 * d, seg, 36 * d, 11 * d), fillP(argb(0.33f, 255, 255, 255)))
                text(c, o, sx + seg / 2, y + bh / 2, 14 * d, if (i == sel) TEXT else DIM, i == sel, 1)
            }
            y += bh + 12 * d
            if (top < 0) return
        }
        fun toggle(label: String, hint: String?, on: Boolean) {
            y += 12 * d
            text(c, label, cardX + 18 * d, y + 9 * d, 16 * d, TEXT)
            var h = 20 * d
            if (hint != null) { text(c, hint, cardX + 18 * d, y + 20 * d + 7 * d, 12 * d, FAINT); h += 15 * d }
            val sx = cardX + cardW - 18 * d - 52 * d; val sy = y + h / 2 - 16 * d
            c.drawRRect(RRect.makeXYWH(sx, sy, 52 * d, 32 * d, 16 * d), fillP(if (on) argb(0.85f, 0x34, 0xD2, 0x7A) else argb(0.2f, 255, 255, 255)))
            c.drawCircle(if (on) sx + 52 * d - 16 * d else sx + 16 * d, sy + 16 * d, 13 * d, fillP(Color.WHITE))
            y += max(h, 32 * d) + 12 * d
        }
        fun stepper(label: String, value: String) {
            y += 12 * d
            val h = 36 * d
            text(c, label, cardX + 18 * d, y + h / 2, 16 * d, TEXT)
            val right = cardX + cardW - 18 * d
            c.drawCircle(right - 18 * d, y + h / 2, 18 * d, fillP(argb(0.16f, 255, 255, 255)))
            icon(c, Ic.Plus, right - 18 * d, y + h / 2, 18 * d, TEXT)
            text(c, value, right - 36 * d - 36 * d, y + h / 2, 16 * d, ORANGE, true, 1)
            c.drawCircle(right - 36 * d - 72 * d - 18 * d, y + h / 2, 18 * d, fillP(argb(0.16f, 255, 255, 255)))
            icon(c, Ic.Minus, right - 36 * d - 72 * d - 18 * d, y + h / 2, 18 * d, TEXT)
            y += h + 12 * d
        }

        fun slider(label: String, valueText: String, hint: String?, frac: Float) {
            y += 12 * d
            text(c, label, cardX + 18 * d, y + 9 * d, 16 * d, TEXT)
            text(c, valueText, cardX + cardW - 18 * d, y + 9 * d, 16 * d, ORANGE, true, 2)
            y += 20 * d
            if (hint != null) { text(c, hint, cardX + 18 * d, y + 7 * d, 12 * d, FAINT); y += 15 * d }
            y += 8 * d
            val tx = cardX + 18 * d; val tw = cardW - 36 * d; val cy = y + 18 * d
            c.drawRRect(RRect.makeXYWH(tx, cy - 3 * d, tw, 6 * d, 3 * d), fillP(argb(0.19f, 255, 255, 255)))
            c.drawRRect(RRect.makeXYWH(tx, cy - 3 * d, tw * frac, 6 * d, 3 * d), fillP(ORANGE))
            c.drawCircle(tx + tw * frac, cy, 13 * d, fillP(Color.WHITE))
            y += 36 * d + 12 * d
        }

        val camTop = groupStart("Camera")
        segment("Lens", null, listOf("Rear", "Front"), 0); divider()
        segment("Night handling", "Auto switches by itself as the room darkens", listOf("Auto", "Day", "Night"), 0); divider()
        stepper("Exposure", "0"); divider()
        toggle("Flashlight", null, false); divider()
        toggle("Lock exposure", "Stops auto-exposure steps being read as movement", false); divider()
        segment("Anti-flicker", "Match your mains frequency if you see banding", listOf("Auto", "50 Hz", "60 Hz", "Off"), 0)
        card(camTop, y)

        val detTop = groupStart("Detection")
        slider("Sensitivity", "50%", "Higher notices smaller, fainter movement", 0.49f); divider()
        stepper("Quiet time between alerts", "5s"); divider()
        stepper("Frames to confirm", "2"); divider()
        toggle("Ignore lighting changes", "Headlights and light switches", true); divider()
        stepper("Arm delay", "10s")
        card(detTop, y)

        val vidTop = groupStart("Video to your watch")
        segment("Quality", "Auto follows the connection", listOf("Auto", "Low", "Med", "High", "Max"), 0); divider()
        slider("Muddiness", "0", "Softer picture, smoother motion on a weak link", 0f)
        card(vidTop, y)
        mc.drawImage(layer.makeImageSnapshot(), 0f, 0f)
        png(s.makeImageSnapshot(), File(out, "mock_phone_settings.png"))
    }

    // ── watch: camera screen ─────────────────────────────────────────

    private fun watchCamera(effect: RuntimeEffect, out: File) {
        val S = 450; val d = 2.0f
        val D = S.toFloat()
        val bs = Surface.makeRasterN32Premul(S, S)
        val bc = bs.canvas
        bc.drawRect(Rect.makeWH(D, D), fillP(Color.BLACK))
        // PreviewMatrix: a portrait picture, height-pinned to 1.06 of the diameter, clipped by the round glass
        val ph = D * 1.06f
        val pw = ph * 0.75f
        val px = (D - pw) / 2; val py = (D - ph) / 2
        bc.save()
        bc.clipRRect(RRect.makeXYWH(0f, 0f, D, D, D / 2), ClipMode.INTERSECT, true)
        bc.drawImageRect(room(600, 800), Rect.makeWH(600f, 800f), Rect.makeXYWH(px, py, pw, ph), SamplingMode.LINEAR, null, true)
        bc.drawRect(Rect.makeXYWH(px + pw * 0.34f, py + ph * 0.34f, pw * 0.32f, ph * 0.32f), strokeP(argb(0.5f, 255, 255, 255), 1.2f * d))
        bc.restore()

        val ringT = 13 * d
        val chipH = 26 * d
        val name = "Porch"
        val chipW = (24 + 13 + 5) * d + textW(name, 12 * d, true)
        val chipX = (D - chipW) / 2; val chipY = 20 * d
        val btn = 42 * d
        val rowW = btn * 2 + 14 * d
        val bx = (D - rowW) / 2; val by = D - 22 * d - btn

        val lenses = listOf(
            Lens(0f, 0f, D, D, radius = D / 2, ring = true, ringThickness = ringT, bezel = 6 * d, scrim = 0.12f),
            Lens(chipX, chipY, chipX + chipW, chipY + chipH, radius = chipH / 2, bezel = 8 * d),
            Lens(bx, by, bx + btn, by + btn, radius = btn / 2, bezel = 12 * d, tint = 0.05f),
            Lens(bx + btn + 14 * d, by, bx + rowW, by + btn, radius = btn / 2, bezel = 12 * d, tint = 0.05f)
        )
        val glass = render(effect, bs.makeImageSnapshot(), S, S, lenses, look(d), quality = 2)
        val s = Surface.makeRasterN32Premul(S, S)
        val c = s.canvas
        c.drawImage(glass, 0f, 0f)

        // gauge: zoom 1.0x sits 23% of the way through its log range
        val stroke = 3 * d
        val inset = 6.5f * d + stroke / 2
        val ga = Rect.makeXYWH(inset, inset, D - 2 * inset, D - 2 * inset)
        c.drawArc(ga.left, ga.top, ga.right, ga.bottom, 150f, 240f, false, strokeP(argb(0.20f, 255, 255, 255), stroke))
        c.drawArc(ga.left, ga.top, ga.right, ga.bottom, 150f, 240f * 0.231f, false, strokeP(ORANGE, stroke))

        // chip content
        val cy = chipY + chipH / 2
        icon(c, Ic.Wifi, chipX + 12 * d + 6.5f * d, cy, 13 * d, GREEN)
        text(c, name, chipX + (12 + 13 + 5) * d, cy, 12 * d, TEXT, true)

        // bezel readout in the middle
        val msg = "Zoom 1.0x · 1x lens"
        val mw = textW(msg, 14 * d, true) + 24 * d
        c.drawRRect(RRect.makeXYWH((D - mw) / 2, D / 2 - 17 * d, mw, 34 * d, 14 * d), fillP(Color.makeARGB(0xAA, 0, 0, 0)))
        text(c, msg, D / 2, D / 2, 14 * d, TEXT, true, 1)

        // buttons
        icon(c, Ic.Shield, bx + btn / 2, by + btn / 2, 20 * d, TEXT)
        icon(c, Ic.List, bx + btn + 14 * d + btn / 2, by + btn / 2, 20 * d, TEXT)

        roundOut(s.makeImageSnapshot(), S, File(out, "mock_watch_camera.png"))
    }

    // ── watch: settings list ─────────────────────────────────────────

    private fun watchSettings(out: File) {
        val S = 450; val d = 2.0f
        val D = S.toFloat()
        val s = Surface.makeRasterN32Premul(S, S)
        val c = s.canvas
        c.drawRect(Rect.makeWH(D, D), fillP(Color.BLACK))

        class Item(val h: Float, val draw: (Canvas, Float, Float, Float) -> Unit)  // height, then (canvas, cy, scale, alpha)
        fun shell(cc: Canvas, cy: Float, sc: Float, a: Float, hDp: Float): Pair<Float, Float> {
            val w = D * 0.9f * sc; val h = hDp * d * sc
            cc.drawRRect(RRect.makeXYWH((D - w) / 2, cy - h / 2, w, h, 18 * d * sc), fillP(argb(0.15f * a, 255, 255, 255)))
            return (D - w) / 2 to w
        }
        val items = mutableListOf<Item>()
        items += Item(38 * d) { cc, cy, sc, a -> text(cc, "Porch", D / 2, cy + 6 * d * sc, 15 * d * sc, argb(a, 245, 245, 247), true, 1) }
        items += Item(20 * d) { cc, cy, sc, a -> text(cc, "DETECTION", D / 2 - 90 * d * sc, cy + 4 * d * sc, 10 * d * sc, argb(0.5f * a, 245, 245, 247), true) }
        fun stepper(label: String, value: String) = items.add(Item(44 * d) { cc, cy, sc, a ->
            val (x0, w) = shell(cc, cy, sc, a, 44f)
            text(cc, label, x0 + 12 * d * sc, cy, 12 * d * sc, argb(a, 245, 245, 247))
            val r = x0 + w - 12 * d * sc
            cc.drawCircle(r - 14 * d * sc, cy, 14 * d * sc, fillP(argb(0.2f * a, 255, 255, 255)))
            icon(cc, Ic.Plus, r - 14 * d * sc, cy, 14 * d * sc, argb(a, 245, 245, 247))
            text(cc, value, r - 28 * d * sc - 22 * d * sc, cy, 12 * d * sc, argb(a, 0xFF, 0x7A, 0x2F), true, 1)
            cc.drawCircle(r - 28 * d * sc - 44 * d * sc - 14 * d * sc, cy, 14 * d * sc, fillP(argb(0.2f * a, 255, 255, 255)))
            icon(cc, Ic.Minus, r - 28 * d * sc - 44 * d * sc - 14 * d * sc, cy, 14 * d * sc, argb(a, 245, 245, 247))
        })
        fun toggle(label: String, on: Boolean) = items.add(Item(36 * d) { cc, cy, sc, a ->
            val (x0, w) = shell(cc, cy, sc, a, 36f)
            text(cc, label, x0 + 12 * d * sc, cy, 12 * d * sc, argb(a, 245, 245, 247))
            val tw = 34 * d * sc; val th = 20 * d * sc; val tx = x0 + w - 12 * d * sc - tw
            cc.drawRRect(RRect.makeXYWH(tx, cy - th / 2, tw, th, th / 2), fillP(if (on) argb(0.85f * a, 0x34, 0xD2, 0x7A) else argb(0.25f * a, 255, 255, 255)))
            cc.drawCircle(if (on) tx + tw - th / 2 else tx + th / 2, cy, 8 * d * sc, fillP(argb(a, 255, 255, 255)))
        })
        stepper("Sensitivity", "50%"); stepper("Alert gap", "5s"); stepper("Confirm frames", "2"); stepper("Arm delay", "10s")
        toggle("Ignore light changes", true); toggle("Tamper alert", true)

        // ScalingLazyColumn: item index 1 (the section label) is centred; items shrink and fade toward the edge
        val gap = 5 * d
        val centre = D / 2
        val ys = FloatArray(items.size)
        ys[1] = centre
        for (i in 2 until items.size) ys[i] = ys[i - 1] + items[i - 1].h / 2 + gap + items[i].h / 2
        ys[0] = ys[1] - items[1].h / 2 - gap - items[0].h / 2
        c.save()
        c.clipRRect(RRect.makeXYWH(0f, 0f, D, D, D / 2), ClipMode.INTERSECT, true)
        for ((i, it) in items.withIndex()) {
            val t = (kotlin.math.abs(ys[i] - centre) / (D / 2)).coerceIn(0f, 1.2f)
            val sc = (1f - 0.35f * t * t).coerceAtLeast(0.55f)
            val a = (1f - 0.55f * t * t).coerceIn(0.25f, 1f)
            it.draw(c, ys[i], sc, a)
        }
        c.restore()
        roundOut(s.makeImageSnapshot(), S, File(out, "mock_watch_settings.png"))
    }

    /** The watch face is round: mask to the circle and enlarge 2x so the details can be seen. */
    private fun roundOut(img: Image, size: Int, f: File) {
        val big = size * 2
        val s = Surface.makeRasterN32Premul(big, big)
        s.canvas.drawRect(Rect.makeWH(big.toFloat(), big.toFloat()), fillP(Color.makeRGB(0x16, 0x16, 0x1a)))
        s.canvas.save()
        s.canvas.clipRRect(RRect.makeXYWH(0f, 0f, big.toFloat(), big.toFloat(), big / 2f), ClipMode.INTERSECT, true)
        s.canvas.drawImageRect(img, Rect.makeWH(size.toFloat(), size.toFloat()), Rect.makeWH(big.toFloat(), big.toFloat()), SamplingMode.LINEAR, null, true)
        s.canvas.restore()
        png(s.makeImageSnapshot(), f)
    }
}
