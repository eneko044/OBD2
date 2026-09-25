package com.eneko.toledoobd.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eneko.toledoobd.ui.theme.Dash
import com.eneko.toledoobd.ui.theme.Orbitron
import com.eneko.toledoobd.ui.theme.Rajdhani
import kotlin.math.cos
import kotlin.math.sin

val PanelShape = RoundedCornerShape(18.dp)

/** Fondo del salpicadero: degradado oscuro con textura de fibra y brillo rojo. */
fun Modifier.dashBackground(): Modifier = this.drawBehind {
    drawRect(Brush.verticalGradient(listOf(Dash.BgTop, Dash.Bg, Color(0xFF040506))))
    drawRect(
        Brush.radialGradient(
            listOf(Dash.Red.copy(alpha = 0.10f), Color.Transparent),
            center = Offset(size.width / 2f, 0f), radius = size.maxDimension * 0.6f,
        ),
    )
    val step = 7.dp.toPx()
    var x = -size.height
    while (x < size.width) {
        drawLine(
            Color.White.copy(alpha = 0.018f),
            Offset(x, 0f), Offset(x + size.height, size.height), strokeWidth = 1f,
        )
        x += step
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .background(
                Brush.verticalGradient(listOf(Dash.PanelHi.copy(alpha = 0.92f), Dash.Panel.copy(alpha = 0.92f))),
                PanelShape,
            )
            .border(
                1.dp,
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.02f))),
                PanelShape,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        content = content,
    )
}

@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color = Dash.TextDim) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = TextStyle(
            fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp, letterSpacing = 1.6.sp, color = color,
        ),
    )
}

@Composable
fun BigNumber(text: String, size: Int, modifier: Modifier = Modifier, color: Color = Dash.Text) {
    Text(
        text,
        modifier = modifier,
        style = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Black, fontSize = size.sp, color = color),
    )
}

@Composable
fun UnitText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Dash.TextDim),
    )
}

/** Color del consumo: verde eficiente, ámbar normal, rojo alto. */
fun consumptionColor(l100: Float): Color = when {
    l100 < 5.5f -> Dash.Green
    l100 < 9f -> Dash.Amber
    else -> Dash.Red
}

/** Barra horizontal de consumo con degradado y marcador de la media. */
@Composable
fun ConsumptionBar(value: Float, maxScale: Float, average: Float?, modifier: Modifier = Modifier) {
    val v by animateFloatAsState(
        (value / maxScale).coerceIn(0f, 1f),
        spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow), label = "bar",
    )
    val avg by animateFloatAsState(((average ?: 0f) / maxScale).coerceIn(0f, 1f), tween(700), label = "avg")
    Canvas(modifier.fillMaxWidth().height(22.dp)) {
        val h = 10.dp.toPx()
        val top = (size.height - h) / 2f
        val cr = CornerRadius(h / 2, h / 2)
        drawRoundRect(Color.White.copy(alpha = 0.06f), Offset(0f, top), Size(size.width, h), cr)
        val grad = Brush.horizontalGradient(
            0f to Dash.Green, 0.30f to Dash.Green, 0.48f to Dash.Amber, 0.75f to Dash.Red, 1f to Dash.Red,
            startX = 0f, endX = size.width,
        )
        clipRect(right = size.width * v) {
            drawRoundRect(grad, Offset(0f, top - 3.dp.toPx()), Size(size.width, h + 6.dp.toPx()), cr, alpha = 0.18f)
            drawRoundRect(grad, Offset(0f, top), Size(size.width, h), cr)
        }
        // divisiones cada 5 L
        var t = 5f
        while (t < maxScale) {
            val x = size.width * t / maxScale
            drawLine(Dash.Bg.copy(alpha = 0.8f), Offset(x, top), Offset(x, top + h), 2.dp.toPx())
            t += 5f
        }
        if (average != null) {
            val x = size.width * avg
            val p = Path().apply {
                moveTo(x, top - 1.dp.toPx())
                lineTo(x - 5.dp.toPx(), top - 8.dp.toPx())
                lineTo(x + 5.dp.toPx(), top - 8.dp.toPx())
                close()
            }
            drawPath(p, Color.White)
            drawLine(Color.White, Offset(x, top), Offset(x, top + h), 2.dp.toPx())
        }
    }
}

