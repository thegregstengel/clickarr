package net.clickarr.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import kotlinx.datetime.Instant
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens
import net.clickarr.ui.design.ClickarrTextStyles

/** Bottom strip: what is on now and the next few programs on this channel. Left and Right move the highlight. */
@Composable
fun MiniGuide(guide: PlayerViewModel.MiniGuide, now: Instant, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(ClickarrColors.BgBase.copy(alpha = STRIP_ALPHA))
            .padding(horizontal = ClickarrDimens.SafeArea, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        guide.items.forEachIndexed { i, airing ->
            val selected = i == guide.index
            val live = airing.start <= now && now < airing.end
            Column(
                Modifier
                    .width(CARD_WIDTH)
                    .background(
                        if (selected) ClickarrColors.AccentPrimary else ClickarrColors.BgSurface.copy(alpha = CARD_ALPHA),
                        RoundedCornerShape(ClickarrDimens.RadiusCell),
                    )
                    .then(
                        if (selected) Modifier.border(2.dp, ClickarrColors.FocusRing, RoundedCornerShape(ClickarrDimens.RadiusCell)) else Modifier,
                    )
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    if (live) "Now" else slotRange(airing),
                    style = ClickarrTextStyles.Caption,
                    color = if (live) ClickarrColors.AccentGlow else ClickarrColors.TextSecondary,
                )
                Text(airing.entry.title, style = ClickarrTextStyles.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                airing.entry.subtitle?.let {
                    Text(
                        it,
                        style = ClickarrTextStyles.Caption,
                        color = ClickarrColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (live) Text(slotRange(airing), style = ClickarrTextStyles.Caption, color = ClickarrColors.TextMuted)
            }
        }
    }
}

private val CARD_WIDTH = 300.dp
private const val STRIP_ALPHA = 0.85f
private const val CARD_ALPHA = 0.9f
