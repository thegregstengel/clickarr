package net.clickarr.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import kotlin.time.Duration
import kotlinx.datetime.Instant
import net.clickarr.playback.core.TuneState
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens
import net.clickarr.ui.design.ClickarrTextStyles

/**
 * Shown between a program's content end and its slot end, and when a program cannot play
 * (design language 4, "Filler card"). The countdown is the activity indicator; there is no spinner.
 */
@Composable
fun FillerCard(state: TuneState, now: Instant, reason: String?, showHeader: Boolean = true) {
    val channel = state.channel
    val airing = state.airing
    Box(Modifier.fillMaxSize().background(ClickarrColors.BgBase)) {
        if (channel != null && showHeader) {
            Column(Modifier.align(Alignment.TopStart).padding(ClickarrDimens.SafeArea)) {
                Text(channel.number.toString(), style = ClickarrTextStyles.ChannelNumber)
                Text(channel.name.uppercase(), style = ClickarrTextStyles.LabelAllCaps, color = ClickarrColors.TextSecondary)
            }
        }
        Column(
            Modifier.align(Alignment.Center).padding(ClickarrDimens.SafeArea),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (reason != null) {
                Text(reason, style = ClickarrTextStyles.ScreenTitle, color = ClickarrColors.TextSecondary)
            }
            val next = state.next
            if (next != null) {
                Text("Up next", style = ClickarrTextStyles.Caption, color = ClickarrColors.TextMuted)
                Text(next.entry.title, style = ClickarrTextStyles.ProgramTitle)
                Text(slotRange(next), style = ClickarrTextStyles.Secondary, color = ClickarrColors.TextSecondary)
                val remaining = (next.start - now).coerceAtLeast(Duration.ZERO)
                Text("in ${mmss(remaining)}", style = ClickarrTextStyles.Secondary, color = ClickarrColors.AccentGlow)
            } else if (airing == null && channel != null) {
                Text("Nothing scheduled", style = ClickarrTextStyles.ProgramTitle)
            } else if (channel == null) {
                Text("No channels yet", style = ClickarrTextStyles.ProgramTitle)
                Text("Press Back to create one.", style = ClickarrTextStyles.Secondary, color = ClickarrColors.TextSecondary)
            }
        }
    }
}
