package com.eneko.toledoobd.data

import kotlin.math.abs

/** Lectura instantánea de la centralita (null = no disponible). */
data class LiveData(
    val rpm: Float = 0f,
    val speed: Float = 0f,
    val load: Float? = null,
    val coolant: Float? = null,
    val intakeTemp: Float? = null,
    val mapKpa: Float? = null,
    val baroKpa: Float? = null,
    val maf: Float? = null,
    val fuelRateLph: Float? = null,
    val voltage: Float? = null,
) {
    /** Presión de turbo relativa en bar. */
    val boostBar: Float?
        get() = mapKpa?.let { ((it - (baroKpa ?: 101.3f)) / 100f).coerceAtLeast(0f) }
}

enum class FuelSource(val label: String) {
    NONE("Sin datos"),
    FUEL_RATE("PID caudal combustible"),
    LOAD("Estimado por carga motor"),
    MAF("Estimado por caudalímetro"),
}

/** Coches para los que está preparada la app. */
enum class Vehicle(
    val title: String,
    val shortTitle: String,
    val rpmMax: Float,
    val rpmRed: Float,
    val speedMax: Float,
    /** Cilindrada de cada cilindro (L). */
    val cylVolumeL: Float,
    /** Ralentí normal con el motor caliente. */
    val idleRpm: Float,
    /** Fondo de escala del reloj del turbo y presión a partir de la que se avisa (bar relativos). */
    val boostGaugeMax: Float,
    val overboostBar: Float,
    /** Consumo por debajo del cual se pinta verde y a partir del cual rojo (L/100 km). */
    val ecoGood: Float,
    val ecoBad: Float,
) {
    // 1.9 TDI VE: ralentí ~900, turbo de serie ~1,0 bar (Stage 1 ~1,3)
    TOLEDO("SEAT TOLEDO 1.9 TDI", "TOLEDO TDI", 5000f, 4500f, 220f, 0.4745f, 900f, 2.0f, 1.6f, 5.5f, 9f),

    // M47N2/N47 common rail: ralentí ~780, turbo de geometría variable hasta ~1,5 bar de serie
    BMW_E60("BMW 520d E60", "BMW 520d", 5000f, 4500f, 260f, 0.4988f, 780f, 2.5f, 1.9f, 6.5f, 10f),
}

/** Motores de cada coche. maxIqMg ≈ inyección máxima por cilindro y ciclo (orientativa). */
enum class EnginePreset(val vehicle: Vehicle, val label: String, val maxIqMg: Float) {
    ALH(Vehicle.TOLEDO, "1.9 TDI 90 CV (ALH/AGR)", 45f),
    ASV(Vehicle.TOLEDO, "1.9 TDI 110 CV (ASV/AHF)", 52f),
    ASV_STAGE1(Vehicle.TOLEDO, "1.9 TDI 110 CV Stage 1 (~140 CV)", 61f),
    ASZ(Vehicle.TOLEDO, "1.9 TDI 130 CV (ASZ)", 58f),
    ARL(Vehicle.TOLEDO, "1.9 TDI 150 CV (ARL)", 63f),
    BMW_M47(Vehicle.BMW_E60, "2.0d 163 CV (M47N2, hasta 09/2007)", 68f),
    BMW_N47(Vehicle.BMW_E60, "2.0d 177 CV (N47, desde 09/2007)", 72f),
    ;

    companion object {
        fun defaultFor(v: Vehicle) = if (v == Vehicle.TOLEDO) ASV_STAGE1 else BMW_M47
        fun of(v: Vehicle) = entries.filter { it.vehicle == v }
    }
}

/**
 * Cajas de cambio: km/h por cada 1000 rpm en cada marcha.
 * Toledo: 02J con 205/55 R16. BMW: neumático de ~2,03 m de circunferencia (225/55 R16,
 * 225/50 R17, 245/45 R17…) y grupo final estimado, por eso la marcha del BMW es orientativa.
 */
