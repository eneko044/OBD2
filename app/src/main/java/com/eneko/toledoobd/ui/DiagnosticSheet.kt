package com.eneko.toledoobd.ui

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eneko.toledoobd.data.DiagState
import com.eneko.toledoobd.data.DiagStep
import com.eneko.toledoobd.data.Diagnostics
import com.eneko.toledoobd.data.Level
import com.eneko.toledoobd.data.LiveData
import com.eneko.toledoobd.ui.components.BigNumber
import com.eneko.toledoobd.ui.components.Caption
import com.eneko.toledoobd.ui.theme.Dash
import com.eneko.toledoobd.ui.theme.Rajdhani

private val body = TextStyle(fontFamily = Rajdhani, fontSize = 16.sp, color = Dash.Text)
private val dim = TextStyle(fontFamily = Rajdhani, fontSize = 14.sp, color = Dash.TextDim)

@Composable
fun DiagnosticSheet(
    diag: DiagState,
    live: LiveData,
    connected: Boolean,
    automatic: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Pitido al completar cada paso: así no hace falta mirar el móvil conduciendo.
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { tone?.release() } }
    LaunchedEffect(diag.step, diag.active) {
        if (diag.active && diag.step != DiagStep.WARM_UP) {
            tone?.startTone(if (diag.step == DiagStep.DONE) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_BEEP2, 400)
        }
    }

    DashSheet(onDismiss) {
        SheetTitle("Diagnóstico EGR · caudalímetro · termostato")

        if (!diag.active && diag.idleRatios.isEmpty()) {
            Text(
                "La app compara el aire que mide el caudalímetro con el que cabe en los cilindros según " +
                    "la presión del turbo. Al ralentí, si la EGR recircula gases, falta aire limpio; a plena " +
                    "carga la EGR siempre se cierra y así se comprueba si el caudalímetro mide bien.",
                style = dim,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Necesitas: el coche arrancado, la app conectada y una carretera donde acelerar a fondo " +
                    (if (automatic) "(desde unos 40 km/h)" else "en 3ª") + " con seguridad.",
                style = dim,
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onStart, enabled = connected, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Dash.Red, contentColor = Color.White),
            ) { Text("EMPEZAR DIAGNÓSTICO", fontFamily = Rajdhani, fontWeight = FontWeight.Bold) }
            if (!connected) Text("Conéctate primero al OBD (o usa el modo demo).", style = dim.copy(color = Dash.Amber))
            return@DashSheet
        }

        StepCard(
            number = 1, title = "Calentar el motor",
            state = stepState(diag, DiagStep.WARM_UP),
            instructions = "Conduce normal hasta que el motor pase de ${Diagnostics.WARM_C.toInt()} °C. " +
                "La EGR solo trabaja con el motor caliente.",
            extra = "Motor: ${diag.coolantNow?.toInt() ?: "--"} °C",
            progress = ((diag.coolantNow ?: 0f) / Diagnostics.WARM_C).coerceIn(0f, 1f),
        )
        StepCard(
            number = 2, title = "Ralentí parado",
            state = stepState(diag, DiagStep.IDLE),
            instructions = "Para el coche en un sitio seguro, " + (if (automatic) "palanca en P o N" else "punto muerto") +
                ", sin tocar el acelerador ni encender " +
                "cosas. Espera a que suene el pitido (unos 20 segundos).",
            extra = if (diag.step == DiagStep.IDLE) "Rpm ${live.rpm.toInt()} · velocidad ${live.speed.toInt()} km/h" else null,
            progress = diag.idleProgress,
        )
        StepCard(
            number = 3, title = if (automatic) "Acelerón a fondo" else "Acelerón a fondo en 3ª",
            state = stepState(diag, DiagStep.ROAD),
            instructions = "En una carretera despejada y respetando el límite: " +
                (
                    if (automatic) {
                        "circulando a unos 40 km/h en D, pisa el acelerador A FONDO 3-4 segundos (la caja reducirá sola)."
                    } else {
                        "mete 3ª a unas 1500 rpm y pisa el acelerador A FONDO hasta ~3500 rpm."
                    }
                    ) +
                " Repite 2 o 3 veces si hace falta, hasta que suene el pitido. No mires el móvil: la app avisa sola.",
            extra = if (diag.step == DiagStep.ROAD) {
                "Carga ${live.load?.toInt() ?: "--"} % · turbo ${live.boostBar?.let { "%.2f".format(it) } ?: "--"} bar · rpm ${live.rpm.toInt()}"
            } else null,
            progress = diag.roadProgress,
        )
        StepCard(
            number = 4, title = "Termostato (automático)",
            state = if (diag.drivingSeconds >= 600f) StepUi.DONE else if (diag.active) StepUi.RUNNING else StepUi.PENDING,
            instructions = "No tienes que hacer nada: mientras conduces 10 minutos la app vigila hasta dónde " +
                "sube la temperatura del motor.",
            extra = "Circulando ${(diag.drivingSeconds / 60f).toInt()} min · máxima ${diag.coolantMax?.toInt() ?: "--"} °C",
            progress = (diag.drivingSeconds / 600f).coerceIn(0f, 1f),
        )

        // Lectura en directo
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().background(Dash.Panel, RoundedCornerShape(12.dp)).padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            LiveValue("Aire caudalím.", diag.lastMafAir?.toInt()?.toString() ?: "--", "mg")
            LiveValue("Aire esperado", diag.lastMapAir?.toInt()?.toString() ?: "--", "mg")
            LiveValue("Proporción", diag.lastRatio?.let { "${(it * 100).toInt()}" } ?: "--", "%")
        }

        // Resultados
        val verdicts = Diagnostics.verdicts(diag)
        if (verdicts.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Caption(if (diag.step == DiagStep.DONE) "Resultado" else "Resultado provisional")
            verdicts.forEach { v ->
                val color = when (v.level) {
                    Level.OK -> Dash.Green
                    Level.WARN -> Dash.Amber
                    Level.BAD -> Dash.Red
                    Level.INFO -> Dash.TextDim
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(Dash.Panel, RoundedCornerShape(12.dp))
                        .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                ) {
                    Box(Modifier.padding(top = 5.dp).size(10.dp).background(color, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(v.title, style = body.copy(fontWeight = FontWeight.Bold, color = color))
                        if (v.detail.isNotEmpty()) Text(v.detail, style = dim)
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (diag.active) {
                OutlinedButton(onClick = onStop) {
                    Text("TERMINAR", fontFamily = Rajdhani, fontWeight = FontWeight.Bold, color = Dash.Text)
                }
            }
            Button(
                onClick = onStart, enabled = connected,
                colors = ButtonDefaults.buttonColors(containerColor = Dash.Red, contentColor = Color.White),
            ) { Text("EMPEZAR DE NUEVO", fontFamily = Rajdhani, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Mientras el diagnóstico está activo el panel se actualiza algo más lento, porque se leen más sensores.",
            style = dim,
        )
    }
}

private enum class StepUi { PENDING, RUNNING, DONE }

private fun stepState(d: DiagState, step: DiagStep): StepUi = when {
    d.step.ordinal > step.ordinal -> StepUi.DONE
    d.step == step && d.active -> StepUi.RUNNING
    else -> StepUi.PENDING
}

@Composable
private fun StepCard(number: Int, title: String, state: StepUi, instructions: String, extra: String?, progress: Float) {
    val color = when (state) {
        StepUi.DONE -> Dash.Green
        StepUi.RUNNING -> Dash.Red
        StepUi.PENDING -> Dash.TextFaint
    }
    val p by animateFloatAsState(if (state == StepUi.DONE) 1f else progress, label = "step")
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(Dash.Panel, RoundedCornerShape(12.dp))
            .border(1.dp, color.copy(alpha = if (state == StepUi.RUNNING) 0.8f else 0.25f), RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
                Text(if (state == StepUi.DONE) "✓" else "$number", style = body.copy(color = Color.Black, fontWeight = FontWeight.Bold))
            }
            Spacer(Modifier.width(10.dp))
            Text(title, style = body.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp), modifier = Modifier.weight(1f))
            Caption(
                when (state) {
                    StepUi.DONE -> "Hecho"
                    StepUi.RUNNING -> "Ahora"
                    StepUi.PENDING -> "Pendiente"
                },
                color = color,
            )
        }
        if (state != StepUi.DONE) {
            Spacer(Modifier.height(6.dp))
            Text(instructions, style = if (state == StepUi.RUNNING) body else dim)
        }
        extra?.let { Text(it, style = dim.copy(color = Dash.RedSoft), modifier = Modifier.padding(top = 4.dp)) }
        if (state != StepUi.PENDING) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { p }, modifier = Modifier.fillMaxWidth().height(6.dp),
                color = color, trackColor = Dash.Stroke,
            )
        }
    }
}

@Composable
private fun LiveValue(label: String, value: String, unit: String) {
    Column {
        Caption(label)
        Row(verticalAlignment = Alignment.Bottom) {
            BigNumber(value, 18)
            Spacer(Modifier.width(3.dp))
            Text(unit, style = dim)
        }
    }
}
