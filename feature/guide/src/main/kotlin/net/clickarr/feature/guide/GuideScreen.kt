package net.clickarr.feature.guide

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.clickarr.core.model.Airing
import net.clickarr.core.model.Channel
import net.clickarr.core.model.ChannelIcon
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens
import net.clickarr.ui.design.ClickarrTextStyles
import net.clickarr.ui.design.GlyphIcon
import net.clickarr.ui.design.clickarrFocusable

private val CHANNEL_COLUMN = 200.dp
private val ROW_HEIGHT = 64.dp
private val HEADER_HEIGHT = 40.dp
private const val ROW_JUMP = 5
private val TIME_JUMP = 3.hours

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
    var details by remember { mutableStateOf<Pair<Channel, Airing>?>(null) }

    val grid = GuideGrid(w, minutePx, scroll, focus)
    CompositionLocalProvider(LocalBringIntoViewSpec provides PlainBringIntoViewSpec) {
        GuideBody(grid, onlyFavorites, focused, { focused = it }, details, { details = it }) { ch -> viewModel.tune(ch, onWatch) }
    }
}

@Composable
private fun GuideBody(
    grid: GuideGrid,
    onlyFavorites: Boolean,
    focused: Airing?,
    onFocusAiring: (Airing) -> Unit,
    details: Pair<Channel, Airing>?,
    onDetails: (Pair<Channel, Airing>?) -> Unit,
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
                        onDetails = { a -> onDetails(row.channel to a) },
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
            details?.let { (ch, a) ->
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomEnd) {
                    DetailsCard(
                        channel = ch,
                        airing = a,
                        onTune = { onTune(ch) },
                        onClose = {
                            onDetails(null)
                            val rowIndex = w.rows.indexOfFirst { it.channel.id == ch.id }
                            if (rowIndex >= 0) focus.requestAt(rowIndex, maxOf(a.start, w.from))
                        },
                    )
                }
            }
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

/**
 * Minimal-distance bring-into-view (what non-TV Compose does): scroll only as far as needed to make
 * the focused cell fully visible, never to a pivot. Compose keeps its own default internal.
 */
@OptIn(ExperimentalFoundationApi::class)
private object PlainBringIntoViewSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailing = offset + size
        return when {
            offset >= 0f && trailing <= containerSize -> 0f
            offset < 0f && trailing > containerSize -> 0f
            kotlin.math.abs(offset) < kotlin.math.abs(trailing - containerSize) -> offset
            else -> trailing - containerSize
        }
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
    onDetails: (Airing) -> Unit,
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
                            actions = CellActions(
                                tune = onTune,
                                details = { onDetails(airing) },
                                jumpRows = { d -> focus.requestAt((rowIndex + d).coerceIn(0, maxOf(0, focus.rows.lastIndex)), focusTime) },
                                jumpTime = { d -> focus.requestAt(rowIndex, (focusTime + d).coerceIn(window.from, window.to - 1.minutes)) },
                            ),
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
            modifier = Modifier.weight(1f),
        )
        (channel.icon as? ChannelIcon.Glyph)?.let { GlyphIcon(it.name, 32.dp, Modifier.padding(start = 8.dp)) }
    }
}

@Composable
private fun ProgramCell(
    airing: Airing,
    window: GuideViewModel.Window,
    modifier: Modifier,
    onFocus: () -> Unit,
    actions: CellActions,
) {
    val interaction = remember { MutableInteractionSource() }
    val past = airing.end <= window.now
    val current = airing.start <= window.now && window.now < airing.end
    Box(
        modifier
            .fillMaxSize()
            .padding(horizontal = ClickarrDimens.GuideCellGutter / 2)
            .clickarrFocusable(interaction)
            .border(1.dp, ClickarrColors.BgCellBorder, RoundedCornerShape(ClickarrDimens.RadiusCell))
            .onFocusChanged { if (it.isFocused) onFocus() }
            .onKeyEvent { e -> handleCellKey(e, current, actions) }
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
        if (current) {
            // Thin progress along the bottom edge of the program airing now (design language, "Cells").
            val elapsed = (window.now - airing.start).inWholeSeconds.toFloat()
            val length = (airing.end - airing.start).inWholeSeconds.coerceAtLeast(1).toFloat()
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth((elapsed / length).coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(ClickarrColors.AccentGlow),
            )
        }
    }
}

/** What a cell can do; the key map lives in [handleCellKey] (design language, key map). */
private class CellActions(
    val tune: () -> Unit,
    val details: () -> Unit,
    val jumpRows: (Int) -> Unit,
    val jumpTime: (Duration) -> Unit,
)

private fun handleCellKey(e: KeyEvent, current: Boolean, a: CellActions): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    when (e.key) {
        Key.DirectionCenter, Key.Enter -> if (current) a.tune() else a.details()
        Key.MediaPlayPause, Key.MediaPlay -> a.tune()
        Key.ChannelUp -> a.jumpRows(-ROW_JUMP)
        Key.ChannelDown -> a.jumpRows(ROW_JUMP)
        Key.MediaFastForward -> a.jumpTime(TIME_JUMP)
        Key.MediaRewind -> a.jumpTime(-TIME_JUMP)
        else -> return false
    }
    return true
}

/** Details for a program that is not on now: OK on a future cell (design language, "Details"). */
@Composable
private fun DetailsCard(channel: Channel, airing: Airing, onTune: () -> Unit, onClose: () -> Unit) {
    val tuneFocus = remember { FocusRequester() }
    LaunchedEffect(airing) { runCatching { tuneFocus.requestFocus() } }
    BackHandler(onBack = onClose)
    Column(
        Modifier
            .width(560.dp)
            .background(ClickarrColors.BgSurface.copy(alpha = 0.95f), RoundedCornerShape(ClickarrDimens.RadiusCard))
            .padding(ClickarrDimens.CardPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "${channel.number}  ${channel.name.uppercase()}",
            style = ClickarrTextStyles.LabelAllCaps,
            color = ClickarrColors.TextSecondary,
        )
        Text(airing.entry.title, style = ClickarrTextStyles.ProgramTitle, maxLines = 2)
        airing.entry.subtitle?.let { Text(it, style = ClickarrTextStyles.Secondary, color = ClickarrColors.TextSecondary) }
        Text(
            "${timeOfDay(airing.start)} – ${timeOfDay(airing.end)}",
            style = ClickarrTextStyles.Secondary,
            color = ClickarrColors.TextSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onTune, modifier = Modifier.focusRequester(tuneFocus)) { Text("Tune to channel") }
            Button(onClick = onClose) { Text("Close") }
        }
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
