package com.eneko.toledoobd.obd

import com.eneko.toledoobd.data.EnginePreset
import com.eneko.toledoobd.data.FuelModel
import com.eneko.toledoobd.data.FuelSource
import com.eneko.toledoobd.data.GearEstimator
import com.eneko.toledoobd.data.LiveData
import com.eneko.toledoobd.data.Settings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdParserTest {

    @Test
    fun parsesRpmWithAndWithoutSpaces() {
        assertArrayEquals(intArrayOf(0x1A, 0xF8), ObdParser.parsePid("410C1AF8\r\r", 0x0C))
        assertArrayEquals(intArrayOf(0x1A, 0xF8), ObdParser.parsePid("41 0C 1A F8 \r", 0x0C))
    }

    @Test
    fun ignoresSearchingAndBusInit() {
        val raw = "SEARCHING...\rBUS INIT: ...OK\r41 00 98 18 80 01\r"
        val bytes = ObdParser.parsePid(raw, 0x00)!!
        val sup = ObdParser.parseSupported(bytes, 0)
        assertTrue(0x01 in sup)
        assertTrue(0x04 in sup)
        assertTrue(0x05 in sup)
        assertTrue(0x0C in sup)
        assertTrue(0x0D in sup)
        assertTrue(0x11 in sup)
        assertTrue(0x20 in sup)
    }

    @Test
    fun noDataIsNull() {
        assertNull(ObdParser.parsePid("NO DATA\r", 0x5E))
        assertTrue(ObdParser.isError("NO DATA"))
    }

    @Test
    fun parsesVoltage() {
        assertEquals(12.6f, ObdParser.parseVoltage("12.6V")!!, 0.001f)
    }

    @Test
    fun decodesDtcsKLine() {
        val codes = ObdParser.parseDtcs("43 04 01 01 00 00 00\r", isCan = false)
        assertEquals(listOf("P0401", "P0100"), codes)
    }

    @Test
    fun decodesDtcsCan() {
        val codes = ObdParser.parseDtcs("43 02 02 99 C1 23\r", isCan = true)
        assertEquals(listOf("P0299", "U0123"), codes)
    }

    @Test
    fun fuelEstimateIsRealisticForTdi() {
        val s = Settings(engine = EnginePreset.ASV_STAGE1, loadOffset = 0.8f)
        // Datos reales del coche al ralentí: 900 rpm, carga 21 %, MAP 102 kPa, admisión 61 °C
        val idle = FuelModel.litersPerHour(LiveData(rpm = 900f, load = 21.2f, mapKpa = 102f, intakeTemp = 61f), FuelSource.LOAD, s)
        assertTrue("ralentí $idle", idle in 0.5f..0.9f)
        // 120 km/h en 5ª (≈2600 rpm), carga ~68 % (lo que daba 12,5 L/100), poco turbo
        val cruise = FuelModel.litersPerHour(
            LiveData(rpm = 2600f, speed = 120f, load = 68f, mapKpa = 125f, intakeTemp = 30f), FuelSource.LOAD, s,
        )
        val l100 = cruise / 120f * 100f
        assertTrue("crucero $l100", l100 in 5f..8f)
        assertEquals(0f, FuelModel.litersPerHour(LiveData(rpm = 2000f, speed = 80f, load = 0.8f, mapKpa = 100f), FuelSource.LOAD, s), 0.001f)
        // Sin dato de presión se usa la curva por rpm
        val noMap = FuelModel.litersPerHour(LiveData(rpm = 900f, load = 22.7f), FuelSource.LOAD, s)
        assertTrue("sin MAP $noMap", noMap in 0.5f..1.1f)
    }

    @Test
    fun learnsLoadOffsetWhileCoasting() {
        val l = com.eneko.toledoobd.data.LoadOffsetLearner(null)
        // crucero estable: no aprende
        repeat(5) { l.feed(LiveData(rpm = 2200f, speed = 100f, load = 35f)) }
        assertNull(l.offset)
        // retención: velocidad bajando con carga ~20 %
        // retención con velocidad leída en ciclos alternos (se repite un ciclo sí y otro no)
        var v = 100f
        repeat(10) { i ->
            if (i % 2 == 0) v -= 1f
            l.feed(LiveData(rpm = 2100f, speed = v, load = 20.4f, mapKpa = 100f, baroKpa = 100f))
        }
        assertEquals(20.4f, l.offset!!, 0.01f)
        // con el cero aprendido, en retención el consumo es 0 y al ralentí es bajo
        val s = Settings(loadOffset = l.offset)
        assertEquals(0f, FuelModel.litersPerHour(LiveData(rpm = 2100f, speed = 90f, load = 20.4f), FuelSource.LOAD, s), 0.001f)
        val idle = FuelModel.litersPerHour(LiveData(rpm = 900f, load = 25f), FuelSource.LOAD, s)
        assertTrue("ralentí $idle", idle in 0.05f..1.0f)
    }

    @Test
    fun diagnosticDetectsActiveEgr() {
        fun maf(air: Float, rpm: Float) = air * rpm / 30f / 1000f
        var st = com.eneko.toledoobd.data.Diagnostics.start(LiveData(coolant = 80f))
        assertEquals(com.eneko.toledoobd.data.DiagStep.IDLE, st.step)
        // Ralentí real del coche: ~280 mg de caudalímetro frente a ~435 esperados
        repeat(25) {
            st = com.eneko.toledoobd.data.Diagnostics.feed(
                st, LiveData(rpm = 903f, load = 21f, mapKpa = 101f, intakeTemp = 35f, coolant = 80f, maf = maf(280f, 903f)), 0.8f,
            )
        }
        assertEquals(com.eneko.toledoobd.data.DiagStep.ROAD, st.step)
        // A plena carga el caudalímetro mide lo esperado (EGR cerrada)
        repeat(8) {
            val d = LiveData(rpm = 2800f, speed = 60f, load = 95f, mapKpa = 220f, baroKpa = 101f, intakeTemp = 40f, coolant = 85f)
            val expected = FuelModel.airPerStrokeMg(d)!!
            st = com.eneko.toledoobd.data.Diagnostics.feed(st, d.copy(maf = maf(expected * 0.97f, 2800f)), 0.8f)
        }
        assertEquals(com.eneko.toledoobd.data.DiagStep.DONE, st.step)
        val v = com.eneko.toledoobd.data.Diagnostics.verdicts(st)
        assertTrue(v.any { it.title == "EGR ACTIVA" })
        assertTrue(v.any { it.title == "Caudalímetro correcto" })
    }

    @Test
    fun estimatesGear() {
        assertEquals(5, GearEstimator.gear(2165f, 100f))
        assertEquals(1, GearEstimator.gear(2000f, 18.6f))
        assertNull(GearEstimator.gear(900f, 0f))
    }
}
