package net.clickarr.feature.guide

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.launch
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
import androidx.compose.ui.text.style.TextOverflow
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

/**
 * Grid guide (design language 4, "Guide grid"). OK on a cell tunes to that channel.
 *
 * On TV, Compose's default bring-into-view spec scrolls a focused item to a pivot position, which
 * would shift the whole grid every time focus moves. The guide manages its own horizontal scroll, so
 * it opts out and uses the plain spec.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GuideScreen(onWatch: () -> Unit, onlyFavorites: Boolean = false, viewModel: GuideViewModel = hiltViewModel()) {
    val window by viewModel.window.collectAsState()
    val all = window ?: return
    val w = if (onlyFavorites) all.copy(rows = all.rows.filter { it.channel.id.value in all.favorites }) else all
    val focus = remember(w.rows.size) { GuideFocus(w.rows.size) }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val minutePx = with(density) { (ClickarrDimens.GuideHalfHourWidth / 30).toPx() }
    var focused by remember { mutableStateOf<Airing?>(null) }

    val grid = GuideGrid(w, minutePx, scroll, focus)
    CompositionLocalProvider(LocalBringIntoViewSpec provides BringIntoViewSpec.DefaultBringIntoViewSpec) {
        GuideBody(grid, onlyFavorites, focused, { focused = it }) { ch -> viewModel.tune(ch, onWatch) }
    }
}

@Composable
private fun GuideBody(
    grid: GuideGrid,
    onlyFavorites: Boolean,
    focused: Airing?,
    onFocusAiring: (Airing) -> Unit,
    onTune: (Channel) -> Unit,
) {
    val w = grid.window
    val scroll = grid.scroll
    val minutePx = grid.minutePx
    val focus = grid.focus
    Column(Modifier.fillMaxSize().background(ClickarrColors.BgBase).padding(horizontal = ClickarrDimens.SafeArea / 2)) {
        TimeHeader(w.from, w.to, scroll)
        Box(Modifier.weight(1f)) {
            if (w.rows.isEmpty()) {
                Text(
                    if (onlyFavorites) "No favorites yet. Mark channels as favorites from the Channels tab." else "No channels yet.",
                    style = ClickarrTextStyles.Secondary,
                    color = ClickarrColors.TextSecondary,
                    modifier = Modifier.padding(24.dp),
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(w.rows, key = { _, r -> r.channel.id.value }) { rowIndex, row ->
                    GuideRow(
                        row = row,
                        rowIndex = rowIndex,
                        grid = grid,
                        isCurrent = row.channel.id.value == w.currentChannelId,
                        onFocusAiring = onFocusAiring,
                        onTune = { onTune(row.channel) },
                    )
                }
            }
            // Initial focus: the program airing now on the current channel, else the first row's current program.
            LaunchedEffect(w.rows.size, w.currentChannelId) {
                val rowIndex = w.rows.indexOfFirst { it.channel.id.value == w.currentChannelId }.takeIf { it >= 0 } ?: 0
                focus.requestAt(rowIndex, w.now)
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

/**
 * Per-cell focus requesters so Up/Down land on the program overlapping the focused time
 * (design language 2.8), instead of wherever Compose's default spatial search lands.
 */
private class GuideFocus(rowCount: Int) {
    val rows = List(rowCount) { HashMap<Int, FocusRequester>() }
    val airings = List(rowCount) { ArrayList<Airing>() }

    fun requester(row: Int, cell: Int): FocusRequester = rows[row].getOrPut(cell) { FocusRequester() }

    /** The cell in [row] that contains [at], or the first cell that starts after it. */
    fun cellAt(row: Int, at: Instant): Int? {
        val list = airings.getOrNull(row) ?: return null
        val i = list.indexOfFirst { at >= it.start && at < it.end }
        return if (i >= 0) i else list.indexOfFirst { it.start >= at }.takeIf { it >= 0 }
    }

    fun requestAt(row: Int, at: Instant) {
        val cell = cellAt(row, at) ?: return
        runCatching { requester(row, cell).requestFocus() }
    }
}

/** What every row shares: the time window, the scale, the horizontal scroll, and the focus map. */
private class GuideGrid(
    val window: GuideViewModel.Window,
    val minutePx: Float,
    val scroll: androidx.compose.foundation.ScrollState,
    val focus: GuideFocus,
)

@Composable
private fun GuideRow(
    row: GuideViewModel.Row,
    rowIndex: Int,
    grid: GuideGrid,
    isCurrent: Boolean,
    onFocusAiring: (Airing) -> Unit,
    onTune: () -> Unit,
) {
    val window = grid.window
    val minutePx = grid.minutePx
    val scroll = grid.scroll
    val focus = grid.focus
    focus.airings[rowIndex].let { it.clear(); it.addAll(row.airings) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT).padding(vertical = ClickarrDimens.GuideCellGutter / 2)) {
        ChannelCell(row.channel, isCurrent)
        Box(Modifier.fillMaxSize().clipToBounds().horizontalScroll(scroll)) {
            Layout(
                content = {
                    row.airings.forEachIndexed { i, airing ->
                        val focusTime = maxOf(airing.start, window.from)
                        ProgramCell(
                            airing = airing,
                            window = window,
                            modifier = Modifier
                                .focusRequester(focus.requester(rowIndex, i))
                                .focusProperties {
                                    focus.cellAt(rowIndex - 1, focusTime)?.let { c -> up = focus.requester(rowIndex - 1, c) }
                                    focus.cellAt(rowIndex + 1, focusTime)?.let { c -> down = focus.requester(rowIndex + 1, c) }
                                },
                            onFocus = {
                                onFocusAiring(airing)
                                // Keep the focused cell inside the visible window.
                                val leftPx = ((focusTime - window.from).inWholeMinutes * minutePx).toInt()
                                val margin = with(density) { ClickarrDimens.GuideHalfHourWidth.toPx() }.toInt()
                                val target = (leftPx - margin).coerceAtLeast(0)
                                if (leftPx < scroll.value || leftPx > scroll.value + scroll.viewportSize - margin) {
                                    scope.launch { scroll.animateScrollTo(target) }
                                }
                            },
                            onTune = onTune,
                        )
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
        Text(
            channel.name.uppercase(),
            style = ClickarrTextStyles.LabelAllCaps,
            color = ClickarrColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProgramCell(
    airing: Airing,
    window: GuideViewModel.Window,
    modifier: Modifier,
    onFocus: () -> Unit,
    onTune: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val past = airing.end <= window.now
    Box(
        modifier
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
