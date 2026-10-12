package com.eneko.toledoobd.auto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import com.eneko.toledoobd.R
import com.eneko.toledoobd.ui.components.consumptionColor
import com.eneko.toledoobd.ui.theme.Dash

/**
 * Carátula de "lo que suena" para Android Auto con el consumo instantáneo.
 *
 * Android Auto estira la carátula a toda la tarjeta y encima, en la mitad de abajo, pone el
 * título, el subtítulo y los botones. Por eso todo va en la mitad de arriba y la de abajo
 * queda lisa y oscura para que el texto se lea.
 */
class ConsumoArt(context: Context) {

    // Rajdhani: estrecha y sin el cero tachado de Orbitron, que en grande parecía una "Ø".
    private val bold: Typeface = font(context, R.font.rajdhani_bold)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * @param value número grande (L/100 en marcha, L/h parado) o null sin conexión.
     * @param scaleMax fondo de escala de la barra en L/100.
     */
    fun draw(
        value: Float?,
        unit: String,
        average: Float?,
        moving: Boolean,
        ecoGood: Float,
        ecoBad: Float,
        scaleMax: Float = 20f,
    ): Bitmap {
        // RGB_565: la mitad de bytes que ARGB, para no cargar el envío al coche cada segundo.
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        val s = SIZE.toFloat()
        val cx = s / 2

        // Android Auto pone un velo encima de la carátula (oscuro, o blanquecino de día).
        // Lo que mejor aguanta ese velo es blanco puro sobre negro puro: el número va en blanco
        // y el color del consumo (verde/ámbar/rojo) va en la etiqueta, el brillo y la barra.
        c.drawColor(Color.BLACK)
        val color = when {
            value == null -> Dash.TextDim.toArgb()
            moving -> consumptionColor(value, ecoGood, ecoBad).toArgb()
            else -> Color.WHITE
        }
        paint.shader = RadialGradient(
            cx, s * 0.3f, s * 0.42f,
            intArrayOf(withAlpha(color, 0x60), withAlpha(color, 0x14), withAlpha(color, 0)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, s, s * 0.62f, paint)
        paint.shader = null

        text.textAlign = Paint.Align.CENTER
        text.typeface = bold
        if (value == null) {
            text.textSize = s * 0.13f
            text.letterSpacing = 0.12f
            text.color = 0xFFC8CCD4.toInt()
            c.drawText("SIN OBD", cx, s * 0.36f, text)
            return bmp
        }

        // Etiqueta con la unidad, en el color del consumo: "AHORA · L/100"
        text.letterSpacing = 0.15f
        text.textSize = s * 0.072f
        text.color = brighten(color, 0.15f)
        c.drawText(if (moving) "AHORA · $unit" else "PARADO · $unit", cx, s * 0.105f, text)

        // Número solo y centrado: Android Auto recorta los lados de la carátula según la forma
        // de la tarjeta, así que no debe pasar del 70 % central.
        val number = fmt(value)
        text.letterSpacing = 0f
        text.textSize = s * 0.40f
        val maxW = s * 0.70f
        val w0 = text.measureText(number)
        if (w0 > maxW) text.textSize *= maxW / w0
        text.color = Color.WHITE
        text.style = Paint.Style.FILL_AND_STROKE
        text.strokeWidth = s * 0.01f
        text.setShadowLayer(s * 0.045f, 0f, 0f, withAlpha(color, 0xE0))
        c.drawText(number, cx, s * 0.43f, text)
        text.clearShadowLayer()
        text.style = Paint.Style.FILL

        // Barra gruesa: consumo de ahora en su color y una marca blanca en la media del trayecto
        val bar = RectF(s * 0.17f, s * 0.47f, s * 0.83f, s * 0.515f)
        val r = bar.height() / 2
        paint.color = 0xFF3A3D45.toInt()
        c.drawRoundRect(bar, r, r, paint)
        if (moving) {
            paint.color = color
            val w = bar.width() * (value / scaleMax).coerceIn(0.05f, 1f)
            c.drawRoundRect(RectF(bar.left, bar.top, bar.left + w, bar.bottom), r, r, paint)
        }
        average?.let {
            val x = bar.left + bar.width() * (it / scaleMax).coerceIn(0f, 1f)
            paint.color = Color.WHITE
            c.drawRoundRect(RectF(x - s * 0.011f, bar.top - s * 0.025f, x + s * 0.011f, bar.bottom + s * 0.025f), 3f, 3f, paint)
        }
        return bmp
    }

    /**
     * Foto del "contacto" en la tarjeta de mensajes de Android Auto: círculo negro con el número
     * en blanco y un aro del color del consumo.
     */
    fun avatar(value: Float, moving: Boolean, ecoGood: Float, ecoBad: Float): Bitmap {
        val n = AVATAR
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = n / 2f
        val color = if (moving) consumptionColor(value, ecoGood, ecoBad).toArgb() else Color.WHITE
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = Color.BLACK
        c.drawCircle(r, r, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = n * 0.09f
        paint.color = color
        c.drawCircle(r, r, r - paint.strokeWidth / 2, paint)
        paint.style = Paint.Style.FILL

        val number = fmt(value)
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = bold
            textAlign = Paint.Align.CENTER
            this.color = Color.WHITE
            textSize = n * 0.46f
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = n * 0.012f
        }
        val maxW = n * 0.68f
        val w = t.measureText(number)
        if (w > maxW) t.textSize *= maxW / w
        // Centrado vertical con la altura real de las cifras
        val bounds = android.graphics.Rect()
        t.getTextBounds(number, 0, number.length, bounds)
        c.drawText(number, r, r - (bounds.top + bounds.bottom) / 2f, t)
        return bmp
    }

    /** Mezcla con blanco: misma tonalidad, más luz. */
    private fun brighten(color: Int, k: Float): Int {
        fun ch(v: Int) = (v + (255 - v) * k).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(color)), ch(Color.green(color)), ch(Color.blue(color)))
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    private fun font(context: Context, id: Int): Typeface =
        runCatching { ResourcesCompat.getFont(context, id) }.getOrNull() ?: Typeface.DEFAULT_BOLD

    companion object {
        /** Lado en píxeles: nítido en la pantalla del coche sin pasarse de tamaño al mandarla. */
        const val SIZE = 400
        const val AVATAR = 192

        fun fmt(v: Float) = String.format(java.util.Locale.getDefault(), "%.1f", v)
    }
}
