package net.clickarr.feature.player

import android.graphics.Color
import androidx.annotation.OptIn
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import net.clickarr.ui.design.R as DesignR

/**
 * Clickarr's caption look: Inter semibold in white with a soft black outline and no box, a little larger than the
 * player default and lifted off the bottom edge. Styling carried inside subtitle files (ASS fonts and colours) is
 * ignored so every program looks the same. Captions burned into a transcode are part of the picture and keep
 * whatever look the server gave them.
 */
@OptIn(UnstableApi::class)
internal fun SubtitleView.applyClickarrStyle() {
    val typeface = runCatching { ResourcesCompat.getFont(context, DesignR.font.inter_semibold) }.getOrNull()
    setStyle(
        CaptionStyleCompat(
            Color.WHITE,
            Color.TRANSPARENT,
            Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            OUTLINE,
            typeface,
        ),
    )
    setApplyEmbeddedStyles(false)
    setApplyEmbeddedFontSizes(false)
    setFractionalTextSize(TEXT_SIZE)
    setBottomPaddingFraction(BOTTOM_PADDING)
}

private val OUTLINE = Color.argb(230, 0, 0, 0)
private const val TEXT_SIZE = 0.058f
private const val BOTTOM_PADDING = 0.09f
