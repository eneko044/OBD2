package com.eneko.toledoobd.data

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Genera datos de conducción creíbles para probar el panel sin coche. */
class DemoSimulator {
    private data class Leg(val targetKmh: Float, val holdS: Float)

    private val route = listOf(
        Leg(0f, 4f), Leg(50f, 12f), Leg(30f, 5f), Leg(60f, 10f), Leg(0f, 6f),
        Leg(90f, 10f), Leg(120f, 18f), Leg(100f, 8f), Leg(130f, 10f), Leg(70f, 8f), Leg(0f, 5f),
    )
    private val kmhPer1000 = floatArrayOf(9.3f, 16.6f, 25.8f, 36.2f, 46.2f)

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
        if (accelerating && rpmInGear > 2600f && gear < 5) {
            gear++
            shiftPause = 0.35f
        } else if (rpmInGear < 1300f && gear > 1) {
            gear--
        }
        if (speed < 3f) gear = 1

        val noise = Random.nextFloat() * 2f - 1f
        val rpm = if (speed < 5f) 870f + noise * 15f
        else max(900f, speed * 1000f / kmhPer1000[gear - 1] + noise * 12f)

        val load = when {
            speed < 5f && !accelerating -> 11f + noise
            shiftPause > 0f -> 8f
            accelerating -> 68f + noise * 6f + (if (gear <= 2) 10f else 0f)
            braking -> 0f
            else -> 9f + speed * 0.19f + noise * 2f
        }.coerceIn(0f, 100f)

        coolant = min(89f, coolant + dt * 0.9f)
        val boostKpa = if (rpm > 1500f) load * 1.35f * min(1f, (rpm - 1500f) / 700f) else load * 0.08f
        return LiveData(
            rpm = rpm,
            speed = speed,
            load = load,
            coolant = coolant,
            intakeTemp = 31f + load * 0.06f,
            mapKpa = 101f + boostKpa,
            baroKpa = 101f,
            voltage = 14.1f + noise * 0.05f,
        )
    }
}
