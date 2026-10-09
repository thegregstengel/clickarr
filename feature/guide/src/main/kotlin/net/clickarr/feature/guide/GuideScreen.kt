package net.clickarr.feature.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Text
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.clickarr.core.model.Airing
import net.clickarr.core.model.Channel
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens
import net.clickarr.ui.design.ClickarrTextStyles
import net.clickarr.ui.design.clickarrFocusable

private val CHANNEL_COLUMN = 200.dp
private val ROW_HEIGHT = 64.dp
private val HEADER_HEIGHT = 40.dp

/** Grid guide (design language 4, "Guide grid"). OK on a cell tunes to that channel. */
@Composable
fun GuideScreen(onWatch: () -> Unit, viewModel: GuideViewModel = hiltViewModel()) {
    val window by viewModel.window.collectAsState()
    val w = window ?: return
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val minutePx = with(density) { (ClickarrDimens.GuideHalfHourWidth / 30).toPx() }
    var focused by remember { mutableStateOf<Airing?>(null) }

    Column(Modifier.fillMaxSize().background(ClickarrColors.BgBase).padding(horizontal = ClickarrDimens.SafeArea / 2)) {
        TimeHeader(w.from, w.to, scroll)
        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(w.rows, key = { it.channel.id.value }) { row ->
                    GuideRow(
                        row = row,
                        window = w,
                        minutePx = minutePx,
                        scroll = scroll,
                        isCurrent = row.channel.id.value == w.currentChannelId,
                        onFocusAiring = { focused = it },
                        onTune = { viewModel.tune(row.channel, onWatch) },
                    )
                }
            }
            NowLine(w, minutePx, scroll.value)
        }
        FocusedDetail(focused)
    }
}

@Composable
private fun TimeHeader(from: Instant, to: Instant, scroll: androidx.compose.foundation.ScrollState) {
    Row(Modifier.fillMaxWidth().height(HEADER_HEIGHT)) {
        Box(Modifier.width(CHANNEL_COLUMN), contentAlignment = Alignment.CenterStart) {
            Text("Today", style = ClickarrTextStyles.Caption, color = ClickarrColors.TextSecondary)
        }
        Row(Modifier.horizontalScroll(scroll, enabled = false)) {
            var t = from
            while (t < to) {
                Box(Modifier.width(ClickarrDimens.GuideHalfHourWidth), contentAlignment = Alignment.CenterStart) {
                    Text(timeOfDay(t), style = ClickarrTextStyles.Secondary, color = ClickarrColors.TextSecondary)
                }
                t += 30.minutes
            }
        }
    }
}

@Composable
private fun GuideRow(
    row: GuideViewModel.Row,
    window: GuideViewModel.Window,
    minutePx: Float,
    scroll: androidx.compose.foundation.ScrollState,
    isCurrent: Boolean,
    onFocusAiring: (Airing) -> Unit,
    onTune: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT).padding(vertical = ClickarrDimens.GuideCellGutter / 2)) {
        ChannelCell(row.channel, isCurrent)
        Box(Modifier.fillMaxSize().clipToBounds().horizontalScroll(scroll)) {
            Layout(
                content = {
                    row.airings.forEach { airing ->
                        ProgramCell(airing, window, onFocus = { onFocusAiring(airing) }, onTune = onTune)
                    }
                },
            ) { measurables, constraints ->
                val placeables = measurables.mapIndexed { i, m ->
                    val a = row.airings[i]
                    val start = maxOf(a.start, window.from)
                    val w = ((a.end - start).inWholeMinutes * minutePx).toInt().coerceAtLeast(1)
                    m.measure(constraints.copy(minWidth = w, maxWidth = w))
                }
                val total = ((window.to - window.from).inWholeMinutes * minutePx).toInt()
                layout(total, constraints.maxHeight) {
                    placeables.forEachIndexed { i, p ->
                        val start = maxOf(row.airings[i].start, window.from)
                        p.place(((start - window.from).inWholeMinutes * minutePx).toInt(), 0)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelCell(channel: Channel, isCurrent: Boolean) {
    Row(
        Modifier
            .width(CHANNEL_COLUMN)
            .fillMaxHeight()
            .padding(end = 16.dp)
            .background(
                if (isCurrent) ClickarrColors.AccentPrimaryDeep else ClickarrColors.BgPanel,
                RoundedCornerShape(ClickarrDimens.RadiusCell),
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(channel.number.toString(), style = ClickarrTextStyles.RowTitle, modifier = Modifier.width(56.dp))
        Text(channel.name.uppercase(), style = ClickarrTextStyles.LabelAllCaps, color = ClickarrColors.TextSecondary, maxLines = 1)
    }
}

@Composable
private fun ProgramCell(airing: Airing, window: GuideViewModel.Window, onFocus: () -> Unit, onTune: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val past = airing.end <= window.now
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = ClickarrDimens.GuideCellGutter / 2)
            .clickarrFocusable(interaction)
            .border(1.dp, ClickarrColors.BgCellBorder, RoundedCornerShape(ClickarrDimens.RadiusCell))
            .onFocusChanged { if (it.isFocused) onFocus() }
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && (e.key == Key.DirectionCenter || e.key == Key.Enter)) {
                    onTune()
                    true
                } else {
                    false
                }
            }
            .focusable(interactionSource = interaction)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            airing.entry.title,
            style = ClickarrTextStyles.RowTitle,
            color = if (past) ClickarrColors.TextMuted else ClickarrColors.TextPrimary,
            maxLines = 1,
        )
    }
}

@Composable
private fun NowLine(window: GuideViewModel.Window, minutePx: Float, scrollPx: Int) {
    val density = LocalDensity.current
    val offsetPx = ((window.now - window.from).inWholeMinutes * minutePx).toInt() - scrollPx
    val x = with(density) { offsetPx.toDp() } + CHANNEL_COLUMN
    if (offsetPx >= 0) {
        Box(Modifier.padding(start = x).width(2.dp).fillMaxHeight().background(ClickarrColors.AccentGlow))
    }
}

@Composable
private fun FocusedDetail(airing: Airing?) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        if (airing != null) {
            Text(airing.entry.title, style = ClickarrTextStyles.RowTitle)
            airing.entry.subtitle?.let {
                Text("   $it", style = ClickarrTextStyles.Secondary, color = ClickarrColors.TextSecondary)
            }
            Text(
                "   ${timeOfDay(airing.start)} – ${timeOfDay(airing.end)}",
                style = ClickarrTextStyles.Secondary,
                color = ClickarrColors.TextSecondary,
            )
        }
    }
}

private fun timeOfDay(instant: Instant): String {
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val h24 = local.hour
    val h12 = if (h24 % 12 == 0) 12 else h24 % 12
    val ampm = if (h24 < 12) "AM" else "PM"
    return "%d:%02d %s".format(h12, local.minute, ampm)
}
