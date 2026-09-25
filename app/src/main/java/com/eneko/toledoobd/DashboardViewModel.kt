package com.eneko.toledoobd

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eneko.toledoobd.data.DemoSimulator
import com.eneko.toledoobd.data.EnginePreset
import com.eneko.toledoobd.data.FuelModel
import com.eneko.toledoobd.data.FuelSource
import com.eneko.toledoobd.data.GearEstimator
import com.eneko.toledoobd.data.LiveData
import com.eneko.toledoobd.data.LoadOffsetLearner
import com.eneko.toledoobd.data.Settings
import com.eneko.toledoobd.data.TripStats
import com.eneko.toledoobd.obd.ElmIo
import com.eneko.toledoobd.obd.ObdSession
import com.eneko.toledoobd.obd.Pid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

sealed interface ConnState {
    data object Idle : ConnState
    data class Connecting(val step: String) : ConnState
    data class Connected(val device: String, val protocol: String) : ConnState
    data class Error(val message: String) : ConnState
}

data class DashState(
    val conn: ConnState = ConnState.Idle,
    val demo: Boolean = false,
    val live: LiveData = LiveData(),
    val instLph: Float = 0f,
    /** null cuando el coche está parado (se muestra L/h). */
    val instL100: Float? = null,
    val trip: TripStats = TripStats(),
    val history: List<Float> = emptyList(),
    val gear: Int? = null,
    val fuelSource: FuelSource = FuelSource.NONE,
    val ecuResponding: Boolean = true,
    val introKey: Int = 0,
)

sealed interface DtcState {
    data object Idle : DtcState
    data object Loading : DtcState
    data class Result(val codes: List<String>, val note: String? = null) : DtcState
    data class Failed(val message: String) : DtcState
}

data class BtDevice(val name: String, val address: String)

