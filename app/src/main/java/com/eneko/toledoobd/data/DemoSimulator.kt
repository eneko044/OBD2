package com.eneko.toledoobd.data

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Genera datos de conducción creíbles para probar el panel sin coche. */
class DemoSimulator(
    private val kmhPer1000: FloatArray = Gearbox.TOLEDO_02J.kmhPer1000,
    private val cylVolumeL: Float = Vehicle.TOLEDO.cylVolumeL,
    private val idleRpm: Float = Vehicle.TOLEDO.idleRpm,
) {
    private data class Leg(val targetKmh: Float, val holdS: Float)

    private val route = listOf(
        Leg(0f, 4f), Leg(50f, 12f), Leg(30f, 5f), Leg(60f, 10f), Leg(0f, 6f),
        Leg(90f, 10f), Leg(120f, 18f), Leg(100f, 8f), Leg(130f, 10f), Leg(70f, 8f), Leg(0f, 5f),
    )

    private var leg = 0
    private var holdLeft = route[0].holdS
    private var speed = 0f
    private var gear = 1
    private var coolant = 32f
    private var shiftPause = 0f

    fun step(dt: Float): LiveData {
        val target = route[leg].targetKmh
        val diff = target - speed
        val accelerating: Boolean
        val braking: Boolean
        if (shiftPause > 0f) {
            shiftPause -= dt
            accelerating = false
            braking = false
        } else if (diff > 1f) {
            speed += min(diff, (if (speed < 60) 9f else 4.5f) * dt)
            accelerating = true
            braking = false
        } else if (diff < -1f) {
            speed += max(diff, -10f * dt)
            accelerating = false
            braking = true
        } else {
            speed = target
            accelerating = false
            braking = false
            holdLeft -= dt
            if (holdLeft <= 0f) {
                leg = (leg + 1) % route.size
                holdLeft = route[leg].holdS
            }
        }
        speed = speed.coerceAtLeast(0f)

        // Cambio de marcha: sube a ~2600 rpm acelerando, baja por debajo de 1300.
        val rpmInGear = speed * 1000f / kmhPer1000[gear - 1]
        if (accelerating && rpmInGear > 2600f && gear < kmhPer1000.size) {
            gear++
            shiftPause = 0.35f
        } else if (rpmInGear < 1300f && gear > 1) {
            gear--
        }
        if (speed < 3f) gear = 1

        val noise = Random.nextFloat() * 2f - 1f
        val rpm = if (speed < 5f) idleRpm + noise * 15f
        else max(idleRpm + 30f,speed * 1000f / kmhPer1000[gear - 1] + noise * 12f)

        val load = when {
            speed < 5f && !accelerating -> 11f + noise
            shiftPause > 0f -> 8f
            accelerating -> 72f + noise * 5f + (if (gear <= 3) 16f else 0f)
            braking -> 0f
            else -> 9f + speed * 0.19f + noise * 2f
        }.coerceIn(0f, 100f)

        coolant = min(89f, coolant + dt * 0.9f)
        val boostKpa = if (rpm > 1500f) load * 1.35f * min(1f, (rpm - 1500f) / 700f) else load * 0.08f
        val base = LiveData(
            rpm = rpm,
            speed = speed,
            load = load,
            coolant = coolant,
            intakeTemp = 31f + load * 0.06f,
            mapKpa = 101f + boostKpa,
            baroKpa = 101f,
            voltage = 14.1f + noise * 0.05f,
        )
        // Caudalímetro simulado como el coche real: EGR activa al ralentí (~65 %), cerrada a plena carga.
        val fresh = when {
            load < 30f -> 0.65f
            load > 60f -> 0.97f
            else -> 0.82f
        }
        val air = FuelModel.airPerStrokeMg(base, cylVolumeL) ?: 0f
        return base.copy(maf = air * fresh * rpm / 30f / 1000f)
    }
}
