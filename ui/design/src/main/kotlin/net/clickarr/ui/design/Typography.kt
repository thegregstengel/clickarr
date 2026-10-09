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
    val ChannelNumber = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 40.sp)
    val ProgramTitle = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 34.sp)
    val ScreenTitle = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 26.sp)
    val Body = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 24.sp)
    val Secondary = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 20.sp)
    val Caption = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 18.sp)
}

val ClickarrTypography = Typography(
    displayLarge = ClickarrTextStyles.ChannelNumber,
    headlineLarge = ClickarrTextStyles.ProgramTitle,
    headlineMedium = ClickarrTextStyles.ScreenTitle,
    titleLarge = ClickarrTextStyles.ScreenTitle,
    titleMedium = ClickarrTextStyles.Body,
    bodyLarge = ClickarrTextStyles.Body,
    bodyMedium = ClickarrTextStyles.Secondary,
    labelLarge = ClickarrTextStyles.Body,
    labelMedium = ClickarrTextStyles.Secondary,
    labelSmall = ClickarrTextStyles.Caption,
)