class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("toledo", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _state = MutableStateFlow(DashState(trip = loadTrip()))
    val state: StateFlow<DashState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _dtc = MutableStateFlow<DtcState>(DtcState.Idle)
    val dtc: StateFlow<DtcState> = _dtc.asStateFlow()

    private var job: Job? = null
    private var socket: BluetoothSocket? = null
    private var session: ObdSession? = null
    private val ioLock = Mutex()

    private var lastSampleAt = 0L
    private var lastHistoryAt = 0L
    private var lastSaveAt = 0L
    private var smoothLph = 0f
    private var autoConnectTried = false
    private val offsetLearner = LoadOffsetLearner(_settings.value.loadOffset)
    private val csv = ArrayDeque<String>()
    private var lastCsvAt = 0L
    private var supportedInfo = "sin conexión OBD"

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private fun log(msg: String) {
        val line = "${timeFmt.format(Date())}  $msg"
        _log.update { (it + line).takeLast(250) }
    }

    // ---------------------------------------------------------------- Bluetooth

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<BtDevice> = try {
        val mgr = getApplication<Application>().getSystemService(BluetoothManager::class.java)
        mgr?.adapter?.bondedDevices.orEmpty()
            .map { BtDevice(it.name ?: it.address, it.address) }
            .sortedByDescending { d -> OBD_HINTS.any { d.name.contains(it, ignoreCase = true) } }
    } catch (e: SecurityException) {
        emptyList()
    }

    fun isBluetoothEnabled(): Boolean = try {
        getApplication<Application>().getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    } catch (e: SecurityException) {
        false
    }

    fun autoConnectIfPossible() {
        if (autoConnectTried) return
        autoConnectTried = true
        val last = _settings.value.lastDevice ?: return
        if (isBluetoothEnabled() && pairedDevices().any { it.address == last }) connect(last)
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        stop()
        saveSettings(_settings.value.copy(lastDevice = address))
        job = viewModelScope.launch(Dispatchers.IO) {
            var s: BluetoothSocket? = null
            try {
                _state.update { it.copy(conn = ConnState.Connecting("Conectando por Bluetooth…")) }
                val mgr = getApplication<Application>().getSystemService(BluetoothManager::class.java)
                val adapter = mgr?.adapter ?: throw IOException("Este móvil no tiene Bluetooth")
                val device = adapter.getRemoteDevice(address)
                val name = device.name ?: address
                log("Conectando a $name ($address)")
                s = openSocket(device)
                socket = s
                val obd = ObdSession(ElmIo(s.inputStream, s.outputStream, ::log), ::log)
                ioLock.withLock {
                    obd.initialize { step -> _state.update { it.copy(conn = ConnState.Connecting(step)) } }
                }
                session = obd
                val source = FuelModel.source(obd.supported)
                supportedInfo = "Protocolo ${obd.protocol} · PIDs soportados: " +
                    obd.supported.sorted().joinToString(" ") { "%02X".format(it) }
                log("Protocolo: ${obd.protocol} · consumo: ${source.label}")
                _state.update {
                    it.copy(
                        conn = ConnState.Connected(name, obd.protocol),
                        fuelSource = source,
                        introKey = it.introKey + 1,
                    )
                }
                pollLoop(obd, source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Error: ${e.message}")
                _state.update { it.copy(conn = ConnState.Error(e.message ?: "Error de conexión")) }
            } finally {
                try {
                    s?.close()
                } catch (_: IOException) {
                }
                if (socket === s) {
                    socket = null
                    session = null
                }
                withContext(NonCancellable) { saveTrip() }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openSocket(device: BluetoothDevice): BluetoothSocket {
        try {
            getApplication<Application>().getSystemService(BluetoothManager::class.java)?.adapter?.cancelDiscovery()
        } catch (_: SecurityException) {
        }
        val attempts: List<Pair<String, () -> BluetoothSocket>> = listOf(
            "SPP seguro" to { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            "SPP inseguro" to { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            "canal 1" to {
                device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
            },
        )
        var last: Exception? = null
        for ((label, make) in attempts) {
            var s: BluetoothSocket? = null
            try {
                s = make()
                s.connect()
                log("Socket abierto ($label)")
                return s
            } catch (e: Exception) {
                log("Fallo $label: ${e.message}")
                last = e
                try {
                    s?.close()
                } catch (_: IOException) {
                }
            }
        }
        throw IOException("No se pudo abrir el enlace Bluetooth (${last?.message ?: "?"})")
    }

    private suspend fun pollLoop(obd: ObdSession, source: FuelSource) {
        val sup = obd.supported
        val fast = buildList {
            add(Pid.RPM)
            add(Pid.SPEED)
            when (source) {
                FuelSource.FUEL_RATE -> add(Pid.FUEL_RATE)
                FuelSource.LOAD -> add(Pid.LOAD)
                FuelSource.MAF -> add(Pid.MAF)
                FuelSource.NONE -> Unit
            }
        }
        val slow = listOf(Pid.MAP, Pid.COOLANT, Pid.MAP, Pid.IAT, Pid.MAP, VOLTAGE, Pid.BARO)
            .filter { it == VOLTAGE || it in sup }
        val failures = mutableMapOf<Int, Int>()
        var live = LiveData()
        var slowIdx = 0
        var rpmMisses = 0
        lastSampleAt = SystemClock.elapsedRealtime()

        while (currentCoroutineContext().isActive) {
            ioLock.withLock {
                for (pid in fast) {
                    val b = obd.query(pid)
                    if (b == null) {
                        if (pid == Pid.RPM) rpmMisses++
                    } else {
                        if (pid == Pid.RPM) rpmMisses = 0
                        live = apply(live, pid, b)
                    }
                }
                if (slow.isNotEmpty()) {
                    val pid = slow[slowIdx % slow.size]
                    slowIdx++
                    if ((failures[pid] ?: 0) < 6) {
                        if (pid == VOLTAGE) {
                            val v = obd.voltage()
                            if (v == null) failures[pid] = (failures[pid] ?: 0) + 1 else live = live.copy(voltage = v)
                        } else {
                            val b = obd.query(pid)
                            if (b == null) failures[pid] = (failures[pid] ?: 0) + 1
                            else {
                                failures[pid] = 0
                                live = apply(live, pid, b)
                            }
                        }
                    }
                }
            }
            val responding = rpmMisses < 4
            if (!responding) live = live.copy(rpm = 0f, speed = 0f)
            _state.update { it.copy(ecuResponding = responding) }
            onSample(live)
            if (!responding) delay(800)
        }
    }

    private fun apply(d: LiveData, pid: Int, b: IntArray): LiveData {
        fun w() = if (b.size >= 2) b[0] * 256 + b[1] else b[0] * 256
        return when (pid) {
            Pid.LOAD -> d.copy(load = b[0] * 100f / 255f)
            Pid.COOLANT -> d.copy(coolant = b[0] - 40f)
            Pid.MAP -> d.copy(mapKpa = b[0].toFloat())
            Pid.RPM -> d.copy(rpm = w() / 4f)
            Pid.SPEED -> d.copy(speed = b[0].toFloat())
            Pid.IAT -> d.copy(intakeTemp = b[0] - 40f)
            Pid.MAF -> d.copy(maf = w() / 100f)
            Pid.BARO -> d.copy(baroKpa = b[0].toFloat())
            Pid.FUEL_RATE -> d.copy(fuelRateLph = w() / 20f)
            else -> d
        }
    }

    // ---------------------------------------------------------------- Demo

    fun startDemo() {
        stop()
        job = viewModelScope.launch {
            val sim = DemoSimulator()
            _state.update {
                it.copy(
                    trip = TripStats(),
                    history = emptyList(),
                    conn = ConnState.Connected("Simulador", "Modo demostración"),
                    demo = true,
                    fuelSource = FuelSource.LOAD,
                    ecuResponding = true,
                    introKey = it.introKey + 1,
                )
            }
            lastSampleAt = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(200)
                onSample(sim.step(0.2f))
            }
        }
    }

    fun disconnect() {
        stop()
        _state.update { it.copy(conn = ConnState.Idle, live = LiveData(), instLph = 0f, instL100 = null, gear = null) }
    }

    private fun stop() {
        job?.cancel()
        job = null
        closeSocket()
        if (_state.value.demo) {
            // El trayecto del simulador no se mezcla con el real.
            _state.update { it.copy(demo = false, trip = loadTrip(), history = emptyList()) }
        } else {
            saveTrip()
        }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
    }

    // ---------------------------------------------------------------- Cálculos

    private fun onSample(live: LiveData) {
        val now = SystemClock.elapsedRealtime()
        val dt = ((now - lastSampleAt) / 1000.0).coerceIn(0.0, 2.0)
        lastSampleAt = now

        if (learnsLoadOffset() && offsetLearner.feed(live)) {
            log("Carga de retención aprendida: %.1f %%".format(offsetLearner.offset))
            saveSettings(_settings.value.copy(loadOffset = offsetLearner.offset))
        }
        val cur = _state.value
        val lph = FuelModel.litersPerHour(live, cur.fuelSource, _settings.value)
        smoothLph = when {
            live.rpm < 300f -> 0f
            smoothLph == 0f -> lph
            else -> smoothLph + (lph - smoothLph) * 0.35f
        }
        val moving = live.speed >= 5f
        val l100 = if (moving) (smoothLph / live.speed * 100f).coerceAtMost(99.9f) else null

        val trip = cur.trip.add(live.speed, live.rpm, lph, live.boostBar, dt)

        var history = cur.history
        if (now - lastHistoryAt >= 1000) {
            lastHistoryAt = now
            history = (history + (l100 ?: Float.NaN)).takeLast(HISTORY_POINTS)
        }

        _state.update {
            it.copy(
                live = live,
                instLph = smoothLph,
                instL100 = l100,
                trip = trip,
                history = history,
                gear = GearEstimator.gear(live.rpm, live.speed),
            )
        }
        if (now - lastSaveAt > 15_000) {
            lastSaveAt = now
            saveTrip()
        }
        if (now - lastCsvAt >= 1000) {
            lastCsvAt = now
            val row = String.format(
                    Locale.US, "%s,%.0f,%.0f,%s,%s,%s,%s,%.2f,%s,%s",
                    timeFmt.format(Date()), live.rpm, live.speed,
                    live.load?.let { "%.1f".format(Locale.US, it) } ?: "",
                    live.boostBar?.let { "%.2f".format(Locale.US, it) } ?: "",
                    live.maf?.let { "%.1f".format(Locale.US, it) } ?: "",
                    live.coolant?.let { "%.0f".format(Locale.US, it) } ?: "",
                    smoothLph, l100?.let { "%.1f".format(Locale.US, it) } ?: "",
                    GearEstimator.gear(live.rpm, live.speed)?.toString() ?: "",
                )
            synchronized(csv) {
                csv.addLast(row)
                while (csv.size > CSV_MAX_ROWS) csv.removeFirst()
            }
        }
    }

    private fun learnsLoadOffset() = _state.value.fuelSource == FuelSource.LOAD && !_state.value.demo

    /** Escribe los datos registrados (hasta 2 h, 1 por segundo) en un CSV para compartir. */
    fun exportCsv(): File? {
        val rows = synchronized(csv) { csv.toList() }
        if (rows.isEmpty()) return null
        val f = File(getApplication<Application>().cacheDir, "toledo_obd_datos.csv")
        f.bufferedWriter().use { w ->
            val st = _settings.value
            w.appendLine("# Toledo OBD · motor: ${st.engine.label} · calibración: ${st.calibration} · carga retención: ${st.loadOffset ?: "sin aprender"}")
            w.appendLine("# ${supportedInfo}")
            w.appendLine("hora,rpm,kmh,carga_pct,turbo_bar,maf_gs,refrigerante_c,l_h,l_100km,marcha")
            rows.forEach { w.appendLine(it) }
        }
        return f
    }

    fun relearnLoadOffset() {
        offsetLearner.reset()
        saveSettings(_settings.value.copy(loadOffset = null))
    }

    fun resetTrip() {
        _state.update { it.copy(trip = TripStats(), history = emptyList()) }
        saveTrip()
    }

    // ---------------------------------------------------------------- Averías

    fun readDtcs() {
        if (_state.value.demo) {
            _dtc.value = DtcState.Result(listOf("P0401"), "Ejemplo del modo demostración")
            return
        }
        val obd = session ?: run {
            _dtc.value = DtcState.Failed("Conéctate primero al adaptador OBD")
            return
        }
        _dtc.value = DtcState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            _dtc.value = try {
                DtcState.Result(ioLock.withLock { obd.readDtcs() })
            } catch (e: Exception) {
                DtcState.Failed(e.message ?: "Error leyendo averías")
            }
        }
    }

    fun clearDtcs() {
        if (_state.value.demo) {
            _dtc.value = DtcState.Result(emptyList(), "Borrado (demo)")
            return
        }
        val obd = session ?: return
        _dtc.value = DtcState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            _dtc.value = try {
                val ok = ioLock.withLock { obd.clearDtcs() }
                val codes = ioLock.withLock { obd.readDtcs() }
                DtcState.Result(codes, if (ok) "Memoria de averías borrada" else "La centralita no confirmó el borrado")
            } catch (e: Exception) {
                DtcState.Failed(e.message ?: "Error borrando averías")
            }
        }
    }

    // ---------------------------------------------------------------- Ajustes

    fun setEngine(e: EnginePreset) = saveSettings(_settings.value.copy(engine = e))
    fun setCalibration(c: Float) = saveSettings(_settings.value.copy(calibration = c.coerceIn(0.5f, 2.0f)))
    fun setFuelPrice(p: Float) = saveSettings(_settings.value.copy(fuelPrice = p.coerceIn(0.5f, 4f)))

    /** Ajusta la calibración con los litros reales repostados tras vaciar el depósito del trayecto. */
    fun calibrateWithRefuel(realLiters: Float): Boolean {
        val estimated = _state.value.trip.fuelL
        if (estimated < 1.0 || realLiters <= 0f) return false
        setCalibration((_settings.value.calibration * realLiters / estimated).toFloat())
        return true
    }

    private fun loadSettings() = Settings(
        engine = EnginePreset.entries.firstOrNull { it.name == prefs.getString("engineName", null) }
            ?: EnginePreset.ASV_STAGE1,
        calibration = prefs.getFloat("calibration", 1f),
        fuelPrice = prefs.getFloat("fuelPrice", 1.55f),
        lastDevice = prefs.getString("lastDevice", null),
        loadOffset = prefs.getFloat("loadOffset", -1f).takeIf { it >= 0f },
    )

    private fun saveSettings(s: Settings) {
        _settings.value = s
        prefs.edit()
            .putString("engineName", s.engine.name)
            .putFloat("calibration", s.calibration)
            .putFloat("fuelPrice", s.fuelPrice)
            .putString("lastDevice", s.lastDevice)
            .putFloat("loadOffset", s.loadOffset ?: -1f)
            .apply()
    }

    private fun loadTrip() = TripStats(
        distanceKm = prefs.getFloat("tripKm", 0f).toDouble(),
        fuelL = prefs.getFloat("tripFuel", 0f).toDouble(),
        timeS = prefs.getFloat("tripTime", 0f).toDouble(),
        maxSpeed = prefs.getFloat("tripMax", 0f),
        maxBoost = prefs.getFloat("tripMaxBoost", 0f),
        maxRpm = prefs.getFloat("tripMaxRpm", 0f),
    )

    private fun saveTrip() {
        if (_state.value.demo) return
        val t = _state.value.trip
        prefs.edit()
            .putFloat("tripKm", t.distanceKm.toFloat())
            .putFloat("tripFuel", t.fuelL.toFloat())
            .putFloat("tripTime", t.timeS.toFloat())
            .putFloat("tripMax", t.maxSpeed)
            .putFloat("tripMaxBoost", t.maxBoost)
            .putFloat("tripMaxRpm", t.maxRpm)
            .apply()
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val VOLTAGE = -1
        const val HISTORY_POINTS = 120
        private const val CSV_MAX_ROWS = 7200
        private val OBD_HINTS = listOf("OBD", "ELM", "V-LINK", "VLINK", "KONNWEI", "VGATE", "ICAR", "CAR")
    }
}
