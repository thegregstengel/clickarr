package net.clickarr.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * The mockups use a solid blue fill as the focus cue, plus a thin white ring so focus stays visible
 * on elements that are both focused and selected (docs/design/README.md section 4.2).
 */
@Composable
fun Modifier.clickarrFocusable(
    interactionSource: MutableInteractionSource,
    radius: Dp = ClickarrDimens.RadiusCell,
    idleColor: Color = ClickarrColors.BgCell,
    selected: Boolean = false,
): Modifier {
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(radius)
    val fill = when {
        focused -> ClickarrColors.AccentPrimary
        selected -> ClickarrColors.AccentPrimaryDeep
        else -> idleColor
    }
    return this
        .background(fill, shape)
        .then(if (focused && selected) Modifier.border(ClickarrDimens.FocusRingWidth, ClickarrColors.FocusRing, shape) else Modifier)
}
