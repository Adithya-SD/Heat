package app.heat.glass.preview

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val MAX = 8

/** One glass shape, in the same terms the Android side uses. */
private class Lens(
    val l: Float, val t: Float, val r: Float, val b: Float,
    val radius: Float = 0f,
    val ring: Boolean = false, val ringThickness: Float = 0f,
    val bezel: Float = 26f, val strength: Float = 1f, val tint: Float = 0.045f, val scrim: Float = 0.10f
)

private class Look(
    val ior: Float = 1.5f, val thick: Float = 34f, val dispersion: Float = 0.12f,
    val spec: Float = 1.0f, val blur: Float = 1.0f, val shadow: Float = 1f, val saturation: Float = 1.18f,
    val light: Pair<Float, Float> = (-0.55f to -0.83f)
)

fun main(args: Array<String>) {
    val shaderFile = File(args[0])
    val outDir = File(args[1]).also { it.mkdirs() }
    val sksl = shaderFile.readText()
    val effect = try {
        RuntimeEffect.makeForShader(sksl)
    } catch (e: Throwable) {
        System.err.println("SHADER DID NOT COMPILE:\n${e.message}")
        throw e
    }
    println("shader compiled OK (${sksl.length} chars)")

    val w = 960
    val h = 640
    val backdrop = renderBackdrop(w, h)

    val lenses = listOf(
        Lens(50f, 50f, 440f, 260f, radius = 64f),                                   // card
        Lens(500f, 60f, 700f, 260f, radius = 100f, bezel = 44f),                    // circle
        Lens(50f, 320f, 450f, 416f, radius = 48f, bezel = 30f),                     // capsule / pill
        Lens(760f, 60f, 904f, 204f, radius = 40f, bezel = 22f),                     // squircle button
        Lens(560f, 330f, 900f, 600f, ring = true, ringThickness = 56f, bezel = 28f, radius = 0f), // watch-style ring
        Lens(70f, 450f, 170f, 550f, radius = 30f, bezel = 20f)                      // small control
    )

    for (q in 0..2) {
        val img = render(effect, backdrop, w, h, lenses, Look(), quality = q)
        File(outDir, "glass_q$q.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
    // No-glass reference, to compare the backdrop with and without.
    File(outDir, "backdrop.png").writeBytes(backdrop.encodeToData(EncodedImageFormat.PNG)!!.bytes)

    // A close-up of the card's top-left corner and the ring, 3x.
    val close = render(effect, backdrop, w, h, lenses, Look(), quality = 2)
    zoom(close, 30, 30, 240, 160, 4).let { File(outDir, "zoom_corner.png").writeBytes(it) }
    zoom(close, 560, 330, 340, 270, 3).let { File(outDir, "zoom_ring.png").writeBytes(it) }
    println("wrote PNGs to $outDir")
}

private fun render(
    effect: RuntimeEffect, backdrop: Image, w: Int, h: Int, lenses: List<Lens>, look: Look, quality: Int
): Image {
    val builder = RuntimeShaderBuilder(effect)
    builder.child("content", backdrop.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR))
    builder.uniform("uSize", w.toFloat(), h.toFloat())
    builder.uniform("uCount", lenses.size.toFloat())

    val rect = FloatArray(MAX * 4)
    val shape = FloatArray(MAX * 4)
    val lookArr = FloatArray(MAX * 4)
    lenses.forEachIndexed { i, l ->
        rect[i * 4] = l.l; rect[i * 4 + 1] = l.t; rect[i * 4 + 2] = l.r; rect[i * 4 + 3] = l.b
        shape[i * 4] = l.radius; shape[i * 4 + 1] = if (l.ring) 1f else 0f; shape[i * 4 + 2] = l.ringThickness
        lookArr[i * 4] = l.bezel; lookArr[i * 4 + 1] = l.strength; lookArr[i * 4 + 2] = l.tint; lookArr[i * 4 + 3] = l.scrim
    }
    builder.uniform("uRect", rect)
    builder.uniform("uShape", shape)
    builder.uniform("uLook", lookArr)
    builder.uniform("uLight", look.light.first, look.light.second)
    builder.uniform("uIor", look.ior)
    builder.uniform("uThick", look.thick)
    builder.uniform("uDispersion", look.dispersion)
    builder.uniform("uSpec", look.spec)
    builder.uniform("uBlur", look.blur)
    builder.uniform("uQuality", quality.toFloat())
    builder.uniform("uShadow", look.shadow)
    builder.uniform("uSaturation", look.saturation)

    val surface = Surface.makeRasterN32Premul(w, h)
    surface.canvas.drawRect(Rect.makeWH(w.toFloat(), h.toFloat()), Paint().apply { shader = builder.makeShader() })
    return surface.makeImageSnapshot()
}

private fun zoom(img: Image, x: Int, y: Int, w: Int, h: Int, scale: Int): ByteArray {
    val surface = Surface.makeRasterN32Premul(w * scale, h * scale)
    surface.canvas.drawImageRect(
        img, Rect.makeXYWH(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat()),
        Rect.makeWH((w * scale).toFloat(), (h * scale).toFloat()), SamplingMode.LINEAR, null, true
    )
    return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
}

/** A busy backdrop on purpose: grid lines, photo-like colour fields, text and thin high-contrast shapes make refraction easy to judge. */
private fun renderBackdrop(w: Int, h: Int): Image {
    val surface = Surface.makeRasterN32Premul(w, h)
    val c: Canvas = surface.canvas

    // Sunset-ish gradient with a dark floor, like a camera pointed at a room at dusk (drawn row by row).
    val stops = listOf(0f to intArrayOf(24, 38, 92), 0.45f to intArrayOf(196, 92, 70), 0.62f to intArrayOf(250, 190, 90), 1f to intArrayOf(32, 30, 40))
    for (row in 0 until h) {
        val f = row / (h - 1f)
        val i = stops.indexOfLast { it.first <= f }.coerceAtMost(stops.size - 2)
        val (f0, c0) = stops[i]
        val (f1, c1) = stops[i + 1]
        val u = ((f - f0) / (f1 - f0)).coerceIn(0f, 1f)
        fun mix(k: Int) = (c0[k] + (c1[k] - c0[k]) * u).toInt()
        c.drawRect(Rect.makeXYWH(0f, row.toFloat(), w.toFloat(), 1f), Paint().apply { color = Color.makeRGB(mix(0), mix(1), mix(2)) })
    }

    // Fine grid: straight lines show bending immediately.
    val grid = Paint().apply { color = Color.makeARGB(110, 255, 255, 255); mode = PaintMode.STROKE; strokeWidth = 1.5f }
    var x = 0f
    while (x < w) { c.drawLine(x, 0f, x, h.toFloat(), grid); x += 40f }
    var y = 0f
    while (y < h) { c.drawLine(0f, y, w.toFloat(), y, grid); y += 40f }

    // Colour blobs.
    val blobs = listOf(
        Triple(250f to 150f, 90f, Color.makeRGB(40, 200, 140)),
        Triple(620f to 160f, 70f, Color.makeRGB(235, 60, 120)),
        Triple(240f to 370f, 55f, Color.makeRGB(60, 140, 255)),
        Triple(820f to 130f, 40f, Color.makeRGB(255, 230, 80)),
        Triple(730f to 460f, 120f, Color.makeRGB(255, 255, 255)),
        Triple(120f to 500f, 60f, Color.makeRGB(255, 120, 40))
    )
    for ((p, r, col) in blobs) c.drawCircle(p.first, p.second, r, Paint().apply { color = col })

    // A "photo" subject: a figure-ish silhouette and a window.
    c.drawRect(Rect.makeXYWH(300f, 440f, 120f, 150f), Paint().apply { color = Color.makeRGB(20, 20, 24) })
    c.drawCircle(360f, 410f, 36f, Paint().apply { color = Color.makeRGB(230, 190, 160) })
    c.drawRect(Rect.makeXYWH(640f, 380f, 220f, 140f), Paint().apply { color = Color.makeRGB(255, 245, 210) })
    c.drawLine(750f, 380f, 750f, 520f, Paint().apply { color = Color.makeRGB(60, 40, 30); strokeWidth = 6f })

    // Text: the thing glass UI has to stay readable over.
    val face = FontMgr.default.matchFamilyStyle("Segoe UI", FontStyle.BOLD) ?: FontMgr.default.matchFamilyStyle("Arial", FontStyle.BOLD)
    val font = Font(face, 34f)
    val text = Paint().apply { color = Color.makeRGB(255, 255, 255) }
    c.drawString("Heat  Living room  24 C", 70f, 130f, font, text)
    c.drawString("MOTION 09:41  ARMED", 70f, 372f, font, text)
    c.drawString("0123456789 ABCDEFG", 580f, 300f, Font(face, 24f), text)

    // Thin radial spokes: a worst case for dispersion fringes.
    val spokes = Paint().apply { color = Color.makeRGB(0, 0, 0); mode = PaintMode.STROKE; strokeWidth = 2f }
    for (i in 0 until 36) {
        val a = i * PI / 18
        c.drawLine(730f, 460f, (730 + 200 * cos(a)).toFloat(), (460 + 200 * sin(a)).toFloat(), spokes)
    }
    return surface.makeImageSnapshot()
}
