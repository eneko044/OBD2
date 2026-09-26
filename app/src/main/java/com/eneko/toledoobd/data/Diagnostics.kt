package com.eneko.toledoobd.data

/**
 * Diagnóstico guiado de EGR, caudalímetro y termostato.
 *
 * Idea: se compara el aire limpio que mide el caudalímetro con el que cabe en los cilindros
 * según presión y temperatura del colector.
 *  - A plena carga la EGR siempre está cerrada: esa proporción dice si el caudalímetro mide bien.
 *  - Al ralentí, si la EGR recircula gases, el caudalímetro mide bastante menos que a plena carga.
 */
enum class DiagStep { WARM_UP, IDLE, ROAD, DONE }

data class DiagState(
    val active: Boolean = false,
    val step: DiagStep = DiagStep.WARM_UP,
    val idleRatios: List<Float> = emptyList(),
    val roadRatios: List<Float> = emptyList(),
    val lastRatio: Float? = null,
    val lastMafAir: Float? = null,
    val lastMapAir: Float? = null,
    val coolantNow: Float? = null,
    val coolantMax: Float? = null,
    val drivingSeconds: Float = 0f,
) {
    val idleRatio: Float? get() = median(idleRatios)
    val roadRatio: Float? get() = median(roadRatios)
    val idleProgress: Float get() = (idleRatios.size / Diagnostics.IDLE_SAMPLES.toFloat()).coerceAtMost(1f)
    val roadProgress: Float get() = (roadRatios.size / Diagnostics.ROAD_SAMPLES.toFloat()).coerceAtMost(1f)
}

enum class Level { OK, WARN, BAD, INFO }

data class DiagVerdict(val title: String, val detail: String, val level: Level)

object Diagnostics {
    const val IDLE_SAMPLES = 20
    const val ROAD_SAMPLES = 6
    const val WARM_C = 70f
    private const val THERMO_MIN_DRIVE_S = 600f

    /** Aire limpio por ciclo según el caudalímetro (mg). */
    fun mafAirMg(d: LiveData): Float? {
        val maf = d.maf ?: return null
        if (maf < 0.5f || d.rpm < 300f) return null
        return maf * 1000f / (d.rpm / 30f)
    }

    fun start(live: LiveData): DiagState = DiagState(
        active = true,
        step = if ((live.coolant ?: 0f) >= WARM_C) DiagStep.IDLE else DiagStep.WARM_UP,
        coolantNow = live.coolant,
        coolantMax = live.coolant,
    )

    fun feed(s: DiagState, d: LiveData, dtS: Float): DiagState {
        if (!s.active) return s
        val mafAir = mafAirMg(d)
        val mapAir = FuelModel.airPerStrokeMg(d)
        val ratio = if (mafAir != null && mapAir != null && mapAir > 0f) mafAir / mapAir else null
        var n = s.copy(
            lastRatio = ratio,
            lastMafAir = mafAir,
            lastMapAir = mapAir,
            coolantNow = d.coolant ?: s.coolantNow,
            coolantMax = listOfNotNull(s.coolantMax, d.coolant).maxOrNull(),
            drivingSeconds = s.drivingSeconds + if (d.speed >= 10f) dtS else 0f,
        )
        val load = d.load ?: 0f
        when (s.step) {
            DiagStep.WARM_UP ->
                if ((d.coolant ?: 0f) >= WARM_C) n = n.copy(step = DiagStep.IDLE)
            DiagStep.IDLE ->
                if (ratio != null && d.speed < 1f && d.rpm in 750f..1050f && load < 45f) {
                    val l = n.idleRatios + ratio
                    n = n.copy(idleRatios = l, step = if (l.size >= IDLE_SAMPLES) DiagStep.ROAD else DiagStep.IDLE)
                }
            DiagStep.ROAD ->
                if (ratio != null && d.speed >= 15f && d.rpm in 1800f..3800f && load >= 85f && (d.boostBar ?: 0f) >= 0.3f) {
                    val l = n.roadRatios + ratio
                    n = n.copy(roadRatios = l, step = if (l.size >= ROAD_SAMPLES) DiagStep.DONE else DiagStep.ROAD)
                }
            DiagStep.DONE -> Unit
        }
        return n
    }

