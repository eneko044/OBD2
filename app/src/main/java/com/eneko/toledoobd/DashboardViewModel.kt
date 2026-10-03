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
import com.eneko.toledoobd.data.DiagState
import com.eneko.toledoobd.data.Diagnostics
import com.eneko.toledoobd.data.EnginePreset
import com.eneko.toledoobd.data.FloatMode
import com.eneko.toledoobd.data.FuelModel
import com.eneko.toledoobd.data.FuelSource
import com.eneko.toledoobd.data.GearEstimator
import com.eneko.toledoobd.data.Gearbox
import com.eneko.toledoobd.data.LiveData
import com.eneko.toledoobd.data.LoadOffsetLearner
import com.eneko.toledoobd.data.Settings
import com.eneko.toledoobd.data.TripStats
import com.eneko.toledoobd.data.Vehicle
import com.eneko.toledoobd.obd.EcuSilentException
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
    /** Lecturas completas (rpm + velocidad + consumo) por segundo. */
    val updateHz: Float = 0f,
    /** Cada cuántos ciclos llega una lectura nueva de velocidad. */
    val speedEveryCycles: Int = 2,
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
    private var prevSpeed = 0f
    private var prevLph = 0f
    private var lastGoodAt = 0L
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
            // Los ELM327 clon a veces cortan el enlace nada más conectar ("Broken pipe") o en marcha:
            // se reintenta solo, cambiando el tipo de socket en cada intento.
            var attempt = 0
            while (isActive) {
                var s: BluetoothSocket? = null
                try {
                    _state.update {
                        it.copy(conn = ConnState.Connecting(if (attempt == 0) "Conectando por Bluetooth…" else "Reintentando conexión ($attempt/$MAX_RETRIES)…"))
                    }
                    val mgr = getApplication<Application>().getSystemService(BluetoothManager::class.java)
                    val adapter = mgr?.adapter ?: throw IOException("Este móvil no tiene Bluetooth")
                    val device = adapter.getRemoteDevice(address)
                    val name = device.name ?: address
                    log("Conectando a $name ($address), intento ${attempt + 1}")
                    s = openSocket(device, attempt)
                    socket = s
                    delay(400) // algunos clones necesitan un momento antes del primer comando
                    val obd = ObdSession(ElmIo(s.inputStream, s.outputStream, ::log), ::log)
                    ioLock.withLock {
                        obd.initialize { step -> _state.update { it.copy(conn = ConnState.Connecting(step)) } }
                    }
                    session = obd
                    attempt = 0
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
                    attempt++
                    if (e is EcuSilentException || attempt > MAX_RETRIES) {
                        _state.update { it.copy(conn = ConnState.Error(friendlyError(e))) }
                        break
                    }
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
                delay(2000)
            }
        }
    }

    private fun friendlyError(e: Exception): String {
        val m = e.message.orEmpty().lowercase()
        return when {
            e is EcuSilentException -> e.message ?: "La centralita no responde"
            "broken pipe" in m || "reset" in m || "closed" in m || "socket" in m ->
                "Se cortó la conexión con el adaptador. Desenchúfalo 10 s del conector OBD, " +
                    "cierra otras apps OBD y vuelve a conectar."
            "timeout" in m || "timed out" in m -> "El adaptador no contesta. ¿Está enchufado y con el contacto puesto?"
            else -> e.message ?: "Error de conexión"
        }
    }

    @SuppressLint("MissingPermission")
    private fun openSocket(device: BluetoothDevice, rotate: Int = 0): BluetoothSocket {
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
        val order = attempts.indices.map { attempts[(it + rotate) % attempts.size] }
        for ((label, make) in order) {
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
        // rpm en cada ciclo; velocidad y dato de consumo alternados (cambian más despacio
        // que la aguja del cuentarrevoluciones y así cada ciclo son solo 2 peticiones).
        val fuelPid = when (source) {
            FuelSource.FUEL_RATE -> Pid.FUEL_RATE
            FuelSource.LOAD -> Pid.LOAD
            FuelSource.MAF -> Pid.MAF
            FuelSource.NONE -> null
        }
        // Con consumo por carga, la presión del colector (aire disponible) entra en la rotación rápida.
        val mapFast = source == FuelSource.LOAD && Pid.MAP in sup
        val alternate = listOfNotNull(Pid.SPEED, fuelPid, if (mapFast) Pid.MAP else null)
        _state.update { it.copy(speedEveryCycles = alternate.size) }
        val diagAlternate = listOfNotNull(Pid.SPEED, fuelPid, Pid.IAT.takeIf { it in sup })
        // El caudalímetro no entra en el cálculo, pero se registra (sirve para ver si la EGR actúa).
        val slow = listOf(Pid.MAP, Pid.COOLANT, Pid.MAP, Pid.IAT, Pid.MAF, Pid.MAP, VOLTAGE, Pid.BARO)
            .filter { it == VOLTAGE || it in sup }
            .filter { !(mapFast && it == Pid.MAP) }
        val failures = mutableMapOf<Int, Int>()
        var live = LiveData()
        var slowIdx = 0
        var rpmMisses = 0
        var cycle = 0
        var hz = 0f
        // Tras una reconexión rápida se sigue contando desde la última lectura; si hace mucho, de cero.
        if (SystemClock.elapsedRealtime() - lastSampleAt > GAP_HOLD_MS) {
            lastSampleAt = SystemClock.elapsedRealtime()
            prevSpeed = 0f
            prevLph = 0f
        }
        lastGoodAt = SystemClock.elapsedRealtime()

        while (currentCoroutineContext().isActive) {
            val cycleStart = SystemClock.elapsedRealtime()
            cycle++
            ioLock.withLock {
                // En modo diagnóstico se leen caudalímetro y presión del colector en cada ciclo,
                // para comparar el aire de ambos en el mismo instante.
                val fast = if (_diag.value.active && Pid.MAF in sup) {
                    listOfNotNull(Pid.RPM, Pid.MAF, Pid.MAP.takeIf { it in sup }, diagAlternate[cycle % diagAlternate.size])
                } else {
                    listOf(Pid.RPM, alternate[cycle % alternate.size])
                }
                for (pid in fast) {
                    val b = obd.query(pid)
                    if (b == null) {
                        if (pid == Pid.RPM) rpmMisses++
                    } else {
                        if (pid == Pid.RPM) {
                            rpmMisses = 0
                            lastGoodAt = SystemClock.elapsedRealtime()
                        }
                        live = apply(live, pid, b)
                    }
                }
                // Los datos lentos (temperaturas, turbo, batería) solo cada 4 ciclos,
                // para que rpm, velocidad y consumo se refresquen lo más rápido posible.
                if (slow.isNotEmpty() && cycle % 4 == 0) {
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
            if (rpmMisses == 2 && obd.tunedTimeout) {
                // Con el tiempo recortado la ECU ha dejado de contestar: volver al estándar.
                ioLock.withLock { obd.relaxTimeout() }
            }
            val responding = rpmMisses < 4
            // Un corte corto en marcha (adaptador o ECU que no contesta unos segundos) no significa que
            // el coche se haya parado: se mantienen los últimos valores para no perder kilómetros.
            if (!responding && SystemClock.elapsedRealtime() - lastGoodAt > GAP_HOLD_MS) {
                live = live.copy(rpm = 0f, speed = 0f)
            }
            val ms = (SystemClock.elapsedRealtime() - cycleStart).coerceAtLeast(1)
            hz = if (hz == 0f) 1000f / ms else hz + (1000f / ms - hz) * 0.2f
            _state.update { it.copy(ecuResponding = responding, updateHz = hz) }
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
            val st = _settings.value
            val sim = DemoSimulator(st.gearbox.kmhPer1000, st.vehicle.cylVolumeL, st.vehicle.idleRpm)
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
            prevSpeed = 0f
            prevLph = 0f
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

    private fun onSample(raw: LiveData) {
        // La velocidad OBD puede no coincidir con la real (en el Toledo da ~9 % menos): se corrige
        // con el factor del coche, y con ella la distancia, el L/100 y la marcha.
        val factor = _settings.value.speedFactor
        val live = if (factor != 1f && !_state.value.demo) raw.copy(speed = raw.speed * factor) else raw
        val now = SystemClock.elapsedRealtime()
        // Hasta 45 s entre lecturas (cortes y reconexiones) se integra con la media de antes y después.
        val dt = ((now - lastSampleAt) / 1000.0).coerceIn(0.0, GAP_HOLD_MS / 1000.0)
        lastSampleAt = now
        val st = _settings.value
        if (_diag.value.active) _diag.update { Diagnostics.feed(it, live, dt.toFloat(), st.vehicle.cylVolumeL) }

        if (learnsLoadOffset() && offsetLearner.feed(live)) {
            log("Carga de retención aprendida: %.1f %%".format(offsetLearner.offset))
            saveSettings(_settings.value.copy(loadOffset = offsetLearner.offset))
        }
        val cur = _state.value
        val lph = FuelModel.litersPerHour(live, cur.fuelSource, _settings.value)
        smoothLph = when {
            live.rpm < 300f -> 0f
            smoothLph == 0f -> lph
            else -> smoothLph + (lph - smoothLph) * 0.6f
        }
        val moving = live.speed >= 5f
        val l100 = if (moving) (smoothLph / live.speed * 100f).coerceAtMost(99.9f) else null

        // Trapecio: distancia y gasoil con la media entre la lectura anterior y la actual.
        val trip = cur.trip.add((prevSpeed + live.speed) / 2f, live.rpm, (prevLph + lph) / 2f, live.boostBar, dt)
        prevSpeed = live.speed
        prevLph = lph

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
                gear = GearEstimator.gear(live.rpm, live.speed, st.gearbox),
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
                    GearEstimator.gear(live.rpm, live.speed, st.gearbox)?.toString() ?: "",
                ) + "," + listOf(
                    live.intakeTemp?.let { "%.0f".format(Locale.US, it) },
                    live.maf?.takeIf { live.rpm > 300f }?.let { "%.0f".format(Locale.US, it * 1000f / (live.rpm / 30f)) },
                    FuelModel.airPerStrokeMg(live, st.vehicle.cylVolumeL)?.let { "%.0f".format(Locale.US, it) },
                ).joinToString(",") { it ?: "" }
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
            w.appendLine("# ${st.vehicle.title} · motor: ${st.engine.label} · caja: ${st.gearbox.label} · calibración: ${st.calibration} · carga retención: ${st.loadOffset ?: "sin aprender"}")
            w.appendLine("# ${supportedInfo}")
            w.appendLine("hora,rpm,kmh,carga_pct,turbo_bar,maf_gs,refrigerante_c,l_h,l_100km,marcha,admision_c,aire_maf_mg,aire_map_mg")
            rows.forEach { w.appendLine(it) }
        }
        return f
    }

    fun relearnLoadOffset() {
        offsetLearner.reset()
        saveSettings(_settings.value.copy(loadOffset = null))
    }

    // ---------------------------------------------------------------- Diagnóstico

    private val _diag = MutableStateFlow(DiagState())
    val diag: StateFlow<DiagState> = _diag.asStateFlow()

    fun startDiagnostics() {
        _diag.value = Diagnostics.start(_state.value.live)
        log("Modo diagnóstico iniciado")
    }

    fun stopDiagnostics() {
        _diag.update { it.copy(active = false) }
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

    fun setEngine(e: EnginePreset) {
        if (e.vehicle != _settings.value.vehicle) setVehicle(e.vehicle)
        saveSettings(_settings.value.copy(engine = e))
    }

    fun setGearbox(g: Gearbox) = saveSettings(_settings.value.copy(gearbox = g))

    /**
     * Cambia de coche. Cada coche guarda su propio trayecto, calibración, cero de carga, motor
     * y caja, así que al cambiar se guarda lo del coche actual y se carga lo del nuevo.
     */
    fun setVehicle(v: Vehicle) {
        if (v == _settings.value.vehicle) return
        if (!_state.value.demo) saveTrip()
        prefs.edit().putString("vehicle", v.name).apply()
        val s = loadSettings()
        _settings.value = s
        offsetLearner.restore(s.loadOffset)
        _state.update { it.copy(trip = if (it.demo) TripStats() else loadTrip(), history = emptyList(), gear = null) }
        log("Coche seleccionado: ${v.title}")
    }

    fun setCalibration(c: Float) = saveSettings(_settings.value.copy(calibration = c.coerceIn(0.5f, 2.0f)))
    fun setFuelPrice(p: Float) = saveSettings(_settings.value.copy(fuelPrice = p.coerceIn(0.5f, 4f)))

    /** Ajusta la calibración con los litros reales repostados tras vaciar el depósito del trayecto. */
    fun setFloatMode(m: FloatMode) = saveSettings(_settings.value.copy(floatMode = m))

    fun setSpeedFactor(f: Float) = saveSettings(_settings.value.copy(speedFactor = f.coerceIn(0.8f, 1.25f)))

    /** Ajusta la velocidad/distancia con los km reales (cuentakilómetros o mapa) del trayecto actual. */
    fun calibrateDistance(realKm: Float): Boolean {
        val f = Settings.speedFactorFrom(_settings.value.speedFactor, _state.value.trip.distanceKm, realKm) ?: return false
        setSpeedFactor(f)
        log("Corrección de velocidad: ×%.3f".format(f))
        return true
    }

    fun calibrateWithRefuel(realLiters: Float): Boolean {
        val estimated = _state.value.trip.fuelL
        if (estimated < 1.0 || realLiters <= 0f) return false
        setCalibration((_settings.value.calibration * realLiters / estimated).toFloat())
        return true
    }

    private fun currentVehicle(): Vehicle =
        Vehicle.entries.firstOrNull { it.name == prefs.getString("vehicle", null) } ?: Vehicle.TOLEDO

    /** Clave de ajustes propia de cada coche. El Toledo usa las claves de siempre (compatibles). */
    private fun vk(v: Vehicle, name: String) = if (v == Vehicle.TOLEDO) name else "${v.name}_$name"

    private fun loadSettings(): Settings {
        val v = currentVehicle()
        return Settings(
            engine = EnginePreset.of(v).firstOrNull { it.name == prefs.getString(vk(v, "engineName"), null) }
                ?: EnginePreset.defaultFor(v),
            gearbox = Gearbox.of(v).firstOrNull { it.name == prefs.getString(vk(v, "gearbox"), null) }
                ?: Gearbox.defaultFor(v),
            calibration = prefs.getFloat(vk(v, "calibration"), 1f),
            fuelPrice = prefs.getFloat("fuelPrice", 1.55f),
            lastDevice = prefs.getString("lastDevice", null),
            loadOffset = prefs.getFloat(vk(v, "loadOffset"), -1f).takeIf { it >= 0f },
            speedFactor = prefs.getFloat(vk(v, "speedFactor"), 1f),
            floatMode = FloatMode.entries.firstOrNull { it.name == prefs.getString("floatMode", null) } ?: FloatMode.BOTH,
        )
    }

    private fun saveSettings(s: Settings) {
        _settings.value = s
        val v = s.vehicle
        prefs.edit()
            .putString("vehicle", v.name)
            .putString(vk(v, "engineName"), s.engine.name)
            .putString(vk(v, "gearbox"), s.gearbox.name)
            .putFloat(vk(v, "calibration"), s.calibration)
            .putFloat("fuelPrice", s.fuelPrice)
            .putString("lastDevice", s.lastDevice)
            .putFloat(vk(v, "loadOffset"), s.loadOffset ?: -1f)
            .putFloat(vk(v, "speedFactor"), s.speedFactor)
            .putString("floatMode", s.floatMode.name)
            .apply()
    }

    private fun loadTrip(): TripStats {
        val v = currentVehicle()
        return TripStats(
            distanceKm = prefs.getFloat(vk(v, "tripKm"), 0f).toDouble(),
            fuelL = prefs.getFloat(vk(v, "tripFuel"), 0f).toDouble(),
            timeS = prefs.getFloat(vk(v, "tripTime"), 0f).toDouble(),
            maxSpeed = prefs.getFloat(vk(v, "tripMax"), 0f),
            maxBoost = prefs.getFloat(vk(v, "tripMaxBoost"), 0f),
            maxRpm = prefs.getFloat(vk(v, "tripMaxRpm"), 0f),
        )
    }

    private fun saveTrip() {
        if (_state.value.demo) return
        val t = _state.value.trip
        val v = _settings.value.vehicle
        prefs.edit()
            .putFloat(vk(v, "tripKm"), t.distanceKm.toFloat())
            .putFloat(vk(v, "tripFuel"), t.fuelL.toFloat())
            .putFloat(vk(v, "tripTime"), t.timeS.toFloat())
            .putFloat(vk(v, "tripMax"), t.maxSpeed)
            .putFloat(vk(v, "tripMaxBoost"), t.maxBoost)
            .putFloat(vk(v, "tripMaxRpm"), t.maxRpm)
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
        private const val MAX_RETRIES = 3
        // Una reconexión por línea K (ATZ + búsqueda de protocolo + ajuste de tiempos) puede tardar ~30 s.
        private const val GAP_HOLD_MS = 45_000L
        private val OBD_HINTS = listOf("OBD", "ELM", "V-LINK", "VLINK", "KONNWEI", "VGATE", "ICAR", "CAR")
    }
}
