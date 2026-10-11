package com.eneko.toledoobd.auto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
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
    private val semi: Typeface = font(context, R.font.rajdhani_semibold)

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

        c.drawColor(Dash.Bg.toArgb())
        val color = when {
            value == null -> Dash.TextDim.toArgb()
            moving -> consumptionColor(value, ecoGood, ecoBad).toArgb()
            else -> Dash.Text.toArgb()
        }
        // Halo suave del color del consumo detrás del número
        paint.shader = RadialGradient(
            cx, s * 0.3f, s * 0.48f,
            intArrayOf(withAlpha(color, 0x38), withAlpha(color, 0)), null, Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, s, s * 0.62f, paint)
        paint.shader = null

        text.textAlign = Paint.Align.CENTER
        text.letterSpacing = 0.18f
        text.typeface = semi
        text.textSize = s * 0.062f
        text.color = Dash.TextDim.toArgb()

        if (value == null) {
            text.typeface = bold
            text.textSize = s * 0.13f
            text.letterSpacing = 0.12f
            c.drawText("SIN OBD", cx, s * 0.36f, text)
            return bmp
        }

        c.drawText(if (moving) "AHORA" else "PARADO", cx, s * 0.13f, text)

        // Número y unidad juntos y centrados: "5,4 L/100"
        val number = fmt(value)
        text.letterSpacing = 0f
        text.typeface = bold
        text.textSize = s * 0.30f
        val numW = text.measureText(number)
        val unitPaint = Paint(text).apply {
            typeface = semi
            textSize = s * 0.075f
            this.color = Dash.TextDim.toArgb()
        }
        val unitW = unitPaint.measureText(unit)
        val gap = s * 0.025f
        val left = cx - (numW + gap + unitW) / 2
        val base = s * 0.39f
        text.textAlign = Paint.Align.LEFT
        unitPaint.textAlign = Paint.Align.LEFT
        text.color = color
        c.drawText(number, left, base, text)
        c.drawText(unit, left + numW + gap, base, unitPaint)

        // Barra: consumo de ahora y una marca blanca en la media del trayecto
        val bar = RectF(s * 0.14f, s * 0.465f, s * 0.86f, s * 0.49f)
        val r = bar.height() / 2
        paint.color = Dash.Stroke.toArgb()
        c.drawRoundRect(bar, r, r, paint)
        if (moving) {
            paint.color = color
            val w = bar.width() * (value / scaleMax).coerceIn(0.03f, 1f)
            c.drawRoundRect(RectF(bar.left, bar.top, bar.left + w, bar.bottom), r, r, paint)
        }
        average?.let {
            val x = bar.left + bar.width() * (it / scaleMax).coerceIn(0f, 1f)
            paint.color = Dash.Text.toArgb()
            c.drawRoundRect(RectF(x - s * 0.006f, bar.top - s * 0.018f, x + s * 0.006f, bar.bottom + s * 0.018f), 2f, 2f, paint)
        }
        return bmp
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    private fun font(context: Context, id: Int): Typeface =
        runCatching { ResourcesCompat.getFont(context, id) }.getOrNull() ?: Typeface.DEFAULT_BOLD

    companion object {
        /** Lado en píxeles: nítido en la pantalla del coche sin pasarse de tamaño al mandarla. */
        const val SIZE = 400

        fun fmt(v: Float) = String.format(java.util.Locale.getDefault(), "%.1f", v)
    }
}
