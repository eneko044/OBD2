package com.eneko.toledoobd

import android.Manifest
import android.app.PictureInPictureParams
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.res.Configuration
import android.util.Rational
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
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eneko.toledoobd.data.FloatMode
import com.eneko.toledoobd.data.Gearbox
import com.eneko.toledoobd.ui.DashboardScreen
import com.eneko.toledoobd.ui.PipView
import com.eneko.toledoobd.ui.DevicePickerSheet
import com.eneko.toledoobd.ui.DiagnosticSheet
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
                var showDiag by remember { mutableStateOf(false) }
                val diag by vm.diag.collectAsStateWithLifecycle()
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

                // Ventana flotante: solo con el OBD (o la demo) conectado y si el usuario la tiene activada.
                val connected = state.conn is ConnState.Connected
                LaunchedEffect(settings.floatMode, connected) {
                    updatePip(
                        allowed = connected && settings.floatMode != FloatMode.OFF,
                        ratio = if (settings.floatMode == FloatMode.BOTH) Rational(2, 1) else Rational(16, 9),
                    )
                }
                LaunchedEffect(inPip) {
                    if (inPip) {
                        showPicker = false
                        showSettings = false
                        showDtc = false
                        showDiag = false
                    }
                }

                if (inPip) {
                    PipView(state, settings)
                    return@ToledoTheme
                }

                DashboardScreen(
                    s = state,
                    settings = settings,
                    onConnect = onConnect,
                    onDemo = vm::startDemo,
                    onDisconnect = vm::disconnect,
                    onSettings = { showSettings = true },
                    onDtc = { showDtc = true },
                    onDiag = { showDiag = true },
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
                        onVehicle = vm::setVehicle,
                        onEngine = vm::setEngine,
                        onGearbox = vm::setGearbox,
                        onCalibration = vm::setCalibration,
                        onFuelPrice = vm::setFuelPrice,
                        onRefuel = vm::calibrateWithRefuel,
                        tripKm = state.trip.distanceKm,
                        onRealKm = vm::calibrateDistance,
                        onSpeedFactor = vm::setSpeedFactor,
                        onResetSpeedFactor = { vm.setSpeedFactor(1f) },
                        onRelearn = vm::relearnLoadOffset,
                        onShareCsv = { shareCsv() },
                        hasPip = hasPip,
                        onFloatMode = vm::setFloatMode,
                        onDismiss = { showSettings = false },
                    )
                }
                if (showDiag) {
                    DiagnosticSheet(
                        diag = diag,
                        live = state.live,
                        connected = state.conn is ConnState.Connected,
                        automatic = settings.gearbox == Gearbox.BMW_AUTO,
                        onStart = vm::startDiagnostics,
                        onStop = vm::stopDiagnostics,
                        onDismiss = { showDiag = false },
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

    // ---------------------------------------------------------------- Imagen en imagen

    private var inPip by mutableStateOf(false)
    private var pipAllowed = false
    private var pipRatio = Rational(2, 1)

    private val hasPip by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }

    private fun pipParams(): PictureInPictureParams = PictureInPictureParams.Builder()
        .setAspectRatio(pipRatio)
        .apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+: entra solo al ir a inicio o cambiar de app, con animación del sistema.
                setAutoEnterEnabled(pipAllowed)
                setSeamlessResizeEnabled(false)
            }
        }
        .build()

    private fun updatePip(allowed: Boolean, ratio: Rational) {
        pipAllowed = allowed
        pipRatio = ratio
        if (hasPip) runCatching { setPictureInPictureParams(pipParams()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // En Android 8-11 hay que pedirlo a mano al salir de la app.
        if (hasPip && pipAllowed && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            runCatching { enterPictureInPictureMode(pipParams()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    private fun shareCsv() {
        val file = vm.exportCsv()
        if (file == null) {
            Toast.makeText(this, "Aún no hay datos registrados: conéctate y conduce un rato", Toast.LENGTH_LONG).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Datos OBD Toledo")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Compartir datos del viaje"))
    }

    private fun hasBtPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}