/** Gráfica de los últimos minutos de consumo instantáneo. */
@Composable
fun Sparkline(points: List<Float>, capacity: Int, maxY: Float, average: Float?, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth()) {
        val w = size.width
        val h = size.height
        val labelStyle = TextStyle(fontFamily = Rajdhani, fontSize = 10.sp, color = Dash.TextFaint)
        for (g in listOf(5f, 10f, 15f)) {
            if (g >= maxY) continue
            val y = h - h * g / maxY
            drawLine(Color.White.copy(alpha = 0.05f), Offset(0f, y), Offset(w, y), 1f)
            drawCentered(measurer, g.toInt().toString(), labelStyle, Offset(10.dp.toPx(), y - 7.dp.toPx()))
        }
        if (average != null) {
            val y = h - h * (average / maxY).coerceIn(0f, 1f)
            drawLine(Color.White.copy(alpha = 0.35f), Offset(0f, y), Offset(w, y), 1.dp.toPx())
        }
        if (points.size < 2) return@Canvas
        val dx = w / (capacity - 1)
        val offset = capacity - points.size
        val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
        // Recorre tramos continuos (NaN = coche parado)
        var i = 0
        while (i < points.size) {
            if (points[i].isNaN()) {
                i++
                continue
            }
            val line = Path()
            val fill = Path()
            val startX = (offset + i) * dx
            var lastX = startX
            var first = true
            while (i < points.size && !points[i].isNaN()) {
                val x = (offset + i) * dx
                val y = h - h * (points[i] / maxY).coerceIn(0f, 1f)
                if (first) {
                    line.moveTo(x, y)
                    fill.moveTo(x, h)
                    fill.lineTo(x, y)
                    first = false
                } else {
                    line.lineTo(x, y)
                    fill.lineTo(x, y)
                }
                lastX = x
                i++
            }
            fill.lineTo(lastX, h)
            fill.close()
            drawPath(fill, Brush.verticalGradient(listOf(Dash.Red.copy(alpha = 0.35f), Color.Transparent)))
            drawPath(line, Dash.Red.copy(alpha = 0.25f), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
            drawPath(line, Dash.RedSoft, style = stroke)
        }
        val last = points.last()
        if (!last.isNaN()) {
            val x = (capacity - 1) * dx
            val y = h - h * (last / maxY).coerceIn(0f, 1f)
            drawCircle(Dash.Red.copy(alpha = 0.3f), 7.dp.toPx(), Offset(x - 3.dp.toPx(), y))
            drawCircle(Color.White, 3.dp.toPx(), Offset(x - 3.dp.toPx(), y))
        }
    }
}

/** Pequeño reloj de arco (temperatura, turbo...). */
@Composable
fun ArcMeter(
    value: Float?,
    min: Float,
    max: Float,
    label: String,
    valueText: String,
    unit: String,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    size: Dp = 92.dp,
) {
    val frac by animateFloatAsState(
        value?.let { ((it - min) / (max - min)).coerceIn(0f, 1f) } ?: 0f,
        spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessVeryLow), label = "arc",
    )
    val measurer = rememberTextMeasurer()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(size).aspectRatio(1f)) {
            val r = this.size.minDimension / 2f
            val c = center
            val start = 150f
            val sweep = 240f
            val arcR = r * 0.82f
            val w = r * 0.12f
            drawArcAt(c, arcR, start, sweep, Color.White.copy(alpha = 0.07f), w)
            if (value != null) {
                // Degradado a lo largo del arco
                val stops = colors.mapIndexed { i, col -> (i.toFloat() / (colors.size - 1)) * sweep / 360f to col }
                    .toMutableList().apply { add(1f to colors.last()) }
                rotate(start, pivot = c) {
                    drawArc(
                        brush = Brush.sweepGradient(*stops.toTypedArray(), center = c),
                        startAngle = 0f, sweepAngle = sweep * frac, useCenter = false,
                        topLeft = Offset(c.x - arcR, c.y - arcR), size = Size(arcR * 2, arcR * 2),
                        style = Stroke(w, cap = StrokeCap.Round),
                    )
                }
                val a = Math.toRadians((start + sweep * frac).toDouble())
                val p = Offset(c.x + cos(a).toFloat() * arcR, c.y + sin(a).toFloat() * arcR)
                drawCircle(Dash.Red.copy(alpha = 0.35f), w * 1.1f, p)
                drawCircle(Color.White, w * 0.5f, p)
            }
            drawCentered(
                measurer, if (value == null) "--" else valueText,
                TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = (r * 0.36f).toSp(), color = Dash.Text),
                Offset(c.x, c.y - r * 0.02f),
            )
            drawCentered(
                measurer, unit,
                TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = (r * 0.2f).toSp(), color = Dash.TextDim),
                Offset(c.x, c.y + r * 0.34f),
            )
        }
        Caption(label, Modifier.padding(top = 2.dp))
    }
}

