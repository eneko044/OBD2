package com.eneko.toledoobd

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eneko.toledoobd.ui.DashboardScreen
import com.eneko.toledoobd.ui.DevicePickerSheet
import com.eneko.toledoobd.ui.DtcSheet
import com.eneko.toledoobd.ui.SettingsSheet
import com.eneko.toledoobd.ui.theme.ToledoTheme

class MainActivity : ComponentActivity() {

    private val vm: DashboardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            ToledoTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                val settings by vm.settings.collectAsStateWithLifecycle()
                val log by vm.log.collectAsStateWithLifecycle()
                val dtc by vm.dtc.collectAsStateWithLifecycle()

                var showPicker by remember { mutableStateOf(false) }
                var showSettings by remember { mutableStateOf(false) }
                var showDtc by remember { mutableStateOf(false) }
                var devices by remember { mutableStateOf(emptyList<BtDevice>()) }

                val openPicker = {
                    devices = vm.pairedDevices()
                    showPicker = true
                }
                val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    if (vm.isBluetoothEnabled()) openPicker()
                    else Toast.makeText(this, "Activa el Bluetooth para conectar", Toast.LENGTH_SHORT).show()
                }
                val afterPermission = {
                    if (vm.isBluetoothEnabled()) openPicker()
                    else enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }
                val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (granted) afterPermission()
                    else Toast.makeText(this, "Sin permiso de Bluetooth no puedo leer el OBD", Toast.LENGTH_LONG).show()
                }
                val onConnect = {
                    if (hasBtPermission()) afterPermission()
                    else askPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }

                LaunchedEffect(Unit) {
                    if (hasBtPermission()) vm.autoConnectIfPossible()
                }

                DashboardScreen(
                    s = state,
                    settings = settings,
                    onConnect = onConnect,
                    onDemo = vm::startDemo,
                    onDisconnect = vm::disconnect,
                    onSettings = { showSettings = true },
                    onDtc = { showDtc = true },
                    onResetTrip = vm::resetTrip,
                )

                if (showPicker) {
                    DevicePickerSheet(
                        devices = devices,
                        lastDevice = settings.lastDevice,
                        onPick = { showPicker = false; vm.connect(it.address) },
                        onDemo = { showPicker = false; vm.startDemo() },
                        onDismiss = { showPicker = false },
                    )
                }
                if (showSettings) {
                    SettingsSheet(
                        settings = settings,
                        tripFuel = state.trip.fuelL,
                        log = log,
                        onEngine = vm::setEngine,
                        onCalibration = vm::setCalibration,
                        onFuelPrice = vm::setFuelPrice,
                        onRefuel = vm::calibrateWithRefuel,
                        onDismiss = { showSettings = false },
                    )
                }
                if (showDtc) {
                    DtcSheet(
                        state = dtc,
                        onRead = vm::readDtcs,
                        onClear = vm::clearDtcs,
                        onDismiss = { showDtc = false },
                    )
                }
            }
        }
    }

    private fun hasBtPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}
