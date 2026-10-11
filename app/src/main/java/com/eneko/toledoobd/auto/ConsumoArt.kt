package com.eneko.toledoobd.auto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import com.eneko.toledoobd.R
import com.eneko.toledoobd.ui.components.consumptionColor
import com.eneko.toledoobd.ui.theme.Dash

/**
 * Carátula de "lo que suena" para Android Auto: un reloj pequeño con el consumo instantáneo,
 * pintado a mano en un bitmap porque Android Auto solo admite texto e imágenes.
 */
class ConsumoArt(context: Context) {

    private val orbitron: Typeface = font(context, R.font.orbitron_var).let {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(it, 900, false)
        else Typeface.create(it, Typeface.BOLD)
    }
    private val rajdhani: Typeface = font(context, R.font.rajdhani_bold)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    /**
     * @param background carátula de la canción que suena, que se pone oscurecida detrás del reloj.
     * @param value número grande (L/100 en marcha, L/h parado) o null sin conexión.
     * @param scaleMax fondo de escala del arco en L/100.
     */
    fun draw(
        background: Bitmap?,
        value: Float?,
        unit: String,
        average: Float?,
        moving: Boolean,
        ecoGood: Float,
        ecoBad: Float,
        scaleMax: Float = 20f,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = SIZE.toFloat()

        // Fondo con el degradado del panel y la línea roja de acento arriba
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, 0f, 0f, s, Dash.BgTop.toArgb(), Dash.Bg.toArgb(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, s, s, paint)
        paint.shader = null
        if (background != null && !background.isRecycled) {
            // Recorte centrado al cuadrado y velo oscuro para que el número se lea encima.
            val side = minOf(background.width, background.height)
            val src = Rect((background.width - side) / 2, (background.height - side) / 2, 0, 0).apply {
                right = left + side
                bottom = top + side
            }
            runCatching { c.drawBitmap(background, src, RectF(0f, 0f, s, s), Paint(Paint.FILTER_BITMAP_FLAG)) }
            paint.color = Color.argb(175, 7, 8, 11)
            c.drawRect(0f, 0f, s, s, paint)
        }
        paint.shader = LinearGradient(
            0f, 0f, s, 0f,
            intArrayOf(0, Dash.Red.toArgb(), 0), null, Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, s, s * 0.012f, paint)
        paint.shader = null

        // Arco de 270° como el de los relojes
        val ring = RectF(s * 0.1f, s * 0.1f, s * 0.9f, s * 0.9f)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = s * 0.045f
        paint.color = Dash.Stroke.toArgb()
        c.drawArc(ring, 135f, 270f, false, paint)

        if (value == null) {
            text.typeface = orbitron
            text.textSize = s * 0.12f
            text.color = Dash.TextDim.toArgb()
            c.drawText("SIN OBD", s / 2, s * 0.54f, text)
            return bmp
        }

        val color = if (moving) consumptionColor(value, ecoGood, ecoBad).toArgb() else Dash.Text.toArgb()
        if (moving) {
            paint.color = color
            c.drawArc(ring, 135f, 270f * (value / scaleMax).coerceIn(0.01f, 1f), false, paint)
        }

        val number = fmt(value)
        text.typeface = orbitron
        text.color = color
        text.textSize = s * when { number.length > 4 -> 0.16f; number.length == 4 -> 0.19f; else -> 0.25f }
        c.drawText(number, s / 2, s * 0.56f, text)

        text.typeface = rajdhani
        text.color = Dash.TextDim.toArgb()
        text.textSize = s * 0.1f
        text.letterSpacing = 0.08f
        c.drawText(unit, s / 2, s * 0.7f, text)

        text.textSize = s * 0.085f
        text.color = average?.let { consumptionColor(it, ecoGood, ecoBad).toArgb() } ?: Dash.TextDim.toArgb()
        c.drawText("MEDIA " + (average?.let(::fmt) ?: "--"), s / 2, s * 0.9f, text)
        text.letterSpacing = 0f
        return bmp
    }

    private fun font(context: Context, id: Int): Typeface =
        runCatching { ResourcesCompat.getFont(context, id) }.getOrNull() ?: Typeface.DEFAULT_BOLD

    companion object {
        /** Lado en píxeles: suficiente para la tarjeta y sin pasarse del límite de memoria al mandarla. */
        const val SIZE = 256

        fun fmt(v: Float) = String.format(java.util.Locale.getDefault(), "%.1f", v)
    }
}