/** Indicador de marcha con animación de cambio y aviso de subir marcha. */
@Composable
fun GearIndicator(gear: Int?, shiftUp: Boolean, modifier: Modifier = Modifier, size: Dp = 64.dp) {
    val blink by rememberInfiniteTransition(label = "shift").animateFloat(
        0.25f, 1f, infiniteRepeatable(tween(380), RepeatMode.Reverse), label = "blink",
    )
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(size)
                .drawBehind {
                    drawCircle(
                        Brush.radialGradient(listOf(Dash.Red.copy(alpha = 0.35f), Color.Transparent)),
                        radius = this.size.minDimension * 0.75f,
                    )
                }
                .background(Brush.radialGradient(listOf(Color(0xFF22252D), Color(0xFF0C0D10))), CircleShape)
                .border(2.dp, Brush.sweepGradient(listOf(Dash.Red, Dash.RedDeep, Dash.Red, Dash.RedDeep, Dash.Red)), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = gear,
                transitionSpec = {
                    val up = (targetState ?: 0) > (initialState ?: 0)
                    (slideInVertically(tween(260)) { if (up) it else -it } + fadeIn(tween(260))) togetherWith
                        (slideOutVertically(tween(260)) { if (up) -it else it } + fadeOut(tween(200)))
                },
                label = "gear",
            ) { g ->
                BigNumber(g?.toString() ?: "N", (size.value * 0.46f).toInt(), color = if (g == null) Dash.TextDim else Dash.Text)
            }
        }
        Text(
            "▲ SUBE",
            modifier = Modifier.padding(top = 4.dp).graphicsLayer { alpha = if (shiftUp) blink else 0f },
            style = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Dash.Red, letterSpacing = 1.5.sp),
        )
    }
}

@Composable
fun StatTile(label: String, value: String, unit: String, modifier: Modifier = Modifier, accent: Color = Dash.Text) {
    Column(modifier, horizontalAlignment = Alignment.Start) {
        Caption(label)
        Row(verticalAlignment = Alignment.Bottom) {
            BigNumber(value, 20, color = accent)
            Spacer(Modifier.width(4.dp))
            UnitText(unit, Modifier.padding(bottom = 2.dp))
        }
    }
}

@Composable
fun StatusDot(color: Color, pulsing: Boolean, modifier: Modifier = Modifier) {
    val p by rememberInfiniteTransition(label = "dot").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing)), label = "dotP",
    )
    Canvas(modifier.size(14.dp)) {
        if (pulsing) drawCircle(color.copy(alpha = (1f - p) * 0.5f), radius = size.minDimension / 2f * (0.4f + p * 0.6f))
        drawCircle(color, radius = size.minDimension * 0.22f)
    }
}

@Composable
fun SectionRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        content()
    }
}

@Composable
fun CenteredCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier = modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        style = TextStyle(fontFamily = Rajdhani, fontSize = 13.sp, color = Dash.TextDim),
    )
}
