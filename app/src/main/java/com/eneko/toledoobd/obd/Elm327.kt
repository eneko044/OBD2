package com.eneko.toledoobd.obd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Canal de texto con el adaptador ELM327: envía un comando y lee hasta el prompt '>'. */
class ElmIo(
    private val input: InputStream,
    private val output: OutputStream,
    private val log: (String) -> Unit,
) {
    suspend fun send(cmd: String, timeoutMs: Long = 3000): String = withContext(Dispatchers.IO) {
        while (input.available() > 0) input.read()
        log("> $cmd")
        output.write("$cmd\r".toByteArray(Charsets.US_ASCII))
        output.flush()

        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        var done = false
        while (!done) {
            val n = input.available()
            if (n > 0) {
                val buf = ByteArray(n)
                val read = input.read(buf)
                if (read < 0) throw IOException("Conexión cerrada")
                for (i in 0 until read) {
                    val ch = (buf[i].toInt() and 0xFF).toChar()
                    if (ch == '>') {
                        done = true
                        break
                    }
                    sb.append(ch)
                }
            } else {
                if (System.currentTimeMillis() > deadline) {
                    log("! sin respuesta a $cmd")
                    break
                }
                delay(4)
            }
        }
        val resp = sb.toString().replace("\u0000", "").trim()
        log("< " + resp.replace('\r', ' ').replace('\n', ' '))
        resp
    }
}

object Pid {
    const val LOAD = 0x04
    const val COOLANT = 0x05
    const val MAP = 0x0B
    const val RPM = 0x0C
    const val SPEED = 0x0D
    const val IAT = 0x0F
    const val MAF = 0x10
    const val BARO = 0x33
    const val MODULE_VOLTAGE = 0x42
    const val FUEL_RATE = 0x5E
}

/** Sesión OBD sobre un ELM327: inicialización, PIDs soportados y lecturas. */
class ObdSession(private val io: ElmIo, private val log: (String) -> Unit) {

    var protocol: String = ""
        private set
    var isCan = false
        private set
    val supported = mutableSetOf<Int>()

    suspend fun initialize(onStep: (String) -> Unit) {
        onStep("Reiniciando ELM327…")
        io.send("ATZ", 4000)
        delay(600)
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1")) io.send(c, 1500)
        val version = io.send("ATI", 1500)
        log("Adaptador: $version")

        // Automático primero; si falla, protocolos típicos de VAG 2000-2004 (K-Line).
        val protocols = listOf("0" to "Automático", "3" to "ISO 9141-2", "5" to "KWP2000 rápido", "4" to "KWP2000 5 baudios")
        var ok = false
        for ((code, name) in protocols) {
            onStep("Buscando protocolo: $name…")
            io.send("ATSP$code", 1500)
            val r = io.send("0100", 20000)
            val bytes = ObdParser.parsePid(r, 0x00)
            if (bytes != null) {
                supported += ObdParser.parseSupported(bytes, 0x00)
                ok = true
                break
            }
        }
        if (!ok) throw IOException("La centralita no responde. ¿Contacto puesto?")

        val dpn = io.send("ATDPN", 1500).uppercase().replace("A", "").trim()
        isCan = dpn.firstOrNull()?.let { it in '6'..'9' || it in 'A'..'C' } ?: false
        protocol = io.send("ATDP", 1500).replace("AUTO,", "").trim()

        onStep("Leyendo sensores disponibles…")
        var base = 0x00
        while (supported.contains(base + 0x20) && base < 0x60) {
            base += 0x20
            val bytes = ObdParser.parsePid(io.send("01%02X".format(base), 4000), base) ?: break
            supported += ObdParser.parseSupported(bytes, base)
        }
        log("PIDs soportados: " + supported.sorted().joinToString { "%02X".format(it) })
    }

    suspend fun query(pid: Int, timeoutMs: Long = 2000): IntArray? =
        ObdParser.parsePid(io.send("01%02X".format(pid), timeoutMs), pid)

    suspend fun voltage(): Float? = ObdParser.parseVoltage(io.send("ATRV", 1500))

    suspend fun readDtcs(): List<String> {
        val r = io.send("03", 6000)
        if (ObdParser.isError(r) && !r.contains("43")) return emptyList()
        return ObdParser.parseDtcs(r, isCan)
    }

    suspend fun clearDtcs(): Boolean {
        val r = io.send("04", 6000)
        return r.replace(" ", "").contains("44")
    }
}
