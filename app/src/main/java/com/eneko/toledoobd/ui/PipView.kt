package com.eneko.toledoobd.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eneko.toledoobd.ConnState
import com.eneko.toledoobd.DashState
import com.eneko.toledoobd.data.FloatMode
import com.eneko.toledoobd.data.Settings
import com.eneko.toledoobd.ui.components.consumptionColor
import com.eneko.toledoobd.ui.theme.Dash
import com.eneko.toledoobd.ui.theme.Orbitron
import com.eneko.toledoobd.ui.theme.Rajdhani
import java.util.Locale

/**
 * Ventana flotante (imagen en imagen) para tener el consumo a la vista encima de Google Maps.
 * El tamaño de letra se calcula con la altura de la ventana, que es pequeña y la elige Android.
 */
@Composable
fun PipView(s: DashState, settings: Settings) {
    val v = settings.vehicle
    val moving = s.instL100 != null
    val inst by animateFloatAsState(s.instL100 ?: s.instLph, tween(300), label = "pipInst")
    val avg = s.trip.avgL100
    val connected = s.conn is ConnState.Connected && s.ecuResponding

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Dash.BgTop, Dash.Bg))),
    ) {
        // Letra según alto y ancho de cada columna, para que "10,5" quepa en ventanas estrechas.
        val colW = if (settings.floatMode == FloatMode.BOTH) maxWidth / 2 else maxWidth
        val h = minOf(maxHeight, colW * 0.72f)
        // Línea roja arriba, como acento del panel
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, Dash.Red, Color.Transparent))),
        )
        if (!connected) {
            Text(
                "SIN OBD",
                modifier = Modifier.align(Alignment.Center),
                style = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = sp(h, 0.22f), color = Dash.TextDim),
            )
            return@BoxWithConstraints
        }
        Row(
            Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Value(
                title = if (moving) "AHORA" else "PARADO",
                value = String.format(Locale.getDefault(), "%.1f", inst),
                unit = if (moving) "L/100" else "L/h",
                color = if (moving) consumptionColor(inst, v.ecoGood, v.ecoBad) else Dash.Text,
                h = h,
                modifier = Modifier.weight(1f),
            )
            if (settings.floatMode == FloatMode.BOTH) {
                Box(Modifier.width(1.dp).fillMaxHeight(0.7f).background(Dash.Stroke))
                Value(
                    title = "MEDIA",
                    value = avg?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "--",
                    unit = "L/100",
                    color = avg?.let { consumptionColor(it, v.ecoGood, v.ecoBad) } ?: Dash.Text,
                    h = h,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Value(title: String, value: String, unit: String, color: Color, h: Dp, modifier: Modifier) {
    // Dos líneas: "AHORA · L/100" y el número grande, sin huecos de sobra.
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(
            "$title · $unit",
            maxLines = 1,
            style = TextStyle(
                fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = sp(h, 0.15f),
                color = Dash.TextDim, letterSpacing = 1.sp,
            ),
        )
        Text(
            value, maxLines = 1,
            style = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Black, fontSize = sp(h, 0.48f), color = color),
        )
    }
}

/** Tamaño de letra proporcional a la altura de la ventana (la letra no sigue la escala del sistema). */
private fun sp(h: Dp, k: Float) = (h.value * k).sp
