package com.eneko.toledoobd.ui

import androidx.compose.foundation.background
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
import com.eneko.toledoobd.BtDevice
import com.eneko.toledoobd.DtcState
import com.eneko.toledoobd.data.DtcInfo
import com.eneko.toledoobd.data.EnginePreset
import com.eneko.toledoobd.data.Settings
import com.eneko.toledoobd.ui.components.BigNumber
import com.eneko.toledoobd.ui.components.Caption
import com.eneko.toledoobd.ui.theme.Dash
import com.eneko.toledoobd.ui.theme.Rajdhani
import java.util.Locale

private val body = TextStyle(fontFamily = Rajdhani, fontSize = 16.sp, color = Dash.Text)
private val dim = TextStyle(fontFamily = Rajdhani, fontSize = 14.sp, color = Dash.TextDim)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
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
private fun SheetTitle(text: String) {
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
    onEngine: (EnginePreset) -> Unit,
    onCalibration: (Float) -> Unit,
    onFuelPrice: (Float) -> Unit,
    onRefuel: (Float) -> Boolean,
    onRelearn: () -> Unit,
    onShareCsv: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showLog by remember { mutableStateOf(false) }
    var refuel by remember { mutableStateOf("") }
    var refuelMsg by remember { mutableStateOf<String?>(null) }
    var price by remember { mutableStateOf(String.format(Locale.US, "%.3f", settings.fuelPrice)) }

    DashSheet(onDismiss) {
        SheetTitle("Ajustes")

        Caption("Motor")
        EnginePreset.entries.forEach { e ->
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
