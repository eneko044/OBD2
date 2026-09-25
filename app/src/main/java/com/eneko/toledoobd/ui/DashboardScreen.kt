package com.eneko.toledoobd.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eneko.toledoobd.ConnState
import com.eneko.toledoobd.DashState
import com.eneko.toledoobd.DashboardViewModel
import com.eneko.toledoobd.data.Settings
import com.eneko.toledoobd.ui.components.AnalogGauge
import com.eneko.toledoobd.ui.components.ArcMeter
import com.eneko.toledoobd.ui.components.BigNumber
import com.eneko.toledoobd.ui.components.Caption
import com.eneko.toledoobd.ui.components.ConsumptionBar
import com.eneko.toledoobd.ui.components.GearIndicator
import com.eneko.toledoobd.ui.components.Panel
import com.eneko.toledoobd.ui.components.Sparkline
import com.eneko.toledoobd.ui.components.StatTile
import com.eneko.toledoobd.ui.components.StatusDot
import com.eneko.toledoobd.ui.components.UnitText
import com.eneko.toledoobd.ui.components.consumptionColor
import com.eneko.toledoobd.ui.components.dashBackground
import com.eneko.toledoobd.ui.theme.Dash
import com.eneko.toledoobd.ui.theme.Orbitron
import com.eneko.toledoobd.ui.theme.Rajdhani
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private const val RPM_MAX = 5000f
private const val RPM_RED = 4500f
private const val SPEED_MAX = 220f

private fun f1(v: Float) = String.format(Locale.getDefault(), "%.1f", v)
private fun f2(v: Float) = String.format(Locale.getDefault(), "%.2f", v)

@Composable
fun DashboardScreen(
    s: DashState,
    settings: Settings,
    onConnect: () -> Unit,
    onDemo: () -> Unit,
    onDisconnect: () -> Unit,
    onSettings: () -> Unit,
    onDtc: () -> Unit,
    onResetTrip: () -> Unit,
) {
    // ---- Animación de arranque: la escala se ilumina y las agujas barren a tope y vuelven.
    val sweep = remember { Animatable(0f) }
    val reveal = remember { Animatable(0f) }
    var intro by remember { mutableStateOf(true) }
    LaunchedEffect(s.introKey) {
        intro = true
        launch {
            reveal.snapTo(0f)
            reveal.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
        }
        sweep.snapTo(0f)
        delay(250)
        sweep.animateTo(1f, tween(950, easing = FastOutSlowInEasing))
        delay(140)
        sweep.animateTo(0f, tween(850, easing = FastOutSlowInEasing))
        intro = false
    }

    val rpmAnim = remember { Animatable(0f) }
    val speedAnim = remember { Animatable(0f) }
    val needleSpring = spring<Float>(dampingRatio = 0.6f, stiffness = 110f)
    LaunchedEffect(s.live.rpm, intro) {
        if (intro) rpmAnim.snapTo(0f) else rpmAnim.animateTo(s.live.rpm, needleSpring)
    }
    LaunchedEffect(s.live.speed, intro) {
        if (intro) speedAnim.snapTo(0f) else speedAnim.animateTo(s.live.speed, needleSpring)
    }
    val rpmFrac = if (intro) sweep.value else rpmAnim.value / RPM_MAX
    val speedFrac = if (intro) sweep.value else speedAnim.value / SPEED_MAX
    val shiftUp = !intro && (s.gear ?: 5) < 5 && s.live.rpm > 2700f

    var confirmReset by remember { mutableStateOf(false) }

    val rpmGauge: @Composable (Modifier) -> Unit = { m ->
        AnalogGauge(
            fraction = rpmFrac, reveal = reveal.value, max = RPM_MAX, majorStep = 1000f, minorPerMajor = 5,
            redFrom = RPM_RED, tickLabel = { (it / 1000).toInt().toString() },
            title = "RPM ×1000", readout = (rpmFrac * RPM_MAX).toInt().coerceAtLeast(0).toString(), unit = "RPM",
            alarm = !intro && s.live.rpm >= RPM_RED, modifier = m,
        )
    }
    val speedGauge: @Composable (Modifier) -> Unit = { m ->
        AnalogGauge(
            fraction = speedFrac, reveal = reveal.value, max = SPEED_MAX, majorStep = 20f, minorPerMajor = 4,
            redFrom = null, tickLabel = { it.toInt().toString() },
            title = "VELOCIDAD", readout = (speedFrac * SPEED_MAX).toInt().coerceAtLeast(0).toString(), unit = "KM/H",
            alarm = false, modifier = m,
        )
    }
    val gaugeIn = Modifier.graphicsLayer {
        val k = reveal.value
        alpha = 0.2f + 0.8f * k
        scaleX = 0.88f + 0.12f * k
        scaleY = 0.88f + 0.12f * k
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .dashBackground()
            .safeDrawingPadding(),
    ) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            Row(Modifier.fillMaxSize().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                rpmGauge(Modifier.weight(0.34f).then(gaugeIn))
                Column(
                    Modifier
                        .weight(0.32f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TopBar(s, onConnect, onDisconnect, onSettings, onDtc, compact = true)
                    ConnectionPanel(s, onConnect, onDemo)
                    GearIndicator(s.gear, shiftUp)
                    ConsumptionPanel(s)
                    MetersPanel(s)
                    TripPanel(s, settings) { confirmReset = true }
                    HistoryPanel(s)
                    Footer(s)
                }
                speedGauge(Modifier.weight(0.34f).then(gaugeIn))
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TopBar(s, onConnect, onDisconnect, onSettings, onDtc, compact = false)
                ConnectionPanel(s, onConnect, onDemo)
                Box(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        rpmGauge(Modifier.weight(1f).then(gaugeIn))
                        speedGauge(Modifier.weight(1f).then(gaugeIn))
                    }
                    GearIndicator(
                        s.gear, shiftUp,
                        Modifier.align(Alignment.BottomCenter).offset(y = 14.dp),
                        size = 56.dp,
                    )
                }
                ConsumptionPanel(s)
                MetersPanel(s)
                TripPanel(s, settings) { confirmReset = true }
                HistoryPanel(s)
                Footer(s)
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = Dash.PanelHi,
            title = { Text("Reiniciar trayecto") },
            text = { Text("Se pondrán a cero distancia, combustible, tiempo y consumo medio.") },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; onResetTrip() }) { Text("Reiniciar", color = Dash.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancelar", color = Dash.TextDim) } },
        )
    }
}

