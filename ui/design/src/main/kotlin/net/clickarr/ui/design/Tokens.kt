package net.clickarr.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Design tokens extracted from the mockups. See docs/design/README.md section 2.
 * These are the spec; the mockups are direction.
 */
/** One complete color set. The app ships four; Settings, Appearance, Theme picks one (see [ClickarrPalettes]). */
@Immutable
@Suppress("LongParameterList")
data class ClickarrPalette(
    val name: String,
    val label: String,
    val isLight: Boolean,
    val bgBase: Color,
    val bgPanel: Color,
    val bgSurface: Color,
    val bgCell: Color,
    val bgCellBorder: Color,
    val bgElevated: Color,
    val accentPrimary: Color,
    val accentPrimaryDeep: Color,
    val accentGlow: Color,
    val accentCyan: Color,
    val accentViolet: Color,
    val accentMagenta: Color,
    val statusOk: Color,
    val statusWarn: Color,
    val statusError: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val focusRing: Color,
) {
    val brandGradient: Brush get() = Brush.linearGradient(listOf(accentCyan, accentPrimary, accentMagenta))
}

object ClickarrPalettes {
    /** The design language's navy, extracted from the mockups (docs/design/README.md section 2). */
    val Default = ClickarrPalette(
        name = "default", label = "Clickarr", isLight = false,
        bgBase = Color(0xFF05070F), bgPanel = Color(0xFF0A1220), bgSurface = Color(0xFF131A36), bgCell = Color(0xFF1A2A47),
        bgCellBorder = Color(0xFF0F2038), bgElevated = Color(0xFF1E2B45),
        accentPrimary = Color(0xFF1479FD), accentPrimaryDeep = Color(0xFF063677), accentGlow = Color(0xFF00ACFF),
        accentCyan = Color(0xFF00E2FD), accentViolet = Color(0xFF7E4DFD), accentMagenta = Color(0xFFAF2EFC),
        statusOk = Color(0xFF0CE568), statusWarn = Color(0xFFFFB020), statusError = Color(0xFFFF4D5E),
        textPrimary = Color(0xFFF2F5FA), textSecondary = Color(0xFFA9B4C8), textMuted = Color(0xFF6B7890),
        focusRing = Color.White.copy(alpha = 0.8f),
    )

    /** Neutral near-black, same accents. */
    val Dark = Default.copy(
        name = "dark", label = "Dark",
        bgBase = Color(0xFF0A0A0C), bgPanel = Color(0xFF121316), bgSurface = Color(0xFF1A1B20), bgCell = Color(0xFF24262D),
        bgCellBorder = Color(0xFF17181C), bgElevated = Color(0xFF2B2E36),
        textPrimary = Color(0xFFF4F4F5), textSecondary = Color(0xFFA6A8B1), textMuted = Color(0xFF6E7079),
    )

    /**
     * For bright rooms: a cool slate, the darkest set that still reads as a light theme on a TV, chosen from six
     * candidates on a real Fire Stick (2026-10-10). Never white; darker accents for contrast.
     */
    val Light = ClickarrPalette(
        name = "light", label = "Light", isLight = true,
        bgBase = Color(0xFFA9B4C4), bgPanel = Color(0xFFB7C1CF), bgSurface = Color(0xFF9CA8B9), bgCell = Color(0xFF909DAF),
        bgCellBorder = Color(0xFF7C8A9E), bgElevated = Color(0xFF8794A6),
        accentPrimary = Color(0xFF0E5FCF), accentPrimaryDeep = Color(0xFFB3C8EA), accentGlow = Color(0xFF0A5BC4),
        accentCyan = Color(0xFF00A7C4), accentViolet = Color(0xFF6A3FE0), accentMagenta = Color(0xFF9A22E0),
        statusOk = Color(0xFF0B9A4A), statusWarn = Color(0xFFB36B00), statusError = Color(0xFFD12F3F),
        textPrimary = Color(0xFF0B1220), textSecondary = Color(0xFF2E3A4D), textMuted = Color(0xFF52607A),
        focusRing = Color(0xFF0B1220).copy(alpha = 0.8f),
    )

