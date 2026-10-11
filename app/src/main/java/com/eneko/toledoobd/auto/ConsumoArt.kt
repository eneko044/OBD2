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
        // Android Auto oscurece la carátula cuando la tarjeta va al lado del mapa:
        // colores aclarados y más halo para que el número siga resaltando con ese velo.
        val color = when {
            value == null -> Dash.TextDim.toArgb()
            moving -> brighten(consumptionColor(value, ecoGood, ecoBad).toArgb(), 0.22f)
            else -> Color.WHITE
        }
        paint.shader = RadialGradient(
            cx, s * 0.3f, s * 0.5f,
            intArrayOf(withAlpha(color, 0x70), withAlpha(color, 0x18), withAlpha(color, 0)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, s, s * 0.62f, paint)
        paint.shader = null

        text.textAlign = Paint.Align.CENTER
        text.letterSpacing = 0.18f
        text.typeface = semi
        text.textSize = s * 0.066f
        text.color = brighten(Dash.TextDim.toArgb(), 0.45f)

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
        val unitPaint = Paint(text).apply {
            typeface = bold
            textSize = s * 0.085f
            this.color = brighten(Dash.TextDim.toArgb(), 0.45f)
        }
        val unitW = unitPaint.measureText(unit)
        val gap = s * 0.025f
        // Lo más grande que quepa, también con "12,8"
        text.textSize = s * 0.36f
        val maxW = s * 0.84f
        if (text.measureText(number) + gap + unitW > maxW) {
            text.textSize *= (maxW - gap - unitW) / text.measureText(number)
        }
        val numW = text.measureText(number)
        val left = cx - (numW + gap + unitW) / 2
        val base = s * 0.39f
        text.textAlign = Paint.Align.LEFT
        unitPaint.textAlign = Paint.Align.LEFT
        text.color = color
        // Trazo extra para engordar la letra y brillo del mismo color alrededor
        text.style = Paint.Style.FILL_AND_STROKE
        text.strokeWidth = s * 0.008f
        text.setShadowLayer(s * 0.05f, 0f, 0f, withAlpha(color, 0xB0))
        c.drawText(number, left, base, text)
        text.clearShadowLayer()
        text.style = Paint.Style.FILL
        c.drawText(unit, left + numW + gap, base, unitPaint)

        // Barra: consumo de ahora y una marca blanca en la media del trayecto
        val bar = RectF(s * 0.12f, s * 0.46f, s * 0.88f, s * 0.495f)
        val r = bar.height() / 2
        paint.color = 0xFF2A2D35.toInt()
        c.drawRoundRect(bar, r, r, paint)
        if (moving) {
            paint.color = color
            val w = bar.width() * (value / scaleMax).coerceIn(0.03f, 1f)
            c.drawRoundRect(RectF(bar.left, bar.top, bar.left + w, bar.bottom), r, r, paint)
        }
        average?.let {
            val x = bar.left + bar.width() * (it / scaleMax).coerceIn(0f, 1f)
            paint.color = Dash.Text.toArgb()
            c.drawRoundRect(RectF(x - s * 0.009f, bar.top - s * 0.022f, x + s * 0.009f, bar.bottom + s * 0.022f), 2f, 2f, paint)
        }
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

        fun fmt(v: Float) = String.format(java.util.Locale.getDefault(), "%.1f", v)
    }
}