@Composable
private fun TopBar(
    s: DashState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSettings: () -> Unit,
    onDtc: () -> Unit,
    compact: Boolean,
) {
    val (dot, status) = when (val c = s.conn) {
        ConnState.Idle -> Dash.TextFaint to "Desconectado"
        is ConnState.Connecting -> Dash.Amber to "Conectando…"
        is ConnState.Connected -> (if (s.ecuResponding) Dash.Green else Dash.Amber) to
            (if (s.ecuResponding) c.device else "Sin respuesta ECU")
        is ConnState.Error -> Dash.Red to "Error de conexión"
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(dot, pulsing = s.conn is ConnState.Connected || s.conn is ConnState.Connecting)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (compact) "TOLEDO TDI" else "SEAT TOLEDO 1.9 TDI",
                style = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = if (compact) 13.sp else 15.sp, color = Dash.Text, letterSpacing = 1.sp),
                maxLines = 1,
            )
            Text(status, style = TextStyle(fontFamily = Rajdhani, fontSize = 13.sp, color = Dash.TextDim), maxLines = 1)
        }
        IconButton(onClick = onDtc) { Icon(Icons.Filled.Warning, "Averías", tint = Dash.Amber) }
        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Ajustes", tint = Dash.TextDim) }
        if (s.conn is ConnState.Connected || s.conn is ConnState.Connecting) {
            IconButton(onClick = onDisconnect) { Icon(Icons.Filled.Close, "Desconectar", tint = Dash.Red) }
        }
    }
}