    fun verdicts(s: DiagState): List<DiagVerdict> {
        val out = mutableListOf<DiagVerdict>()
        val idle = s.idleRatio
        val road = s.roadRatio

        // Caudalímetro
        if (road != null) {
            val pct = (road * 100).toInt()
            out += when {
                road >= 0.85f -> DiagVerdict("Caudalímetro correcto", "A plena carga mide el $pct % del aire esperado.", Level.OK)
                road >= 0.70f -> DiagVerdict(
                    "Caudalímetro algo bajo",
                    "A plena carga mide el $pct % del aire esperado. Puede estar empezando a gastarse.", Level.WARN,
                )
                else -> DiagVerdict(
                    "Caudalímetro mide de menos",
                    "A plena carga solo mide el $pct % del aire esperado. Probablemente está gastado: " +
                        "la centralita limita el gasoil, se pierde potencia y puede echar humo.", Level.BAD,
                )
            }
        }

        // EGR
        if (idle != null && road != null) {
            val egr = (1f - idle / road).coerceIn(0f, 1f)
            val pct = (egr * 100).toInt()
            out += when {
                egr <= 0.12f -> DiagVerdict("EGR anulada", "Al ralentí no entra gas de escape (≈$pct %).", Level.OK)
                egr <= 0.25f -> DiagVerdict(
                    "EGR dudosa",
                    "Al ralentí recircula ≈$pct % de gases. Puede estar parcialmente abierta o con fugas.", Level.WARN,
                )
                else -> DiagVerdict(
                    "EGR ACTIVA",
                    "Al ralentí recircula ≈$pct % de gases de escape. La EGR no está anulada: la " +
                        "reprogramación no la desactivó o la válvula sigue conectada.", Level.BAD,
                )
            }
        } else if (idle != null) {
            val pct = (idle * 100).toInt()
            out += if (idle >= 0.85f) {
                DiagVerdict("EGR probablemente anulada", "Al ralentí el caudalímetro mide el $pct % del aire. Falta la prueba en carretera para confirmarlo.", Level.INFO)
            } else {
                DiagVerdict(
                    "Falta aire al ralentí ($pct %)",
                    "Puede ser la EGR funcionando o el caudalímetro midiendo de menos. La prueba en carretera lo distingue.", Level.INFO,
                )
            }
        }

        // Termostato
        val max = s.coolantMax
        out += if (s.drivingSeconds < THERMO_MIN_DRIVE_S) {
            DiagVerdict(
                "Termostato: conduce ${((THERMO_MIN_DRIVE_S - s.drivingSeconds) / 60f).toInt() + 1} min más",
                "Hacen falta 10 min circulando para evaluarlo. Máxima hasta ahora: ${max?.toInt() ?: "--"} °C.", Level.INFO,
            )
        } else when {
            max == null -> DiagVerdict("Termostato: sin dato de temperatura", "", Level.INFO)
            max >= 85f -> DiagVerdict("Termostato correcto", "El motor llega a ${max.toInt()} °C.", Level.OK)
            max >= 80f -> DiagVerdict("Termostato dudoso", "El motor no pasa de ${max.toInt()} °C (lo normal son 88-92 °C).", Level.WARN)
            else -> DiagVerdict(
                "Termostato abierto",
                "Tras 10 min circulando el motor no pasa de ${max.toInt()} °C. Lo normal son 88-92 °C. " +
                    "Un motor frío gasta más y ensucia más.", Level.BAD,
            )
        }
        return out
    }
}

private fun median(v: List<Float>): Float? {
    if (v.isEmpty()) return null
    val s = v.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
}
