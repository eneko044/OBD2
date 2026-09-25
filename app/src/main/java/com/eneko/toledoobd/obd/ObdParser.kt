package com.eneko.toledoobd.obd

/**
 * Utilidades para interpretar las respuestas de texto de un ELM327.
 * Se asume ATS0 (sin espacios) y ATH0 (sin cabeceras), pero se toleran espacios.
 */
object ObdParser {

    private fun isHex(c: Char) = c in '0'..'9' || c in 'A'..'F'

    fun lines(raw: String): List<String> =
        raw.split('\r', '\n')
            .map { it.replace(" ", "").uppercase().trim() }
            .filter { it.isNotEmpty() }

    fun isError(raw: String): Boolean {
        val r = raw.uppercase().replace(" ", "")
        return r.isBlank() || r.contains("NODATA") || r.contains("ERROR") ||
            r.contains("UNABLE") || r.contains("STOPPED") || r.trim() == "?"
    }

    /** Devuelve los bytes de datos de la respuesta a "01 PID" (modo 41), o null. */
    fun parsePid(raw: String, pid: Int): IntArray? {
        val header = "41%02X".format(pid)
        for (line in lines(raw)) {
            val idx = line.indexOf(header)
            if (idx < 0) continue
            val data = line.substring(idx + header.length)
            val bytes = data.chunked(2)
                .takeWhile { it.length == 2 && isHex(it[0]) && isHex(it[1]) }
                .map { it.toInt(16) }
            if (bytes.isNotEmpty()) return bytes.toIntArray()
        }
        return null
    }

    /** Interpreta la máscara de PIDs soportados de 0100 / 0120 / 0140... */
    fun parseSupported(bytes: IntArray, base: Int): Set<Int> {
        val out = mutableSetOf<Int>()
        for (i in 0 until minOf(4, bytes.size)) {
            for (bit in 0 until 8) {
                if (bytes[i] and (0x80 shr bit) != 0) out += base + i * 8 + bit + 1
            }
        }
        return out
    }

    fun parseVoltage(raw: String): Float? =
        Regex("""(\d{1,2}(?:\.\d{1,2})?)\s*V""", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.get(1)?.toFloatOrNull()

    /** Decodifica los códigos de avería de una respuesta al modo 03. */
    fun parseDtcs(raw: String, isCan: Boolean): List<String> {
        val payload = StringBuilder()
        if (isCan) {
            // Puede venir en varias tramas "0:4302...", "1:...", precedidas de la longitud.
            for (l in lines(raw)) {
                if (l.length <= 3 && l.all(::isHex)) continue
                payload.append(if (l.length > 2 && l[1] == ':') l.substring(2) else l)
            }
            val s = payload.toString()
            val idx = s.indexOf("43")
            if (idx < 0) return emptyList()
            return decodePairs(s.substring(idx + 4)) // salta "43" + byte contador
        }
        val codes = mutableListOf<String>()
        for (l in lines(raw)) {
            val idx = l.indexOf("43")
            if (idx < 0) continue
            codes += decodePairs(l.substring(idx + 2))
        }
        return codes.distinct()
    }

    private fun decodePairs(hex: String): List<String> {
        val clean = hex.takeWhile(::isHex)
        val out = mutableListOf<String>()
        var i = 0
        while (i + 4 <= clean.length) {
            val a = clean.substring(i, i + 2).toInt(16)
            val b = clean.substring(i + 2, i + 4).toInt(16)
            i += 4
            if (a == 0 && b == 0) continue
            val type = "PCBU"[a shr 6]
            out += "%c%d%X%02X".format(type, (a shr 4) and 0x3, a and 0xF, b)
        }
        return out
    }
}
