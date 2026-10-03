package com.eneko.toledoobd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import com.eneko.toledoobd.BtDevice
import com.eneko.toledoobd.ConnState
import com.eneko.toledoobd.DashState
import com.eneko.toledoobd.data.LiveData
import com.eneko.toledoobd.data.TripStats
import com.eneko.toledoobd.data.overlaySizeDp
import com.eneko.toledoobd.DtcState
import com.eneko.toledoobd.data.DtcInfo
import com.eneko.toledoobd.data.EnginePreset
import com.eneko.toledoobd.data.FloatMode
import com.eneko.toledoobd.data.Gearbox
import com.eneko.toledoobd.data.Settings
import com.eneko.toledoobd.data.Vehicle
import com.eneko.toledoobd.ui.components.BigNumber
import com.eneko.toledoobd.ui.components.Caption
import com.eneko.toledoobd.ui.theme.Dash
import com.eneko.toledoobd.ui.theme.Rajdhani
import java.util.Locale

/** Datos de ejemplo para la vista previa de la ventana pequeña. */
private val previewState = DashState(
    conn = ConnState.Connected("OBD", ""),
    live = LiveData(rpm = 2000f, speed = 90f),
    instLph = 4.9f, instL100 = 5.4f,
    trip = TripStats(distanceKm = 40.0, fuelL = 2.0),
)

private val body = TextStyle(fontFamily = Rajdhani, fontSize = 16.sp, color = Dash.Text)
private val dim = TextStyle(fontFamily = Rajdhani, fontSize = 14.sp, color = Dash.TextDim)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DashSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Dash.PanelHi,
        contentColor = Dash.Text,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) { content() }
    }
}

@Composable
internal fun SheetTitle(text: String) {
    Text(text, style = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Dash.Text))
    Spacer(Modifier.height(12.dp))
}

@Composable
fun DevicePickerSheet(devices: List<BtDevice>, lastDevice: String?, onPick: (BtDevice) -> Unit, onDemo: () -> Unit, onDismiss: () -> Unit) {
    DashSheet(onDismiss) {
        SheetTitle("Elige tu adaptador OBD2")
        if (devices.isEmpty()) {
            Text(
                "No hay dispositivos emparejados. Ve a Ajustes de Android → Bluetooth, empareja el adaptador " +
                    "(suele llamarse OBDII, OBD2 o V-LINK, PIN 1234 o 0000) y vuelve aquí.",
                style = dim,
            )
        }
        devices.forEach { d ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .background(Dash.Panel, RoundedCornerShape(12.dp))
                    .clickable { onPick(d) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(d.name, style = body.copy(fontWeight = FontWeight.SemiBold))
                    Text(d.address, style = dim)
                }
                if (d.address == lastDevice) Caption("Último", color = Dash.Red)
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onDemo, modifier = Modifier.fillMaxWidth()) {
            Text("PROBAR EN MODO DEMO", fontFamily = Rajdhani, fontWeight = FontWeight.Bold, color = Dash.Text)
        }
    }
}

