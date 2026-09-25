package com.eneko.toledoobd.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.eneko.toledoobd.R

object Dash {
    val Bg = Color(0xFF07080B)
    val BgTop = Color(0xFF14161C)
    val Panel = Color(0xFF111318)
    val PanelHi = Color(0xFF1A1D24)
    val Stroke = Color(0x22FFFFFF)
    val Text = Color(0xFFEDEFF3)
    val TextDim = Color(0xFF8A9099)
    val TextFaint = Color(0xFF555B64)
    val Red = Color(0xFFFF1E2D)
    val RedDeep = Color(0xFF9E0A14)
    val RedSoft = Color(0xFFFF5A64)
    val Amber = Color(0xFFFFB020)
    val Green = Color(0xFF2FE39A)
    val Blue = Color(0xFF3BA4FF)
}

@OptIn(ExperimentalTextApi::class)
val Orbitron = FontFamily(
    Font(R.font.orbitron_var, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.orbitron_var, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.orbitron_var, FontWeight.Black, variationSettings = FontVariation.Settings(FontVariation.weight(900))),
)

val Rajdhani = FontFamily(
    Font(R.font.rajdhani_medium, FontWeight.Medium),
    Font(R.font.rajdhani_semibold, FontWeight.SemiBold),
    Font(R.font.rajdhani_bold, FontWeight.Bold),
)

@Composable
fun ToledoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Dash.Red,
            onPrimary = Color.White,
            secondary = Dash.RedSoft,
            background = Dash.Bg,
            surface = Dash.Panel,
            surfaceContainerLow = Dash.Panel,
            surfaceContainer = Dash.Panel,
            surfaceContainerHigh = Dash.PanelHi,
            onSurface = Dash.Text,
            onSurfaceVariant = Dash.TextDim,
        ),
        content = content,
    )
}
