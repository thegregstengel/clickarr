package net.clickarr.feature.guide

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import net.clickarr.core.common.AppTime
import net.clickarr.core.model.Airing
import net.clickarr.core.model.Channel
import net.clickarr.core.model.ChannelIcon
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens
import net.clickarr.ui.design.ClickarrTextStyles
import net.clickarr.ui.design.GlyphIcon
import net.clickarr.ui.design.clickarrFocusable

private val CHANNEL_COLUMN = 248.dp
private val ROW_HEIGHT = 64.dp
private val HEADER_HEIGHT = 40.dp
private val PREVIEW_HEIGHT = 104.dp
private val PREVIEW_THUMB_WIDTH = 170.dp
private const val ROW_JUMP = 5
private const val VISIBLE_HALF_HOURS = 4
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
fun GuideScreen(
    onWatch: () -> Unit,
    onlyFavorites: Boolean = false,
    takeFocus: Boolean = true,
    viewModel: GuideViewModel = hiltViewModel(),
) {
    val window by viewModel.window.collectAsState()
    val preview by viewModel.preview.collectAsState()
    val all = window ?: return
    val w = if (onlyFavorites) all.copy(rows = all.rows.filter { it.channel.id.value in all.favorites }) else all
    val focus = remember(w.rows.size) { GuideFocus(w.rows.size) }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    var focused by remember { mutableStateOf<Airing?>(null) }
    var details by remember { mutableStateOf<Pair<Channel, Airing>?>(null) }

    val actions = GuideActions(
        onFocusAiring = { focused = it; viewModel.focus(it) },
        onDetails = { details = it },
        onTune = { ch -> viewModel.tune(ch, onWatch) },
        onToggleFavorite = { ch -> viewModel.toggleFavorite(ch.id) },
    )
    // Two hours fill the grid at any size setting: four half-hour columns beside the channel column.
    BoxWithConstraints {
        val halfHour = (maxWidth - ClickarrDimens.SafeArea - CHANNEL_COLUMN) / VISIBLE_HALF_HOURS
        val minutePx = with(density) { (halfHour / 30).toPx() }
        val grid = GuideGrid(w, minutePx, halfHour, scroll, focus)
        CompositionLocalProvider(LocalBringIntoViewSpec provides PlainBringIntoViewSpec) {
            GuideBody(grid, onlyFavorites, takeFocus, focused, preview, details, actions)
        }
    }
}

/** Everything a row can ask the screen to do. */
private class GuideActions(
    val onFocusAiring: (Airing) -> Unit,
    val onDetails: (Pair<Channel, Airing>?) -> Unit,
    val onTune: (Channel) -> Unit,
    val onToggleFavorite: (Channel) -> Unit,
)

@Composable
private fun GuideBody(
    grid: GuideGrid,
    onlyFavorites: Boolean,
    takeFocus: Boolean,
    focused: Airing?,
    preview: GuideViewModel.Preview?,
    details: Pair<Channel, Airing>?,
    actions: GuideActions,
) {
    val w = grid.window
    val scroll = grid.scroll
    val minutePx = grid.minutePx
    val focus = grid.focus
    // Entering the grid from the tab row lands wherever Compose's search puts it; the first cell to gain focus
    // on entry hands it to the current program instead, so Down from a tab always opens on what is on now.
    val entered = remember { booleanArrayOf(false) }
    fun currentRow() = w.rows.indexOfFirst { it.channel.id.value == w.currentChannelId }.takeIf { it >= 0 } ?: 0
    val onEnter: () -> Unit = {
        if (!entered[0]) {
            entered[0] = true
            focus.requestAt(currentRow(), w.now)
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(ClickarrColors.BgBase)
            .padding(horizontal = ClickarrDimens.SafeArea / 2)
            .onFocusChanged { if (!it.hasFocus) entered[0] = false },
    ) {
        TimeHeader(w.from, w.to, grid.halfHourWidth, scroll)
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
                        isFavorite = row.channel.id.value in w.favorites,
                        actions = actions,
                        onEnter = onEnter,
                    )
                }
            }
            // Initial focus: the program airing now on the current channel, else the first row's current program.
            LaunchedEffect(w.rows.size, w.currentChannelId, takeFocus) {
                if (takeFocus) {
                    entered[0] = true
                    focus.requestAt(currentRow(), w.now)
                }
            }
            NowLine(w, minutePx, scroll.value)
            details?.let { (ch, a) ->
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomEnd) {
                    DetailsCard(
                        channel = ch,
                        airing = a,
                        onTune = { actions.onTune(ch) },
                        onClose = {
                            actions.onDetails(null)
                            val rowIndex = w.rows.indexOfFirst { it.channel.id == ch.id }
                            if (rowIndex >= 0) focus.requestAt(rowIndex, maxOf(a.start, w.from))
                        },
                    )
                }
            }
        }
        PreviewCard(focused, preview?.takeIf { it.ref == focused?.entry?.ref })
    }
}