@Composable
fun SettingsSheet(
    settings: Settings,
    tripFuel: Double,
    log: List<String>,
    onVehicle: (Vehicle) -> Unit,
    onEngine: (EnginePreset) -> Unit,
    onGearbox: (Gearbox) -> Unit,
    onCalibration: (Float) -> Unit,
    onFuelPrice: (Float) -> Unit,
    onRefuel: (Float) -> Boolean,
    tripKm: Double,
    onRealKm: (Float) -> Boolean,
    onSpeedFactor: (Float) -> Unit,
    onResetSpeedFactor: () -> Unit,
    onRelearn: () -> Unit,
    onShareCsv: () -> Unit,
    hasPip: Boolean,
    onFloatMode: (FloatMode) -> Unit,
    overlayGranted: Boolean,
    onRequestOverlay: () -> Unit,
    onOverlayScale: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var showLog by remember { mutableStateOf(false) }
    var refuel by remember { mutableStateOf("") }
    var refuelMsg by remember { mutableStateOf<String?>(null) }
    var price by remember { mutableStateOf(String.format(Locale.US, "%.3f", settings.fuelPrice)) }

    DashSheet(onDismiss) {
        SheetTitle("Ajustes")

        Caption("Coche")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Vehicle.entries.forEach { v ->
                val sel = settings.vehicle == v
                Column(
                    Modifier
                        .weight(1f)
                        .background(if (sel) Dash.Red.copy(alpha = 0.18f) else Dash.Panel, RoundedCornerShape(12.dp))
                        .border(1.5.dp, if (sel) Dash.Red else Dash.Stroke, RoundedCornerShape(12.dp))
                        .clickable { onVehicle(v) }
                        .padding(12.dp),
                ) {
                    Text(v.shortTitle, style = body.copy(fontWeight = FontWeight.Bold, color = if (sel) Dash.Text else Dash.TextDim))
                    Text(v.title, style = dim)
                }
            }
        }
        Text("Cada coche guarda su propio trayecto, calibración y ajustes.", style = dim, modifier = Modifier.padding(top = 4.dp))

        Spacer(Modifier.height(14.dp))
        Caption("Motor")
        EnginePreset.of(settings.vehicle).forEach { e ->
            Row(
                Modifier.fillMaxWidth().clickable { onEngine(e) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = settings.engine == e, onClick = { onEngine(e) },
                    colors = RadioButtonDefaults.colors(selectedColor = Dash.Red),
                )
                Text(e.label, style = body)
            }
        }
        Text(
            "Define la inyección máxima usada para estimar el consumo a partir de la carga del motor. " +
                "Con el coche reprogramado cada mapa es distinto: calibra con un repostaje para que la cifra sea exacta.",
            style = dim,
        )

        val boxes = Gearbox.of(settings.vehicle)
        if (boxes.size > 1) {
            Spacer(Modifier.height(14.dp))
            Caption("Caja de cambios")
            boxes.forEach { g ->
                Row(Modifier.fillMaxWidth().clickable { onGearbox(g) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = settings.gearbox == g, onClick = { onGearbox(g) },
                        colors = RadioButtonDefaults.colors(selectedColor = Dash.Red),
                    )
                    Text(g.label, style = body)
                }
            }
            Text("Se usa para saber en qué marcha vas. En el BMW es orientativa.", style = dim)
        }

        Spacer(Modifier.height(18.dp))
        Caption("Ventana flotante")
        FloatMode.entries.forEach { m ->
            Row(Modifier.fillMaxWidth().clickable { onFloatMode(m) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = settings.floatMode == m, onClick = { onFloatMode(m) },
                    colors = RadioButtonDefaults.colors(selectedColor = Dash.Red),
                )
                Text(m.label, style = body)
            }
        }
        if (settings.floatMode != FloatMode.OFF) {
            if (overlayGranted) {
                Text(
                    "Con el OBD conectado, al salir de la app (botón de inicio o al abrir Google Maps) queda una " +
                        "ventana pequeña con el consumo. Arrástrala donde quieras; tócala para volver al panel.",
                    style = dim,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tamaño", style = body, modifier = Modifier.width(64.dp))
                    Slider(
                        value = settings.overlayScale, onValueChange = onOverlayScale, valueRange = 0.6f..1.6f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = Dash.Red, activeTrackColor = Dash.Red, inactiveTrackColor = Dash.Stroke),
                    )
                }
                // Vista previa a tamaño real
                val (wDp, hDp) = overlaySizeDp(settings.floatMode, settings.overlayScale)
                val shape = RoundedCornerShape(12.dp)
                Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(wDp.dp, hDp.dp).clip(shape).border(1.dp, Dash.Stroke, shape)) {
                        PipView(previewState, settings)
                    }
                }
            } else {
                Text(
                    "Para una ventana pequeña y de tamaño ajustable, permite \"Mostrar sobre otras apps\". " +
                        "Sin ese permiso se usa la ventana de imagen en imagen de Android, cuyo tamaño mínimo pone el sistema.",
                    style = dim,
                )
                TextButton(onClick = onRequestOverlay) {
                    Text("PERMITIR VENTANA PEQUEÑA", color = Dash.Red, fontFamily = Rajdhani, fontWeight = FontWeight.Bold)
                }
                if (!hasPip) Text("Este móvil no permite imagen en imagen.", style = dim.copy(color = Dash.Amber))
            }
        }

        Spacer(Modifier.height(18.dp))
        Caption("Calibración del consumo")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = settings.calibration, onValueChange = onCalibration, valueRange = 0.5f..2f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Dash.Red, activeTrackColor = Dash.Red, inactiveTrackColor = Dash.Stroke),
            )
            Spacer(Modifier.width(12.dp))
            BigNumber("×" + String.format(Locale.getDefault(), "%.2f", settings.calibration), 16)
        }
        Text(
            "Truco para afinarlo: llena el depósito, reinicia el trayecto, conduce normal y al volver a llenar " +
                "escribe aquí los litros que ha cogido. La app ajusta la calibración sola.",
            style = dim,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = refuel, onValueChange = { refuel = it.replace(',', '.') },
                label = { Text("Litros repostados") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f), colors = fieldColors(),
            )
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = {
                    val l = refuel.toFloatOrNull()
                    refuelMsg = if (l != null && onRefuel(l)) "Calibración actualizada"
                    else "Necesitas al menos 1 L estimado en el trayecto (ahora: %.2f L)".format(tripFuel)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Dash.Red, contentColor = Color.White),
            ) { Text("AJUSTAR", fontFamily = Rajdhani, fontWeight = FontWeight.Bold) }
        }
        refuelMsg?.let { Text(it, style = dim.copy(color = Dash.Amber), modifier = Modifier.padding(top = 4.dp)) }

        Spacer(Modifier.height(18.dp))
        Caption("Corrección de velocidad y distancia")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "La velocidad que da el OBD puede no coincidir con la real. Tras un trayecto, escribe los km " +
                    "reales (cuentakilómetros o mapa) y la app corrige velocidad, distancia y L/100.",
                style = dim, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            BigNumber("×" + String.format(Locale.getDefault(), "%.3f", settings.speedFactor), 16)
        }
        Slider(
            value = settings.speedFactor, onValueChange = onSpeedFactor, valueRange = 0.85f..1.2f,
            colors = SliderDefaults.colors(thumbColor = Dash.Red, activeTrackColor = Dash.Red, inactiveTrackColor = Dash.Stroke),
        )
        Text("También puedes moverlo a mano: si el cuadro marca 100 y la app 90, pon ×1,10.", style = dim)
        Spacer(Modifier.height(8.dp))
        var realKm by remember { mutableStateOf("") }
        var kmMsg by remember { mutableStateOf<String?>(null) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = realKm, onValueChange = { realKm = it.replace(',', '.') },
                label = { Text("Km reales (la app lleva %.1f)".format(tripKm)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f), colors = fieldColors(),
            )
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = {
                    val km = realKm.toFloatOrNull()
                    kmMsg = if (km != null && onRealKm(km)) "Corrección aplicada. Reinicia el trayecto."
                    else "Hacen falta al menos 5 km en el trayecto y una diferencia menor del 25 %"
                },
                colors = ButtonDefaults.buttonColors(containerColor = Dash.Red, contentColor = Color.White),
            ) { Text("AJUSTAR", fontFamily = Rajdhani, fontWeight = FontWeight.Bold) }
        }
        kmMsg?.let { Text(it, style = dim.copy(color = Dash.Amber), modifier = Modifier.padding(top = 4.dp)) }
        if (settings.speedFactor != 1f) {
            TextButton(onClick = onResetSpeedFactor) {
                Text("QUITAR CORRECCIÓN", color = Dash.Red, fontFamily = Rajdhani, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(18.dp))
        Caption("Cero de la carga motor")
        Text(
            settings.loadOffset?.let {
                "Aprendido: la centralita marca %.1f %% sin inyectar (en retención). Se descuenta del cálculo.".format(it)
            } ?: "Aún sin aprender. Conduciendo, suelta el acelerador con la marcha metida unos segundos y se aprende solo.",
            style = dim,
        )
        TextButton(onClick = onRelearn) {
            Text("VOLVER A APRENDER", color = Dash.Red, fontFamily = Rajdhani, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(18.dp))
        Caption("Precio del gasóleo (€/L)")
        OutlinedTextField(
            value = price,
            onValueChange = {
                price = it.replace(',', '.')
                price.toFloatOrNull()?.let(onFuelPrice)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
        )

        Spacer(Modifier.height(18.dp))
        OutlinedButton(onClick = onShareCsv, modifier = Modifier.fillMaxWidth()) {
            Text("COMPARTIR DATOS DEL VIAJE (CSV)", fontFamily = Rajdhani, fontWeight = FontWeight.Bold, color = Dash.Text)
        }
        Text("Últimas 2 horas, un registro por segundo.", style = dim)
        TextButton(onClick = { showLog = !showLog }) {
            Text(if (showLog) "OCULTAR REGISTRO OBD" else "VER REGISTRO OBD (diagnóstico)", color = Dash.Red, fontFamily = Rajdhani, fontWeight = FontWeight.Bold)
        }
        if (showLog) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .background(Color(0xFF050607), RoundedCornerShape(10.dp))
                    .verticalScroll(rememberScrollState(), reverseScrolling = true)
                    .padding(10.dp),
            ) {
                if (log.isEmpty()) Text("Sin actividad todavía.", style = dim)
                log.forEach {
                    Text(it, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Dash.TextDim))
                }
            }
        }
    }
}

