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
        val s = Settings(engine = EnginePreset.ASV)
        val idle = FuelModel.litersPerHour(LiveData(rpm = 900f, load = 11f), FuelSource.LOAD, s)
        assertTrue("ralentí $idle", idle in 0.4f..1.2f)
        val cruise = FuelModel.litersPerHour(LiveData(rpm = 2150f, speed = 100f, load = 28f), FuelSource.LOAD, s)
        val l100 = cruise / 100f * 100f
        assertTrue("crucero $l100", l100 in 3.5f..7f)
        val stage1 = FuelModel.litersPerHour(LiveData(rpm = 2150f, speed = 100f, load = 28f), FuelSource.LOAD, Settings())
        assertTrue("stage 1 por defecto", stage1 > cruise)
    }

    @Test
    fun estimatesGear() {
        assertEquals(5, GearEstimator.gear(2165f, 100f))
        assertEquals(1, GearEstimator.gear(2000f, 18.6f))
        assertNull(GearEstimator.gear(900f, 0f))
    }
}