@Composable
private fun TimeHeader(from: Instant, to: Instant, halfHourWidth: Dp, scroll: androidx.compose.foundation.ScrollState) {
    Row(Modifier.fillMaxWidth().height(HEADER_HEIGHT)) {
        Box(Modifier.width(CHANNEL_COLUMN), contentAlignment = Alignment.CenterStart) {
            Text("Today", style = ClickarrTextStyles.Caption, color = ClickarrColors.TextSecondary)
        }
        Row(Modifier.horizontalScroll(scroll, enabled = false)) {
            var t = from
            while (t < to) {
                Box(Modifier.width(halfHourWidth), contentAlignment = Alignment.CenterStart) {
                    Text(headerLabel(t), style = ClickarrTextStyles.Secondary, color = ClickarrColors.TextSecondary)
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
    val halfHourWidth: Dp,
    val scroll: androidx.compose.foundation.ScrollState,
    val focus: GuideFocus,
)

@Composable
private fun GuideRow(
    row: GuideViewModel.Row,
    rowIndex: Int,
    grid: GuideGrid,
    isCurrent: Boolean,
    isFavorite: Boolean,
    actions: GuideActions,
    onEnter: () -> Unit,
) {
    val window = grid.window
    val minutePx = grid.minutePx
    val scroll = grid.scroll
    val focus = grid.focus
    focus.airings[rowIndex].let { it.clear(); it.addAll(row.airings) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT).padding(vertical = ClickarrDimens.GuideCellGutter / 2)) {
        ChannelCell(row.channel, isCurrent, isFavorite, onEnter) { actions.onToggleFavorite(row.channel) }
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
                                onEnter()
                                actions.onFocusAiring(airing)
                                // Keep the focused cell inside the visible window.
                                val leftPx = ((focusTime - window.from).inWholeMinutes * minutePx).toInt()
                                val margin = with(density) { grid.halfHourWidth.toPx() }.toInt()
                                val target = (leftPx - margin).coerceAtLeast(0)
                                if (leftPx < scroll.value || leftPx > scroll.value + scroll.viewportSize - margin) {
                                    scope.launch { scroll.animateScrollTo(target) }
                                }
                            },
                            actions = CellActions(
                                tune = { actions.onTune(row.channel) },
                                details = { actions.onDetails(row.channel to airing) },
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

/** Number, name, glyph, and a star. OK on the cell toggles the channel as a favorite (the Favorites tab). */
@Composable
private fun ChannelCell(
    channel: Channel,
    isCurrent: Boolean,
    isFavorite: Boolean,
    onEnter: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .width(CHANNEL_COLUMN)
            .fillMaxHeight()
            .padding(end = 16.dp)
            .clickarrFocusable(
                interaction,
                idleColor = if (isCurrent) ClickarrColors.AccentPrimaryDeep else ClickarrColors.BgPanel,
                selected = isCurrent,
            )
            .onFocusChanged { if (it.isFocused) onEnter() }
            .onKeyEvent { e ->
                val select = e.type == KeyEventType.KeyDown && (e.key == Key.DirectionCenter || e.key == Key.Enter)
                if (select) onToggleFavorite()
                select
            }
            .focusable(interactionSource = interaction)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(channel.number.toString(), style = ClickarrTextStyles.RowTitle, modifier = Modifier.width(48.dp))
        Text(
            channel.name.uppercase(),
            style = ClickarrTextStyles.LabelAllCaps.copy(fontSize = 14.sp, lineHeight = 17.sp, letterSpacing = 0.5.sp),
            color = ClickarrColors.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        (channel.icon as? ChannelIcon.Glyph)?.let { GlyphIcon(it.name, 28.dp, Modifier.padding(start = 8.dp)) }
        Text(
            if (isFavorite) "★" else "☆",
            style = ClickarrTextStyles.RowTitle,
            color = if (isFavorite) ClickarrColors.AccentGlow else ClickarrColors.TextMuted,
            modifier = Modifier.padding(start = 8.dp).semantics {
                contentDescription = if (isFavorite) "Favorite. Press OK to remove." else "Not a favorite. Press OK to add."
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProgramCell(
    airing: Airing,
    window: GuideViewModel.Window,
    modifier: Modifier,
    onFocus: () -> Unit,
    actions: CellActions,
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    val past = airing.end <= window.now
    val current = airing.start <= window.now && window.now < airing.end
    val label = listOfNotNull(airing.entry.title, airing.entry.subtitle).joinToString("  ·  ")
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
        // Scrolls the label across the cell while focused, only when it does not fit (basicMarquee is a no-op otherwise).
        Text(
            label,
            style = ClickarrTextStyles.RowTitle,
            color = if (past) ClickarrColors.TextMuted else ClickarrColors.TextPrimary,
            maxLines = 1,
            softWrap = false,
            overflow = if (isFocused) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = if (isFocused) Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 800) else Modifier,
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

/** The focused program: thumbnail, title, episode and slot, and the synopsis once the server answers. */
@Composable
private fun PreviewCard(airing: Airing?, preview: GuideViewModel.Preview?) {
    Row(
        Modifier.fillMaxWidth().height(PREVIEW_HEIGHT).padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (airing == null) return
        Box(
            Modifier
                .width(PREVIEW_THUMB_WIDTH)
                .fillMaxHeight()
                .clip(RoundedCornerShape(ClickarrDimens.RadiusCell))
                .background(ClickarrColors.BgCell),
        ) {
            preview?.thumbUrl?.let {
                AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(airing.entry.title, style = ClickarrTextStyles.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val slot = "${timeOfDay(airing.start)} – ${timeOfDay(airing.end)}"
            Text(
                listOfNotNull(airing.entry.subtitle, slot).joinToString("   "),
                style = ClickarrTextStyles.Secondary,
                color = ClickarrColors.TextSecondary,
                maxLines = 1,
            )
            preview?.summary?.let {
                Text(
                    it,
                    style = ClickarrTextStyles.Caption,
                    color = ClickarrColors.TextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Midnight columns carry the day, so a window that crosses it reads "Sat 12:00 AM". */
private fun headerLabel(instant: Instant): String {
    val local = AppTime.local(instant)
    val time = timeOfDay(instant)
    if (local.hour != 0 || local.minute != 0) return time
    val day = local.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
    return "$day $time"
}

private fun timeOfDay(instant: Instant): String = AppTime.timeOfDay(instant)
