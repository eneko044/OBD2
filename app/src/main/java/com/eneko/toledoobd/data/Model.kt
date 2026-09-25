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
    ASZ("1.9 TDI 130 CV (ASZ)", 58f),
    ARL("1.9 TDI 150 CV (ARL)", 63f),
}

data class Settings(
    val engine: EnginePreset = EnginePreset.ASV,
    val calibration: Float = 1.0f,
    val fuelPrice: Float = 1.55f,
    val lastDevice: String? = null,
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
                val iqMg = load / 100f * s.engine.maxIqMg
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
) {
    val avgL100: Float? get() = if (distanceKm > 0.3) (fuelL / distanceKm * 100).toFloat() else null
    val avgSpeed: Float? get() = if (timeS > 30) (distanceKm / (timeS / 3600)).toFloat() else null

    fun add(speed: Float, rpm: Float, lph: Float, dtS: Double): TripStats {
        if (rpm < 300f) return this
        return copy(
            distanceKm = distanceKm + speed * dtS / 3600.0,
            fuelL = fuelL + lph * dtS / 3600.0,
            timeS = timeS + dtS,
            maxSpeed = maxOf(maxSpeed, speed),
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