@Composable
fun DtcSheet(state: DtcState, onRead: () -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    DashSheet(onDismiss) {
        SheetTitle("Códigos de avería (OBD)")
        when (state) {
            DtcState.Idle -> Text("Lee los códigos genéricos guardados en la centralita del motor.", style = dim)
            DtcState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = Dash.Red)
                Spacer(Modifier.width(12.dp))
                Text("Consultando centralita…", style = body)
            }
            is DtcState.Failed -> Text(state.message, style = body.copy(color = Dash.RedSoft))
            is DtcState.Result -> {
                state.note?.let { Text(it, style = dim.copy(color = Dash.Amber)) }
                if (state.codes.isEmpty()) {
                    Text("✓ Sin códigos de avería", style = body.copy(color = Dash.Green, fontSize = 20.sp, fontWeight = FontWeight.Bold))
                }
                state.codes.forEach { code ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(Dash.Panel, RoundedCornerShape(12.dp))
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BigNumber(code, 18, color = Dash.Red)
                        Spacer(Modifier.width(14.dp))
                        Text(DtcInfo.describe(code), style = body)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onRead,
                colors = ButtonDefaults.buttonColors(containerColor = Dash.Red, contentColor = Color.White),
            ) { Text("LEER", fontFamily = Rajdhani, fontWeight = FontWeight.Bold) }
            OutlinedButton(onClick = { confirmClear = true }) {
                Text("BORRAR", fontFamily = Rajdhani, fontWeight = FontWeight.Bold, color = Dash.Text)
            }
        }
        if (confirmClear) {
            Spacer(Modifier.height(12.dp))
            Text("¿Borrar la memoria de averías? Hazlo con el motor parado y el contacto puesto.", style = body)
            Row {
                TextButton(onClick = { confirmClear = false; onClear() }) { Text("SÍ, BORRAR", color = Dash.Red) }
                TextButton(onClick = { confirmClear = false }) { Text("CANCELAR", color = Dash.TextDim) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Solo se ven los códigos OBD genéricos. Los códigos propios de VAG (5 dígitos) requieren VCDS.",
            style = dim,
        )
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Dash.Red,
    unfocusedBorderColor = Dash.Stroke,
    focusedLabelColor = Dash.Red,
    cursorColor = Dash.Red,
    focusedTextColor = Dash.Text,
    unfocusedTextColor = Dash.Text,
)
