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

/** Variantes del 1.9 TDI montadas en el Toledo II (1999-2004). */
enum class EnginePreset(val label: String, val maxIqMg: Float) {
    ALH("1.9 TDI 90 CV (ALH/AGR)", 45f),
    ASV("1.9 TDI 110 CV (ASV/AHF)", 52f),
    ASV_STAGE1("1.9 TDI 110 CV Stage 1 (~140 CV)", 61f),
    ASZ("1.9 TDI 130 CV (ASZ)", 58f),
    ARL("1.9 TDI 150 CV (ARL)", 63f),
}

data class Settings(
    val engine: EnginePreset = EnginePreset.ASV_STAGE1,
    val calibration: Float = 1.0f,
    val fuelPrice: Float = 1.55f,
    val lastDevice: String? = null,
    /** Carga (%) que marca la ECU con inyección cero (en retención). null = aún sin aprender. */
    val loadOffset: Float? = null,
)

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

    fun litersPerHour(d: LiveData, source: FuelSource, s: Settings): Float {
        if (d.rpm < 300f) return 0f
        val lph = when (source) {
            FuelSource.FUEL_RATE -> d.fuelRateLph ?: 0f
            FuelSource.LOAD -> {
                val load = d.load ?: return 0f
                // La EDC15 no marca 0 % con inyección cero: se descuenta la carga de retención.
                val zero = s.loadOffset ?: 0f
                val frac = ((load - zero) / (100f - zero)).coerceIn(0f, 1f)
                val iqMg = frac * s.engine.maxIqMg
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

    fun reset() {
        offset = null
        streak = 0
        lastSpeed = -1f
        slowingDown = false
    }
}

/**
 * Marcha estimada a partir de la relación km/h por cada 1000 rpm
 * (caja 02J de 5 marchas, neumático 205/55 R16).
 */
object GearEstimator {
    private val kmhPer1000 = floatArrayOf(9.3f, 16.6f, 25.8f, 36.2f, 46.2f)

    fun gear(rpm: Float, speed: Float): Int? {
        if (speed < 4f || rpm < 600f) return null
        val ratio = speed / (rpm / 1000f)
        var best = -1
        var bestErr = Float.MAX_VALUE
        kmhPer1000.forEachIndexed { i, r ->
            val err = abs(ratio - r) / r
            if (err < bestErr) {
                bestErr = err
                best = i
            }
        }
        return if (bestErr < 0.13f) best + 1 else null
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
    )

    fun describe(code: String): String = known[code] ?: when (code.firstOrNull()) {
        'P' -> "Motor / transmisión"
        'C' -> "Chasis"
        'B' -> "Carrocería"
        'U' -> "Red de comunicación"
        else -> ""
    }
}
