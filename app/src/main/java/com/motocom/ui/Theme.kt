package com.motocom.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The colours of the launcher icon: near-black with the blue of its wifi waves. */
object Palette {
    val Background = Color(0xFF0E1117)
    val Surface = Color(0xFF161B24)
    val SurfaceHigh = Color(0xFF1F2531)
    val Outline = Color(0xFF2B3240)
    val Primary = Color(0xFF2EA8FF)
    val OnPrimary = Color(0xFF00182B)
    val Text = Color(0xFFE8ECF2)
    val Muted = Color(0xFF8E97A8)
    val Good = Color(0xFF3DDC84)
    val Warn = Color(0xFFFFB547)
    val Bad = Color(0xFFFF6B6B)
}

@Composable
fun MotocomTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Palette.Primary,
        onPrimary = Palette.OnPrimary,
        primaryContainer = Color(0xFF0B3A5C),
        onPrimaryContainer = Color(0xFFCDE8FF),
        secondary = Palette.Primary,
        onSecondary = Palette.OnPrimary,
        background = Palette.Background,
        onBackground = Palette.Text,
        surface = Palette.Background,
        onSurface = Palette.Text,
        surfaceVariant = Palette.SurfaceHigh,
        onSurfaceVariant = Palette.Muted,
        surfaceContainerLowest = Palette.Background,
        surfaceContainerLow = Palette.Surface,
        surfaceContainer = Palette.Surface,
        surfaceContainerHigh = Palette.SurfaceHigh,
        surfaceContainerHighest = Palette.SurfaceHigh,
        outline = Palette.Outline,
        outlineVariant = Palette.Outline,
        error = Palette.Bad,
    )
    MaterialTheme(colorScheme = colors, content = content)
}