    /** draculatheme.com: background #282A36, current line #44475A, comment #6272A4, purple #BD93F9, cyan #8BE9FD. */
    val Dracula = ClickarrPalette(
        name = "dracula", label = "Dracula", isLight = false,
        bgBase = Color(0xFF1E1F29), bgPanel = Color(0xFF282A36), bgSurface = Color(0xFF343746), bgCell = Color(0xFF44475A),
        bgCellBorder = Color(0xFF3A3D4F), bgElevated = Color(0xFF4D5066),
        accentPrimary = Color(0xFFBD93F9), accentPrimaryDeep = Color(0xFF5A4A86), accentGlow = Color(0xFF8BE9FD),
        accentCyan = Color(0xFF8BE9FD), accentViolet = Color(0xFFBD93F9), accentMagenta = Color(0xFFFF79C6),
        statusOk = Color(0xFF50FA7B), statusWarn = Color(0xFFFFB86C), statusError = Color(0xFFFF5555),
        textPrimary = Color(0xFFF8F8F2), textSecondary = Color(0xFFBFC2D0), textMuted = Color(0xFF6272A4),
        focusRing = Color(0xFFF8F8F2).copy(alpha = 0.8f),
    )

    val all: List<ClickarrPalette> = listOf(Default, Dark, Light, Dracula)

    fun byName(name: String?): ClickarrPalette = all.firstOrNull { it.name == name } ?: Default
}

val LocalClickarrPalette = staticCompositionLocalOf { ClickarrPalettes.Default }

/**
 * Design tokens, read from the current palette. Every member is a composable getter, so a screen written
 * as `ClickarrColors.BgBase` follows the theme without knowing about it.
 */
object ClickarrColors {
    private val p: ClickarrPalette @Composable @ReadOnlyComposable get() = LocalClickarrPalette.current

    val BgBase: Color @Composable @ReadOnlyComposable get() = p.bgBase
    val BgPanel: Color @Composable @ReadOnlyComposable get() = p.bgPanel
    val BgSurface: Color @Composable @ReadOnlyComposable get() = p.bgSurface
    val BgCell: Color @Composable @ReadOnlyComposable get() = p.bgCell
    val BgCellBorder: Color @Composable @ReadOnlyComposable get() = p.bgCellBorder
    val BgElevated: Color @Composable @ReadOnlyComposable get() = p.bgElevated

    val AccentPrimary: Color @Composable @ReadOnlyComposable get() = p.accentPrimary
    val AccentPrimaryDeep: Color @Composable @ReadOnlyComposable get() = p.accentPrimaryDeep
    val AccentGlow: Color @Composable @ReadOnlyComposable get() = p.accentGlow
    val AccentCyan: Color @Composable @ReadOnlyComposable get() = p.accentCyan
    val AccentViolet: Color @Composable @ReadOnlyComposable get() = p.accentViolet
    val AccentMagenta: Color @Composable @ReadOnlyComposable get() = p.accentMagenta

    val StatusOk: Color @Composable @ReadOnlyComposable get() = p.statusOk
    val StatusWarn: Color @Composable @ReadOnlyComposable get() = p.statusWarn
    val StatusError: Color @Composable @ReadOnlyComposable get() = p.statusError

    val TextPrimary: Color @Composable @ReadOnlyComposable get() = p.textPrimary
    val TextSecondary: Color @Composable @ReadOnlyComposable get() = p.textSecondary
    val TextMuted: Color @Composable @ReadOnlyComposable get() = p.textMuted

    val FocusRing: Color @Composable @ReadOnlyComposable get() = p.focusRing

    val BrandGradient: Brush @Composable @ReadOnlyComposable get() = p.brandGradient
}

object ClickarrDimens {
    val Grid = 8.dp
    val RadiusCell = 12.dp
    val RadiusCard = 16.dp
    val RadiusBadge = 20.dp
    val CardPadding = 20.dp
    val GuideCellGutter = 6.dp
    /** TV overscan inset applied to every screen edge. */
    val SafeArea = 48.dp
    /** Width of 30 minutes in the guide at 1080p. */
    val GuideHalfHourWidth = 220.dp
    val FocusRingWidth = 2.dp
}

/** Durations from docs/design/design-language.md section 2.7. Focus movement itself never animates. */
object ClickarrMotion {
    const val FAST_MS = 120
    const val ENTER_MS = 200
    const val EXIT_MS = 150
    const val SCROLL_MS = 180
    /** Overlay auto-hide default; user adjustable 2 to 10 s under Appearance. */
    const val OVERLAY_TIMEOUT_MS = 5_000
}