enum class Gearbox(val vehicle: Vehicle, val label: String, val kmhPer1000: FloatArray) {
    TOLEDO_02J(Vehicle.TOLEDO, "Manual 5 velocidades (02J)", floatArrayOf(9.3f, 16.6f, 25.8f, 36.2f, 46.2f)),

    // 5,14 · 2,83 · 1,79 · 1,26 · 1,00 · 0,83 con grupo ≈2,64
    BMW_MANUAL(
        Vehicle.BMW_E60, "Manual 6 velocidades (ZF S6-37)",
        floatArrayOf(8.98f, 16.30f, 25.78f, 36.62f, 46.14f, 55.59f),
    ),

    // 4,17 · 2,34 · 1,52 · 1,14 · 0,87 · 0,69 con grupo ≈2,81
    BMW_AUTO(
        Vehicle.BMW_E60, "Automática 6 velocidades (ZF 6HP)",
        floatArrayOf(10.40f, 18.52f, 28.52f, 38.02f, 49.82f, 62.82f),
    ),
    ;

    companion object {
        fun defaultFor(v: Vehicle) = entries.first { it.vehicle == v }
        fun of(v: Vehicle) = entries.filter { it.vehicle == v }
    }
}

data class Settings(
    val engine: EnginePreset = EnginePreset.ASV_STAGE1,
    val gearbox: Gearbox = Gearbox.TOLEDO_02J,
    val calibration: Float = 1.0f,
    val fuelPrice: Float = 1.55f,
    val lastDevice: String? = null,
    /** Carga (%) que marca la ECU con inyección cero (en retención). null = aún sin aprender. */
    val loadOffset: Float? = null,
) {
    val vehicle: Vehicle get() = engine.vehicle
}

/**
 * Cálculo de consumo para un diésel sin PID de caudal de combustible.
 *
 * En los diésel el PID 04 (carga calculada) representa la cantidad inyectada respecto
 * al máximo, así que: mg/embolada ≈ carga · IQmáx. Con 4 cilindros y 4 tiempos hay
 * rpm/30 inyecciones por segundo. Gasóleo: 0,835 kg/L.
 */
object FuelModel {
    private const val DIESEL_DENSITY_G_PER_L = 835f
    private const val DIESEL_AFR_AVG = 22f

    fun source(supported: Set<Int>): FuelSource = when {
        0x5E in supported -> FuelSource.FUEL_RATE
        0x04 in supported -> FuelSource.LOAD
        0x10 in supported -> FuelSource.MAF
        else -> FuelSource.NONE
    }

    /** Relación aire/gasoil mínima que permite el limitador de humos (λ ≈ 1,17). */
    private const val SMOKE_AFR = 17f
    private const val VOLUMETRIC_EFF = 0.85f

    /** Aire que entra en cada cilindro por ciclo (mg), a partir de presión y temperatura de admisión. */
    fun airPerStrokeMg(d: LiveData, cylVolumeL: Float = Vehicle.TOLEDO.cylVolumeL): Float? {
        val map = d.mapKpa ?: return null
        val tK = (d.intakeTemp ?: 30f) + 273.15f
        return VOLUMETRIC_EFF * map * 1000f * (cylVolumeL / 1000f) / (287f * tK) * 1_000_000f
    }

    fun maxInjectionMg(d: LiveData, s: Settings): Float {
        val byCurve = s.engine.maxIqMg * FullLoadCurve.shape(d.rpm)
        val air = airPerStrokeMg(d, s.vehicle.cylVolumeL) ?: return byCurve
        return minOf(air / SMOKE_AFR, s.engine.maxIqMg)
    }

