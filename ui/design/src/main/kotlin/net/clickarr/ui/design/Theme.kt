package net.clickarr.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ColorScheme
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme

private fun ClickarrPalette.toColorScheme(): ColorScheme {
    val onAccent = Color.White
    return if (isLight) {
        lightColorScheme(
            primary = accentPrimary, onPrimary = onAccent, primaryContainer = accentPrimaryDeep, onPrimaryContainer = textPrimary,
            secondary = accentGlow, onSecondary = Color.White, tertiary = accentViolet,
            background = bgBase, onBackground = textPrimary, surface = bgPanel, onSurface = textPrimary,
            surfaceVariant = bgSurface, onSurfaceVariant = textSecondary, border = bgCellBorder,
            error = statusError, onError = Color.White,
        )
    } else {
        darkColorScheme(
            primary = accentPrimary, onPrimary = onAccent, primaryContainer = accentPrimaryDeep, onPrimaryContainer = textPrimary,
            secondary = accentGlow, onSecondary = bgBase, tertiary = accentViolet,
            background = bgBase, onBackground = textPrimary, surface = bgPanel, onSurface = textPrimary,
            surfaceVariant = bgSurface, onSurfaceVariant = textSecondary, border = bgCellBorder,
            error = statusError, onError = Color.White,
        )
    }
}

/** Televisions are watched in the dark, so the default palette is dark; Settings, Appearance offers others. */
@Composable
fun ClickarrTheme(palette: ClickarrPalette = ClickarrPalettes.Default, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = palette.toColorScheme(), typography = ClickarrTypography) {
        // TV Material only sets a content color inside a Surface; everything else would default to black.
        CompositionLocalProvider(
            LocalClickarrPalette provides palette,
            LocalContentColor provides palette.textPrimary,
            content = content,
        )
    }
}
