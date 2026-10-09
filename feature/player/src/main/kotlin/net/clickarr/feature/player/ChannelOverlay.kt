package net.clickarr.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFeatureSettings
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import kotlin.time.Duration
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.clickarr.core.model.Airing
import net.clickarr.playback.core.TuneState
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens
import net.clickarr.ui.design.ClickarrTextStyles

/** Bottom overlay: channel badge, name, program, slot time, progress, Up Next (design language 4). */
@Composable
fun ChannelOverlay(state: TuneState, now: Instant, digits: String) {
    val channel = state.channel ?: return
    val airing = state.airing
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to ClickarrColors.BgBase.copy(alpha = 0.85f))),
    ) {
        Row(
            Modifier.align(Alignment.TopStart).padding(ClickarrDimens.SafeArea),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Clickarr", style = ClickarrTextStyles.ScreenTitle)
        }
        Text(
            clock(now),
            style = ClickarrTextStyles.ScreenTitle.tabular(),
            modifier = Modifier.align(Alignment.TopEnd).padding(ClickarrDimens.SafeArea),
        )
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(ClickarrDimens.SafeArea),
            verticalAlignment = Alignment.Bottom,
        ) {
            ChannelBadge(if (digits.isNotEmpty()) digits else channel.number.toString(), highlight = digits.isNotEmpty())
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(channel.name.uppercase(), style = ClickarrTextStyles.LabelAllCaps, color = ClickarrColors.TextSecondary)
                Text(airing?.entry?.title ?: "Nothing scheduled", style = ClickarrTextStyles.ProgramTitle, maxLines = 1)
                airing?.entry?.subtitle?.let { Text(it, style = ClickarrTextStyles.Secondary, color = ClickarrColors.TextSecondary) }
                if (airing != null) {
                    Text(slotRange(airing), style = ClickarrTextStyles.Secondary.tabular(), color = ClickarrColors.TextSecondary)
                    Spacer(Modifier.height(4.dp))
                    ProgressRow(airing, now)
                }
            }
            state.next?.let { UpNextCard(it) }
        }
    }
}

@Composable
private fun ChannelBadge(text: String, highlight: Boolean) {
    Box(
        Modifier
            .size(width = 88.dp, height = 64.dp)
            .background(
                if (highlight) ClickarrColors.AccentPrimary else ClickarrColors.BgSurface,
                RoundedCornerShape(ClickarrDimens.RadiusBadge),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = ClickarrTextStyles.ChannelNumber.tabular())
    }
}

@Composable
private fun ProgressRow(airing: Airing, now: Instant) {
    val slot = airing.end - airing.start
    val pos = (now - airing.start).coerceIn(Duration.ZERO, slot)
    val fraction = if (slot.inWholeMilliseconds == 0L) 0f else (pos.inWholeMilliseconds.toFloat() / slot.inWholeMilliseconds)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(560.dp)) {
        Box(Modifier.weight(1f).height(6.dp).background(ClickarrColors.TextMuted.copy(alpha = 0.6f), RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidth(fraction).height(6.dp).background(ClickarrColors.AccentGlow, RoundedCornerShape(3.dp)))
        }
        Spacer(Modifier.width(16.dp))
        Text("${mmss(pos)} / ${mmss(slot)}", style = ClickarrTextStyles.Secondary.tabular(), color = ClickarrColors.TextSecondary)
    }
}

@Composable
private fun UpNextCard(next: Airing) {
    Column(
        Modifier
            .width(360.dp)
            .background(ClickarrColors.BgSurface.copy(alpha = 0.9f), RoundedCornerShape(ClickarrDimens.RadiusCard))
            .padding(ClickarrDimens.CardPadding),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Up Next", style = ClickarrTextStyles.Caption, color = ClickarrColors.TextSecondary)
        Text(next.entry.title, style = ClickarrTextStyles.RowTitle, maxLines = 1)
        Text(slotRange(next), style = ClickarrTextStyles.Secondary.tabular(), color = ClickarrColors.TextSecondary)
    }
}

@Composable
fun DigitBadge(digits: String) {
    Box(Modifier.fillMaxSize().padding(ClickarrDimens.SafeArea), contentAlignment = Alignment.TopStart) {
        ChannelBadge(digits, highlight = true)
    }
}

// Formatting helpers (design language 6: short local time, en dash between times, tabular figures).

private fun androidx.compose.ui.text.TextStyle.tabular() = copy(fontFeatureSettings = ClickarrTextStyles.TABULAR_FIGURES)

internal fun clock(now: Instant): String = timeOfDay(now)

internal fun slotRange(a: Airing): String = "${timeOfDay(a.start)} – ${timeOfDay(a.end)}"

internal fun timeOfDay(instant: Instant): String {
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val h24 = local.hour
    val h12 = if (h24 % 12 == 0) 12 else h24 % 12
    val ampm = if (h24 < 12) "AM" else "PM"
    return "%d:%02d %s".format(h12, local.minute, ampm)
}

internal fun mmss(d: Duration): String {
    val total = d.inWholeSeconds
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@Suppress("unused")
private val fontFeatureNote: FontFeatureSettings? = null