    fun litersPerHour(d: LiveData, source: FuelSource, s: Settings): Float {
        if (d.rpm < 300f) return 0f
        val lph = when (source) {
            FuelSource.FUEL_RATE -> d.fuelRateLph ?: 0f
            FuelSource.LOAD -> {
                val load = d.load ?: return 0f
                // La EDC15 no marca 0 % con inyección cero: se descuenta la carga de retención.
                val zero = s.loadOffset ?: 0f
                val frac = ((load - zero) / (100f - zero)).coerceIn(0f, 1f)
                // La EDC15 da la carga como % de la inyección máxima permitida en ese momento,
                // que la limita el aire que entra (limitador de humos). Con la presión del
                // colector se calcula ese aire; si no hay dato, se usa la curva típica por rpm.
                val iqMg = frac * maxInjectionMg(d, s)
                val gramsPerSec = iqMg * d.rpm / 30f / 1000f
                gramsPerSec * 3600f / DIESEL_DENSITY_G_PER_L
            }
            FuelSource.MAF -> {
                val maf = d.maf ?: return 0f
                maf / DIESEL_AFR_AVG * 3600f / DIESEL_DENSITY_G_PER_L
            }
            FuelSource.NONE -> 0f
        }
        return lph * s.calibration
    }
}

/**
 * Forma de la curva de inyección a plena carga de un 1.9 TDI PD/VE (limitador de humos y par),
 * normalizada a 1 en el máximo (~1900-2500 rpm). Al ralentí el máximo permitido es la mitad.
 */
object FullLoadCurve {
    private val rpm = floatArrayOf(800f, 1000f, 1250f, 1500f, 1750f, 2000f, 2500f, 3000f, 3500f, 4000f, 4500f)
    private val k = floatArrayOf(0.48f, 0.55f, 0.68f, 0.84f, 0.97f, 1.0f, 0.98f, 0.90f, 0.77f, 0.65f, 0.52f)

    fun shape(r: Float): Float {
        if (r <= rpm.first()) return k.first()
        if (r >= rpm.last()) return k.last()
        val i = rpm.indexOfFirst { it >= r }
        val t = (r - rpm[i - 1]) / (rpm[i] - rpm[i - 1])
        return k[i - 1] + (k[i] - k[i - 1]) * t
    }
}

/**
 * Aprende qué carga marca la centralita cuando no inyecta nada: en retención
 * (marcha metida, pie fuera del acelerador) el diésel corta la inyección, así que
 * la carga mínima vista decelerando en marcha es el "cero" real.
 */
class LoadOffsetLearner(initial: Float?) {
    var offset: Float? = initial
        private set
    private var lastSpeed = -1f
    private var slowingDown = false
    private var streak = 0
    private var streakMax = 0f

    /** Devuelve true si el valor aprendido ha cambiado. */
    fun feed(d: LiveData): Boolean {
        // La velocidad no se lee en todos los ciclos: la tendencia se decide cuando cambia.
        if (lastSpeed >= 0f && d.speed != lastSpeed) slowingDown = d.speed < lastSpeed
        lastSpeed = d.speed
        val load = d.load
        val coasting = slowingDown && d.speed >= 25f && d.rpm >= 1200f && (d.boostBar ?: 0f) < 0.15f
        if (load == null || !coasting) {
            streak = 0
            return false
        }
        streakMax = if (streak == 0) load else maxOf(streakMax, load)
        streak++
        // ~2 s seguidos en retención: se toma la mayor de esas lecturas para evitar picos sueltos.
        if (streak >= 4 && (offset == null || streakMax < offset!! - 0.1f) && streakMax < 40f) {
            offset = streakMax
            return true
        }
        return false
    }

    /** Cambia el valor de partida (al cambiar de coche). */
    fun restore(value: Float?) {
        reset()
        offset = value
    }

    fun reset() {
        offset = null
        streak = 0
        lastSpeed = -1f
        slowingDown = false
    }
}

/** Marcha estimada a partir de la relación km/h por cada 1000 rpm y la tabla de la caja. */
object GearEstimator {
    fun gear(rpm: Float, speed: Float, box: Gearbox = Gearbox.TOLEDO_02J): Int? {
        if (speed < 4f || rpm < 600f) return null
        val table = box.kmhPer1000
        val ratio = speed / (rpm / 1000f)
        var best = -1
        var bestErr = Float.MAX_VALUE
        table.forEachIndexed { i, r ->
            val err = abs(ratio - r) / r
            if (err < bestErr) {
                bestErr = err
                best = i
            }
        }
        // Tolerancia según lo juntas que estén las marchas (en 6 velocidades 5ª y 6ª están a ~20 %).
        val minStep = (1 until table.size).minOf { table[it] / table[it - 1] } - 1f
        return if (bestErr < minOf(0.13f, minStep * 0.45f)) best + 1 else null
    }
}

