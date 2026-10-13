package com.eneko.toledoobd.diag

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro de conexiones con hora al milisegundo, guardado en un fichero para que sobreviva
 * aunque la app se cierre. Sirve para cazar los cortes de Android Auto: qué cayó primero
 * (Bluetooth, Wi-Fi, Android Auto, OBD…) y qué pasaba justo antes.
 */
object EventLog {
    private const val MAX_BYTES = 1_000_000L
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss.SSS", Locale.getDefault())
    private var file: File? = null
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "registro_conexiones.txt")
        file = f
        _lines.value = runCatching { f.readLines().takeLast(300) }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(tag: String, msg: String) {
        val line = "${fmt.format(Date())}  [$tag] $msg"
        _lines.value = (_lines.value + line).takeLast(300)
        val f = file ?: return
        runCatching {
            f.appendText(line + "\n")
            // Si crece mucho, se queda con la mitad más reciente
            if (f.length() > MAX_BYTES) {
                val keep = f.readLines().let { it.takeLast(it.size / 2) }
                f.writeText(keep.joinToString("\n", postfix = "\n"))
            }
        }
    }

    @Synchronized
    fun clear() {
        _lines.value = emptyList()
        runCatching { file?.writeText("") }
    }

    /** Copia para compartir (el FileProvider solo comparte la caché). */
    @Synchronized
    fun exportTo(dir: File): File? {
        val src = file ?: return null
        if (!src.exists() || src.length() == 0L) return null
        return File(dir, "registro_conexiones.txt").also { src.copyTo(it, overwrite = true) }
    }
}
