package net.clickarr.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val ClickarrColorScheme = darkColorScheme(
    primary = ClickarrColors.AccentPrimary,
    onPrimary = Color.White,
    primaryContainer = ClickarrColors.AccentPrimaryDeep,
    onPrimaryContainer = ClickarrColors.TextPrimary,
    secondary = ClickarrColors.AccentGlow,
    onSecondary = ClickarrColors.BgBase,
    tertiary = ClickarrColors.AccentViolet,
    background = ClickarrColors.BgBase,
    onBackground = ClickarrColors.TextPrimary,
    surface = ClickarrColors.BgPanel,
    onSurface = ClickarrColors.TextPrimary,
    surfaceVariant = ClickarrColors.BgSurface,
    onSurfaceVariant = ClickarrColors.TextSecondary,
    border = ClickarrColors.BgCellBorder,
    error = ClickarrColors.StatusError,
    onError = Color.White,
)

/** Clickarr is dark only. Televisions are watched in the dark, and the brand is built around it. */
@Composable
fun ClickarrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ClickarrColorScheme,
        typography = ClickarrTypography,
        content = content,
    )
}
