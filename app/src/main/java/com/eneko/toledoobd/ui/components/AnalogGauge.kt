package com.eneko.toledoobd.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import com.eneko.toledoobd.ui.theme.Dash
import com.eneko.toledoobd.ui.theme.Orbitron
import com.eneko.toledoobd.ui.theme.Rajdhani
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val START = 135f
private const val SWEEP = 270f

/**
 * Reloj analógico con aguja roja, arco de progreso luminoso y zona roja.
 *
 * @param fraction posición de la aguja (0..1), ya animada.
 * @param reveal   porción de la escala iluminada (0..1) para la animación de arranque.
 */
@Composable
fun AnalogGauge(
    fraction: Float,
    reveal: Float,
    max: Float,
    majorStep: Float,
    minorPerMajor: Int,
    redFrom: Float?,
    tickLabel: (Float) -> String,
    title: String,
    readout: String,
    unit: String,
    alarm: Boolean,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(420, easing = LinearEasing), RepeatMode.Reverse),
        label = "redPulse",
    )
    val labels = remember(max, majorStep) {
        val n = (max / majorStep).roundToInt()
        (0..n).map { it * majorStep }
    }

    Canvas(modifier.aspectRatio(1f)) {
        val r = size.minDimension / 2f
        val c = center
        val f = fraction.coerceIn(0f, 1.02f)

        // Halo rojo exterior
        drawCircle(
            brush = Brush.radialGradient(
                0.78f to Color.Transparent,
                0.92f to Dash.Red.copy(alpha = if (alarm) 0.10f + 0.18f * pulse else 0.07f),
                1f to Color.Transparent,
                center = c, radius = r,
            ),
            radius = r,
        )
        // Bisel metálico
        drawCircle(
            brush = Brush.sweepGradient(
                listOf(
                    Color(0xFF4B4F58), Color(0xFF14161A), Color(0xFF5A5F69),
                    Color(0xFF14161A), Color(0xFF3E424A), Color(0xFF14161A), Color(0xFF4B4F58),
                ),
                center = c,
            ),
            radius = r * 0.955f,
        )
        // Esfera
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0xFF1D2028), Color(0xFF101217), Color(0xFF07080A)),
                center = c, radius = r * 0.93f,
            ),
            radius = r * 0.93f,
        )
        drawCircle(Color.White.copy(alpha = 0.05f), r * 0.93f, style = Stroke(r * 0.006f))
        drawCircle(Color.White.copy(alpha = 0.035f), r * 0.50f, style = Stroke(r * 0.004f))

        // Pista del arco
        val arcR = r * 0.86f
        val arcW = r * 0.032f
        drawArcAt(c, arcR, START, SWEEP, Color.White.copy(alpha = 0.06f), arcW)

        // Zona roja
        if (redFrom != null && redFrom < max) {
            val a0 = START + SWEEP * (redFrom / max)
            val zoneAlpha = if (alarm) 0.45f + 0.55f * pulse else 0.75f
            drawArcAt(c, arcR, a0, START + SWEEP - a0, Dash.Red.copy(alpha = zoneAlpha * reveal), arcW * 1.6f)
        }

        // Arco de progreso con brillo
        if (f > 0.002f) {
            val sweep = SWEEP * f.coerceAtMost(1f)
            drawArcAt(c, arcR, START, sweep, Dash.Red.copy(alpha = 0.06f), arcW * 5f)
            drawArcAt(c, arcR, START, sweep, Dash.Red.copy(alpha = 0.12f), arcW * 2.8f)
            rotate(START, pivot = c) {
                drawArc(
                    brush = Brush.sweepGradient(
                        0f to Dash.RedDeep.copy(alpha = 0.15f),
                        (sweep / 360f) to Dash.Red,
                        1f to Dash.Red,
                        center = c,
                    ),
                    startAngle = 0f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(c.x - arcR, c.y - arcR),
                    size = Size(arcR * 2, arcR * 2),
                    style = Stroke(arcW, cap = StrokeCap.Round),
                )
            }
        }

        // Marcas
        val n = (max / majorStep * minorPerMajor).roundToInt()
        for (i in 0..n) {
            val v = i * majorStep / minorPerMajor
            val vf = v / max
            val major = i % minorPerMajor == 0
            val lit = vf <= reveal + 0.001f
            val inRed = redFrom != null && v >= redFrom
            val base = when {
                inRed -> Dash.Red
                major -> Dash.Text
                else -> Dash.TextDim
            }
            val color = base.copy(alpha = if (lit) (if (major) 1f else 0.8f) else 0.1f)
            val ang = Math.toRadians((START + SWEEP * vf).toDouble())
            val outer = r * 0.80f
            val inner = if (major) r * 0.69f else r * 0.755f
            val cs = cos(ang).toFloat()
            val sn = sin(ang).toFloat()
            drawLine(
                color,
                Offset(c.x + cs * inner, c.y + sn * inner),
                Offset(c.x + cs * outer, c.y + sn * outer),
                strokeWidth = if (major) r * 0.018f else r * 0.007f,
                cap = StrokeCap.Round,
            )
        }

        // Números
        val numStyle = TextStyle(
            fontFamily = Orbitron, fontWeight = FontWeight.Bold,
            fontSize = (r * (if (labels.size > 8) 0.072f else 0.095f)).toSp(),
        )
        for (v in labels) {
            val vf = v / max
            val lit = vf <= reveal + 0.001f
            val inRed = redFrom != null && v >= redFrom
            val ang = Math.toRadians((START + SWEEP * vf).toDouble())
            val pr = r * (if (labels.size > 8) 0.575f else 0.56f)
            val color = (if (inRed) Dash.Red else Dash.Text).copy(alpha = if (lit) 0.95f else 0.12f)
            drawCentered(
                measurer, tickLabel(v), numStyle.copy(color = color),
                Offset(c.x + cos(ang).toFloat() * pr, c.y + sin(ang).toFloat() * pr),
            )
        }

        // Rótulo y lectura digital
        drawCentered(
            measurer, title,
            TextStyle(
                fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold,
                fontSize = (r * 0.085f).toSp(), color = Dash.TextDim, letterSpacing = (r * 0.012f).toSp(),
            ),
            Offset(c.x, c.y - r * 0.28f),
        )
        drawCentered(
            measurer, readout,
            TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Black, fontSize = (r * 0.155f).toSp(), color = Dash.Text),
            Offset(c.x, c.y + r * 0.47f),
        )
        drawLine(
            brush = Brush.horizontalGradient(
                listOf(Color.Transparent, Dash.Red, Color.Transparent),
                startX = c.x - r * 0.2f, endX = c.x + r * 0.2f,
            ),
            start = Offset(c.x - r * 0.2f, c.y + r * 0.585f),
            end = Offset(c.x + r * 0.2f, c.y + r * 0.585f),
            strokeWidth = r * 0.008f,
        )
        drawCentered(
            measurer, unit,
            TextStyle(
                fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold,
                fontSize = (r * 0.08f).toSp(), color = Dash.TextDim, letterSpacing = (r * 0.01f).toSp(),
            ),
            Offset(c.x, c.y + r * 0.67f),
        )

        // Aguja
        val angle = START + SWEEP * f
        translate(r * 0.012f, r * 0.022f) {
            rotate(angle, pivot = c) { drawPath(needlePath(c, r), Color.Black.copy(alpha = 0.5f)) }
        }
        rotate(angle, pivot = c) {
            val p = needlePath(c, r)
            drawPath(p, Dash.Red.copy(alpha = 0.16f), style = Stroke(r * 0.06f, join = StrokeJoin.Round))
            drawPath(p, Dash.Red.copy(alpha = 0.30f), style = Stroke(r * 0.025f, join = StrokeJoin.Round))
            drawPath(
                p,
                Brush.horizontalGradient(
                    0f to Dash.RedDeep, 0.35f to Dash.Red, 1f to Dash.RedSoft,
                    startX = c.x - r * 0.17f, endX = c.x + r * 0.80f,
                ),
            )
            drawLine(
                Color.White.copy(alpha = 0.45f),
                Offset(c.x + r * 0.05f, c.y), Offset(c.x + r * 0.74f, c.y),
                strokeWidth = r * 0.0045f, cap = StrokeCap.Round,
            )
        }

        // Tapa central
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFF6A6F79), Color(0xFF23262D), Color(0xFF0E0F12)), center = c - Offset(r * 0.02f, r * 0.02f), radius = r * 0.09f),
            radius = r * 0.078f, center = c,
        )
        drawCircle(Dash.Red, radius = r * 0.078f, center = c, style = Stroke(r * 0.012f))
        drawCircle(Color(0xFF0B0B0D), radius = r * 0.03f, center = c)
    }
}

private fun needlePath(c: Offset, r: Float): Path {
    val tail = c.x - r * 0.17f
    val tip = c.x + r * 0.80f
    val hw = r * 0.03f
    return Path().apply {
        moveTo(tail, c.y - hw * 0.65f)
        lineTo(c.x, c.y - hw)
        lineTo(tip, c.y - r * 0.005f)
        lineTo(tip + r * 0.01f, c.y)
        lineTo(tip, c.y + r * 0.005f)
        lineTo(c.x, c.y + hw)
        lineTo(tail, c.y + hw * 0.65f)
        close()
    }
}

internal fun DrawScope.drawArcAt(c: Offset, radius: Float, start: Float, sweep: Float, color: Color, width: Float) {
    drawArc(
        color = color,
        startAngle = start,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(c.x - radius, c.y - radius),
        size = Size(radius * 2, radius * 2),
        style = Stroke(width, cap = StrokeCap.Round),
    )
}

internal fun DrawScope.drawCentered(measurer: TextMeasurer, text: String, style: TextStyle, at: Offset) {
    val layout = measurer.measure(text, style)
    drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f))
}
