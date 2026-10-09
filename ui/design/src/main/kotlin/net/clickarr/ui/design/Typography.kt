package net.clickarr.ui.design

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Typography

val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** TV type scale from docs/design/README.md. Nothing below 18 sp. */
object ClickarrTextStyles {
    val ChannelNumber = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.5).sp,
    )
    val ProgramTitle = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.25).sp,
    )
    val ScreenTitle = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp)
    val RowTitle = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 30.sp)
    val Body = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 24.sp, lineHeight = 32.sp)
    val Secondary = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 20.sp, lineHeight = 26.sp)
    val Caption = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
    )
    val LabelAllCaps = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 22.sp,
        letterSpacing = 1.sp,
    )

    /** Tabular figures so times and channel numbers align in columns. Apply to clocks, slot times, badges. */
    const val TABULAR_FIGURES = "tnum"
}

val ClickarrTypography = Typography(
    displayLarge = ClickarrTextStyles.ChannelNumber,
    headlineLarge = ClickarrTextStyles.ProgramTitle,
    headlineMedium = ClickarrTextStyles.ScreenTitle,
    titleLarge = ClickarrTextStyles.ScreenTitle,
    titleMedium = ClickarrTextStyles.RowTitle,
    bodyLarge = ClickarrTextStyles.Body,
    bodyMedium = ClickarrTextStyles.Secondary,
    labelLarge = ClickarrTextStyles.RowTitle,
    labelMedium = ClickarrTextStyles.Secondary,
    labelSmall = ClickarrTextStyles.Caption,
)