@Composable
private fun ConnectionPanel(s: DashState, onConnect: () -> Unit, onDemo: () -> Unit) {
    val show = s.conn !is ConnState.Connected
    AnimatedVisibility(show, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Panel(Modifier.fillMaxWidth()) {
            when (val c = s.conn) {
                is ConnState.Connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Dash.Red, strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(c.step, color = Dash.Text, fontFamily = Rajdhani, fontSize = 16.sp)
                }
                else -> {
                    if (c is ConnState.Error) {
                        Text(c.message, color = Dash.RedSoft, fontFamily = Rajdhani, fontSize = 15.sp)
                        Spacer(Modifier.height(8.dp))
                    } else {
                        Text(
                            "Enchufa el adaptador OBD2, pon el contacto y conéctate.",
                            color = Dash.TextDim, fontFamily = Rajdhani, fontSize = 15.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onConnect,
                            colors = ButtonDefaults.buttonColors(containerColor = Dash.Red, contentColor = Color.White),
                        ) { Text("CONECTAR OBD", fontFamily = Rajdhani, fontWeight = FontWeight.Bold) }
                        OutlinedButton(onClick = onDemo) {
                            Text("DEMO", fontFamily = Rajdhani, fontWeight = FontWeight.Bold, color = Dash.Text)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsumptionPanel(s: DashState) {
    val moving = s.instL100 != null
    val target = s.instL100 ?: s.instLph
    val shown by animateFloatAsState(target, tween(450), label = "cons")
    val color = if (moving) consumptionColor(shown) else Dash.Text
    val avg = s.trip.avgL100
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Caption(if (moving) "Consumo instantáneo" else "Consumo al ralentí", Modifier.weight(1f))
            Caption(if (moving) "L/100 km" else "L/h", color = Dash.Red)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            BigNumber(f1(shown), 46, color = color)
            Spacer(Modifier.width(8.dp))
            UnitText(if (moving) "L/100 km" else "L/h · parado", Modifier.padding(bottom = 8.dp))
        }
        ConsumptionBar(value = target, maxScale = 20f, average = if (moving) avg else null)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            StatTile("Media", avg?.let { f1(it) } ?: "--", "L/100", Modifier.weight(1f), accent = avg?.let { consumptionColor(it) } ?: Dash.Text)
            StatTile("Caudal", f2(s.instLph), "L/h", Modifier.weight(1f))
            StatTile("Carga", s.live.load?.let { "${it.toInt()}" } ?: "--", "%", Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetersPanel(s: DashState) {
    val l = s.live
    Panel(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val meter = ((maxWidth - 12.dp) / 4).coerceAtMost(80.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ArcMeter(
                l.coolant, 40f, 120f, "Motor", l.coolant?.toInt()?.toString() ?: "--", "°C",
                listOf(Dash.Blue, Dash.Green, Dash.Amber, Dash.Red), size = meter,
            )
            ArcMeter(
                l.boostBar, 0f, 1.6f, "Turbo", l.boostBar?.let { f2(it) } ?: "--", "bar",
                listOf(Dash.RedDeep, Dash.Red, Dash.RedSoft), size = meter,
            )
            ArcMeter(
                l.intakeTemp, -10f, 70f, "Admisión", l.intakeTemp?.toInt()?.toString() ?: "--", "°C",
                listOf(Dash.Blue, Dash.Green, Dash.Amber), size = meter,
            )
            ArcMeter(
                l.voltage, 11f, 15f, "Batería", l.voltage?.let { f1(it) } ?: "--", "V",
                listOf(Dash.Red, Dash.Amber, Dash.Green, Dash.Green), size = meter,
            )
        }
        }
        val warning = when {
            l.voltage != null && l.rpm > 500f && l.voltage < 13.0f -> "Tensión baja con motor en marcha: revisa el alternador"
            l.coolant != null && l.coolant > 105f -> "Temperatura del motor alta"
            l.coolant != null && l.rpm > 500f && l.coolant < 60f -> "Motor frío: evita pasar de 3000 rpm"
            else -> null
        }
        AnimatedVisibility(warning != null) {
            Text(
                "⚠ " + (warning ?: ""),
                modifier = Modifier.padding(top = 8.dp),
                style = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Dash.Amber),
            )
        }
    }
}

@Composable
private fun TripPanel(s: DashState, settings: Settings, onReset: () -> Unit) {
    val t = s.trip
    val totalS = t.timeS.toLong()
    val time = "%d:%02d".format(totalS / 3600, (totalS % 3600) / 60)
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Caption(if (s.demo) "Trayecto (demo)" else "Trayecto", Modifier.weight(1f))
            TextButton(onClick = onReset) { Text("REINICIAR", color = Dash.Red, fontFamily = Rajdhani, fontWeight = FontWeight.Bold) }
        }
        Row(Modifier.fillMaxWidth()) {
            StatTile("Distancia", f1(t.distanceKm.toFloat()), "km", Modifier.weight(1f))
            StatTile("Gastado", f2(t.fuelL.toFloat()), "L", Modifier.weight(1f))
            StatTile("Coste", f2((t.fuelL * settings.fuelPrice).toFloat()), "€", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            StatTile("Tiempo", time, "h", Modifier.weight(1f))
            StatTile("Vel. media", t.avgSpeed?.toInt()?.toString() ?: "--", "km/h", Modifier.weight(1f))
            StatTile("Vel. máx", t.maxSpeed.toInt().toString(), "km/h", Modifier.weight(1f))
        }
    }
}

@Composable
private fun HistoryPanel(s: DashState) {
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Caption("Consumo · últimos 2 min", Modifier.weight(1f))
            Caption("L/100 km", color = Dash.TextFaint)
        }
        Spacer(Modifier.height(6.dp))
        Sparkline(
            points = s.history, capacity = DashboardViewModel.HISTORY_POINTS, maxY = 20f,
            average = s.trip.avgL100, modifier = Modifier.height(110.dp),
        )
    }
}

@Composable
private fun Footer(s: DashState) {
    val proto = (s.conn as? ConnState.Connected)?.protocol
    Text(
        listOfNotNull("Consumo: ${s.fuelSource.label}", proto).joinToString("  ·  "),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        style = TextStyle(fontFamily = Rajdhani, fontSize = 12.sp, color = Dash.TextFaint),
    )
}
