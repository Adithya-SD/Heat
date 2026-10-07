package app.heat.glass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Colour and type tokens shared by the phone and the watch. */
object Heat {
    val Ink = Color(0xFF060608)
    val Panel = Color(0xFF111116)
    val Orange = Color(0xFFFF7A2F)
    val OrangeSoft = Color(0xFFFFA05E)
    val Green = Color(0xFF34D27A)
    val Red = Color(0xFFFF4D4D)
    val Amber = Color(0xFFFFC247)
    val Text = Color(0xFFF5F5F7)
    val TextDim = Color(0xB8F5F5F7)
    val TextFaint = Color(0x80F5F5F7)
    val Hairline = Color(0x26FFFFFF)
}

enum class HeatIcon { Bolt, Flip, Gear, Shield, Back, Close, Check, Eye, Plus, Minus, Camera, Alert, Wifi, Bluetooth, Aim, List, Chevron }

/**
 * Icons drawn from primitives on a 24-unit grid, so the apps carry no icon
 * library (the earlier app shipped tens of megabytes of vector assets for a
 * dozen glyphs) and every icon is crisp at any size.
 */
@Composable
fun HeatIconView(icon: HeatIcon, tint: Color = Heat.Text, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        drawIcon(icon, tint, u)
    }
}

private fun DrawScope.drawIcon(icon: HeatIcon, c: Color, u: Float) {
    val stroke = Stroke(width = 1.9f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    fun poly(vararg pts: Pair<Float, Float>, close: Boolean = false, fill: Boolean = false) {
        val path = Path()
        pts.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x * u, y * u) else path.lineTo(x * u, y * u) }
        if (close) path.close()
        drawPath(path, c, style = if (fill) Fill else stroke)
    }
    when (icon) {
        HeatIcon.Bolt -> poly(13f to 2f, 5f to 13.5f, 11f to 13.5f, 10f to 22f, 19f to 10f, 13f to 10f, close = true, fill = true)
        HeatIcon.Flip -> {
            drawArc(c, 200f, 140f, false, p(4.5f, 4.5f), Size(15f * u, 15f * u), style = stroke)
            drawArc(c, 20f, 140f, false, p(4.5f, 4.5f), Size(15f * u, 15f * u), style = stroke)
            poly(17.5f to 3f, 17.5f to 7.5f, 13f to 7.5f)
            poly(6.5f to 21f, 6.5f to 16.5f, 11f to 16.5f)
        }
        HeatIcon.Gear -> {
            drawCircle(c, 3.2f * u, p(12f, 12f), style = stroke)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val (cx, sx) = Math.cos(a).toFloat() to Math.sin(a).toFloat()
                drawLine(c, p(12f + 6.2f * cx, 12f + 6.2f * sx), p(12f + 9f * cx, 12f + 9f * sx), strokeWidth = 2.6f * u, cap = StrokeCap.Round)
            }
            drawCircle(c, 6.2f * u, p(12f, 12f), style = stroke)
        }
        HeatIcon.Shield -> {
            val path = Path().apply {
                moveTo(12 * u, 2.5f * u); lineTo(19.5f * u, 5.5f * u); lineTo(19.5f * u, 11.5f * u)
                cubicTo(19.5f * u, 16.5f * u, 16f * u, 20f * u, 12 * u, 21.5f * u)
                cubicTo(8f * u, 20f * u, 4.5f * u, 16.5f * u, 4.5f * u, 11.5f * u)
                lineTo(4.5f * u, 5.5f * u); close()
            }
            drawPath(path, c, style = stroke)
        }
        HeatIcon.Back -> poly(15f to 5f, 8f to 12f, 15f to 19f)
        HeatIcon.Chevron -> poly(9f to 5f, 16f to 12f, 9f to 19f)
        HeatIcon.Close -> { poly(6f to 6f, 18f to 18f); poly(18f to 6f, 6f to 18f) }
        HeatIcon.Check -> poly(5f to 12.5f, 10f to 17.5f, 19f to 7f)
        HeatIcon.Plus -> { poly(12f to 5f, 12f to 19f); poly(5f to 12f, 19f to 12f) }
        HeatIcon.Minus -> poly(5f to 12f, 19f to 12f)
        HeatIcon.Eye -> {
            drawOval(c, p(2.5f, 6.5f), Size(19f * u, 11f * u), style = stroke)
            drawCircle(c, 2.8f * u, p(12f, 12f), style = Fill)
        }
        HeatIcon.Camera -> {
            drawRoundRect(c, p(3f, 7f), Size(18f * u, 13f * u), androidx.compose.ui.geometry.CornerRadius(3f * u), style = stroke)
            drawCircle(c, 3.6f * u, p(12f, 13.5f), style = stroke)
            poly(8.5f to 7f, 10f to 4f, 14f to 4f, 15.5f to 7f)
        }
        HeatIcon.Alert -> {
            poly(12f to 3.5f, 21f to 19.5f, 3f to 19.5f, close = true)
            poly(12f to 9.5f, 12f to 14f)
            drawCircle(c, 1.1f * u, p(12f, 17f), style = Fill)
        }
        HeatIcon.Wifi -> {
            drawArc(c, 225f, 90f, false, p(2.5f, 5f), Size(19f * u, 19f * u), style = stroke)
            drawArc(c, 225f, 90f, false, p(6f, 8.5f), Size(12f * u, 12f * u), style = stroke)
            drawCircle(c, 1.4f * u, p(12f, 18f), style = Fill)
        }
        HeatIcon.Bluetooth -> poly(7f to 7.5f, 17f to 16.5f, 12f to 21f, 12f to 3f, 17f to 7.5f, 7f to 16.5f)
        HeatIcon.Aim -> {
            drawCircle(c, 6.5f * u, p(12f, 12f), style = stroke)
            poly(12f to 2f, 12f to 6f); poly(12f to 18f, 12f to 22f); poly(2f to 12f, 6f to 12f); poly(18f to 12f, 22f to 12f)
        }
        HeatIcon.List -> { poly(8f to 6f, 20f to 6f); poly(8f to 12f, 20f to 12f); poly(8f to 18f, 20f to 18f); drawCircle(c, 1.2f * u, p(4f, 6f)); drawCircle(c, 1.2f * u, p(4f, 12f)); drawCircle(c, 1.2f * u, p(4f, 18f)) }
    }
}

@Composable
fun HeatText(
    text: String,
    modifier: Modifier = Modifier,
    size: Int = 16,
    weight: FontWeight = FontWeight.Medium,
    color: Color = Heat.Text,
    align: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE
) {
    BasicText(
        text,
        modifier,
        style = TextStyle(color = color, fontSize = size.sp, fontWeight = weight, textAlign = align, letterSpacing = (-0.1).sp),
        maxLines = maxLines
    )
}
