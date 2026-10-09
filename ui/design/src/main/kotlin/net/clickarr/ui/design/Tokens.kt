package net.clickarr.ui.design

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Design tokens extracted from the mockups. See docs/design/README.md section 2.
 * These are the spec; the mockups are direction.
 */
object ClickarrColors {
    val BgBase = Color(0xFF05070F)
    val BgPanel = Color(0xFF0A1220)
    val BgSurface = Color(0xFF131A36)
    val BgCell = Color(0xFF1A2A47)
    val BgCellBorder = Color(0xFF0F2038)
    val BgElevated = Color(0xFF1E2B45)

    val AccentPrimary = Color(0xFF1479FD)
    val AccentPrimaryDeep = Color(0xFF063677)
    val AccentGlow = Color(0xFF00ACFF)
    val AccentCyan = Color(0xFF00E2FD)
    val AccentViolet = Color(0xFF7E4DFD)
    val AccentMagenta = Color(0xFFAF2EFC)

    val StatusOk = Color(0xFF0CE568)
    val StatusWarn = Color(0xFFFFB020)
    val StatusError = Color(0xFFFF4D5E)

    val TextPrimary = Color(0xFFF2F5FA)
    val TextSecondary = Color(0xFFA9B4C8)
    val TextMuted = Color(0xFF6B7890)

    val FocusRing = Color.White.copy(alpha = 0.8f)

    val BrandGradient = Brush.linearGradient(listOf(AccentCyan, Color(0xFF1284FD), AccentMagenta))
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