data class TripStats(
    val distanceKm: Double = 0.0,
    val fuelL: Double = 0.0,
    val timeS: Double = 0.0,
    val maxSpeed: Float = 0f,
    val maxBoost: Float = 0f,
    val maxRpm: Float = 0f,
) {
    val avgL100: Float? get() = if (distanceKm > 0.3) (fuelL / distanceKm * 100).toFloat() else null
    val avgSpeed: Float? get() = if (timeS > 30) (distanceKm / (timeS / 3600)).toFloat() else null

    fun add(speed: Float, rpm: Float, lph: Float, boost: Float?, dtS: Double): TripStats {
        if (rpm < 300f) return this
        return copy(
            distanceKm = distanceKm + speed * dtS / 3600.0,
            fuelL = fuelL + lph * dtS / 3600.0,
            timeS = timeS + dtS,
            maxSpeed = maxOf(maxSpeed, speed),
            maxBoost = maxOf(maxBoost, boost ?: 0f),
            maxRpm = maxOf(maxRpm, rpm),
        )
    }
}

/** Descripción breve de códigos genéricos habituales en el 1.9 TDI. */
object DtcInfo {
    private val known = mapOf(
        "P0100" to "Caudalímetro (MAF): fallo de circuito",
        "P0101" to "Caudalímetro (MAF): rango/rendimiento",
        "P0102" to "Caudalímetro (MAF): señal baja",
        "P0103" to "Caudalímetro (MAF): señal alta",
        "P0105" to "Sensor presión colector (MAP): circuito",
        "P0110" to "Sensor temperatura aire admisión",
        "P0115" to "Sensor temperatura refrigerante",
        "P0116" to "Sensor temperatura refrigerante: rango",
        "P0170" to "Ajuste de combustible",
        "P0180" to "Sensor temperatura combustible",
        "P0190" to "Sensor presión combustible",
        "P0216" to "Control de avance de inyección",
        "P0234" to "Sobrepresión del turbo",
        "P0235" to "Sensor presión turbo: circuito",
        "P0299" to "Presión de turbo insuficiente",
        "P0335" to "Sensor de régimen (cigüeñal)",
        "P0380" to "Calentadores: circuito",
        "P0400" to "EGR: caudal",
        "P0401" to "EGR: caudal insuficiente",
        "P0402" to "EGR: caudal excesivo",
        "P0403" to "EGR: circuito de control",
        "P0500" to "Sensor de velocidad del vehículo",
        "P0501" to "Sensor de velocidad: rango",
        "P0560" to "Tensión del sistema",
        "P0562" to "Tensión del sistema baja",
        "P0563" to "Tensión del sistema alta",
        "P0605" to "Error interno de la centralita",
        "P0670" to "Relé de calentadores",
        "P1403" to "EGR: desviación de regulación (VAG)",
        "P1550" to "Presión de turbo: desviación de regulación (VAG)",
        "P0045" to "Actuador del turbo: circuito",
        "P0087" to "Presión del rail de combustible demasiado baja",
        "P0088" to "Presión del rail de combustible demasiado alta",
        "P0093" to "Fuga de combustible detectada",
        "P2002" to "Filtro de partículas (DPF): eficiencia baja",
        "P2452" to "Sensor presión diferencial del DPF",
        "P2453" to "Sensor presión diferencial del DPF: rango",
        "P2463" to "Filtro de partículas (DPF): exceso de hollín",
    )

    fun describe(code: String): String = known[code] ?: when (code.firstOrNull()) {
        'P' -> "Motor / transmisión"
        'C' -> "Chasis"
        'B' -> "Carrocería"
        'U' -> "Red de comunicación"
        else -> ""
    }
}
