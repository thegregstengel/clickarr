package net.clickarr.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlin.time.Duration
import kotlinx.datetime.Instant
import net.clickarr.core.common.AppTime
import net.clickarr.core.model.Airing
import net.clickarr.playback.core.TuneState
import net.clickarr.playback.core.TuneStatus
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens
import net.clickarr.ui.design.ClickarrLogoPill
import net.clickarr.ui.design.ClickarrTextStyles
import net.clickarr.ui.design.clickarrFocusable
import net.clickarr.ui.design.R as DesignR

/** Bottom overlay: channel badge, name, program, slot time, progress, Up Next (design language 4). */
@Composable
fun ChannelOverlay(
    state: TuneState,
    now: Instant,
    digits: String,
    captionsOn: Boolean = false,
    sleepInMinutes: Long? = null,
    streamGear: StreamGearBinding? = null,
) {
    val channel = state.channel ?: return
    val airing = state.airing
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to ClickarrColors.BgBase.copy(alpha = 0.85f))),
    ) {
        ClickarrLogoPill(Modifier.align(Alignment.TopStart).padding(ClickarrDimens.SafeArea))
        Column(
            Modifier.align(Alignment.TopEnd).padding(ClickarrDimens.SafeArea),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(clock(now), style = ClickarrTextStyles.ScreenTitle.tabular())
            CaptionHint(captionsOn)
            sleepInMinutes?.let { Text("Sleeping in $it min", style = ClickarrTextStyles.Caption, color = ClickarrColors.AccentGlow) }
        }
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(ClickarrDimens.SafeArea),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChannelBadge(if (digits.isNotEmpty()) digits else channel.number.toString(), highlight = digits.isNotEmpty())
                streamGear?.let { StreamGear(it) }
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(channel.name.uppercase(), style = ClickarrTextStyles.LabelAllCaps, color = ClickarrColors.TextSecondary)
                val headline = airing?.entry?.title ?: if (state.status == TuneStatus.Loading) "Tuning…" else "Nothing scheduled"
                Text(headline, style = ClickarrTextStyles.ProgramTitle, maxLines = 1)
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

/**
 * Ghost gear under the channel badge. Focus lands here on Left / overlay show; OK is handled by
 * [PlayerViewModel.gearOk] while this has focus so hold-OK captions still work.
 */
@Composable
private fun StreamGear(gear: StreamGearBinding) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .focusRequester(gear.focus)
            .onFocusChanged { gear.onFocused(it.isFocused) }
            .focusProperties {
                // Stay on the gear for Left; Right is handled as mini-guide in player keys.
                left = FocusRequester.Cancel
            }
            .clickarrFocusable(interaction, radius = ClickarrDimens.RadiusBadge, idleColor = Color.Transparent)
            .focusable(interactionSource = interaction)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(DesignR.drawable.ic_ui_settings),
            contentDescription = "Audio and video",
            modifier = Modifier.size(28.dp),
            tint = ClickarrColors.TextSecondary,
        )
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

/** "CC" with its state, and how to switch it, under the clock while the overlay is up. */
@Composable
private fun CaptionHint(on: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "CC",
            style = ClickarrTextStyles.LabelAllCaps,
            color = if (on) ClickarrColors.BgBase else ClickarrColors.TextSecondary,
            modifier = Modifier
                .background(if (on) ClickarrColors.TextPrimary else ClickarrColors.BgSurface, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        Text(if (on) "Hold OK to turn captions off" else "Hold OK for captions", style = ClickarrTextStyles.Caption)
    }
}

/** A short "Captions on" or "Captions off" after switching, top centre, clear of the subtitles at the bottom. */
@Composable
fun CaptionNotice(text: String) {
    Box(Modifier.fillMaxSize().padding(ClickarrDimens.SafeArea), contentAlignment = Alignment.TopCenter) {
        Text(
            text,
            style = ClickarrTextStyles.RowTitle,
            modifier = Modifier
                .background(ClickarrColors.BgSurface.copy(alpha = 0.92f), RoundedCornerShape(ClickarrDimens.RadiusBadge))
                .padding(horizontal = 24.dp, vertical = 12.dp),
        )
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

internal fun timeOfDay(instant: Instant): String = AppTime.timeOfDay(instant)

internal fun mmss(d: Duration): String {
    val total = d.inWholeSeconds
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
