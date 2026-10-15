package net.clickarr.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import net.clickarr.core.common.AppTime
import net.clickarr.core.model.Channel
import net.clickarr.core.model.ChannelIcon
import net.clickarr.core.model.ChannelId
import net.clickarr.core.model.Holiday
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens
import net.clickarr.ui.design.ClickarrTextStyles
import net.clickarr.ui.design.GlyphIcon

/** Settings, Channels: create, holiday, suggest, refresh, and the channel list with reorder/delete. */
@Composable
internal fun ChannelsPane(vm: SettingsViewModel, actions: SettingsActions, park: FocusPark) {
    val list by vm.channelList.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val message by vm.message.collectAsState()
    var movingId by remember { mutableStateOf<ChannelId?>(null) }
    BackHandler(enabled = movingId != null) { movingId = null }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = actions.onCreateChannel) { Text("Create channel") }
        Button(onClick = actions.onHolidayChannels) { Text("Holiday channels") }
        Button(onClick = actions.onSuggestChannels) { Text("Suggest channels") }
        Button(onClick = vm::refreshAllLineups) { Text("Refresh all lineups") }
    }
    message?.let { Caption(it) }
    val refresh by vm.refreshInfo.collectAsState()
    Label("Keep lineups current")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Automatic, daily", selected = refresh.automatic) { vm.setAutoRefresh(true) }
        Chip("Only when I press Refresh", selected = !refresh.automatic) { vm.setAutoRefresh(false) }
    }
    Caption(refreshCaption(refresh))
    val g by vm.general.collectAsState()
    Label("Guide shows the next")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(3, 6, 12, 24).forEach { h -> Chip("$h hours", selected = g.guideHours == h) { vm.setGuideHours(h) } }
    }
    val favoritesTab by vm.favoritesTab.collectAsState()
    Label("Favorites tab")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Show", selected = favoritesTab) { vm.setFavoritesTab(true) }
        Chip("Hide", selected = !favoritesTab) { vm.setFavoritesTab(false) }
    }
    Caption("Hide takes Favorites out of the top row. Stars in the guide still work, for whenever you want it back.")
    if (list.isEmpty()) {
        Caption("No channels yet. Create one from a show, a whole library, a collection, or a playlist.")
    }
    val year = AppTime.local(vm.now()).year
    list.forEach { ch ->
        key(ch.id) {
            ChannelRow(
                ch = ch,
                favorite = ch.id in favorites,
                year = year,
                moving = movingId == ch.id,
                onEdit = { actions.onEditChannel(ch.id) },
                onToggleMove = { movingId = if (movingId == ch.id) null else ch.id },
                onMove = { delta -> vm.moveChannel(ch.id, delta) },
                onDelete = { park.park(); movingId = null; vm.deleteChannel(ch.id) },
            )
        }
    }
}

/**
 * One channel in Settings, Channels. Regular channels get a reorder handle on the left and Delete on the
 * right; holiday channels do not (they are managed under Holiday channels).
 */
@Composable
@Suppress("LongParameterList")
private fun ChannelRow(
    ch: Channel,
    favorite: Boolean,
    year: Int,
    moving: Boolean,
    onEdit: () -> Unit,
    onToggleMove: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    val regular = ch.holiday == null
    var confirmDelete by remember { mutableStateOf(false) }
    var restoreDeleteFocus by remember { mutableStateOf(true) }
    val deleteFocus = remember { FocusRequester() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (regular) ReorderHandle(moving, onToggleMove, onMove)
        ListItem(
            selected = false,
            onClick = onEdit,
            headlineContent = { Text(ch.name, style = ClickarrTextStyles.RowTitle) },
            supportingContent = ch.holiday?.let { h ->
                {
                    Text(holidayLabel(h, year), style = ClickarrTextStyles.Caption, color = ClickarrColors.TextSecondary)
                }
            },
            leadingContent = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(ch.number.toString(), style = ClickarrTextStyles.RowTitle, modifier = Modifier.width(56.dp))
                    (ch.icon as? ChannelIcon.Glyph)?.let { GlyphIcon(it.name, 32.dp) }
                }
            },
            trailingContent = {
                if (favorite) Text("★", style = ClickarrTextStyles.RowTitle, color = ClickarrColors.AccentGlow)
            },
            modifier = Modifier.weight(1f),
        )
        if (regular) {
            Button(
                onClick = { confirmDelete = true },
                modifier = Modifier
                    .focusRequester(deleteFocus)
                    .semantics { contentDescription = "Delete ${ch.name}" },
            ) { Text("Delete") }
        }
    }
    if (confirmDelete) {
        DeleteChannelDialog(
            name = ch.name,
            onConfirm = {
                restoreDeleteFocus = false
                confirmDelete = false
                onDelete()
            },
            onDismiss = {
                restoreDeleteFocus = true
                confirmDelete = false
            },
        )
    }
    var wasConfirm by remember { mutableStateOf(false) }
    LaunchedEffect(confirmDelete) {
        if (wasConfirm && !confirmDelete && restoreDeleteFocus) {
            runCatching { deleteFocus.requestFocus() }
        }
        wasConfirm = confirmDelete
    }
}

/**
 * Select to enter move mode; Up/Down then moves among regulars; select again to commit. Outside move mode,
 * Up/Down leave the key alone so focus walks the reorder column like the name and Delete columns.
 */
@Composable
private fun ReorderHandle(moving: Boolean, onToggle: () -> Unit, onMove: (Int) -> Unit) {
    var drag by remember { mutableFloatStateOf(0f) }
    val label = if (moving) "Reorder, moving" else "Reorder"
    Button(
        onClick = onToggle,
        modifier = Modifier
            .width(56.dp)
            .semantics { contentDescription = label }
            .onPreviewKeyEvent { e ->
                if (!moving || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionUp -> {
                        onMove(-1)
                        true
                    }
                    Key.DirectionDown -> {
                        onMove(+1)
                        true
                    }
                    else -> false
                }
            }
            .pointerInput(moving) {
                if (!moving) return@pointerInput
                detectVerticalDragGestures(
                    onDragEnd = { drag = 0f },
                    onVerticalDrag = { _, amount ->
                        drag += amount
                        when {
                            drag <= -DRAG_STEP -> {
                                onMove(-1)
                                drag = 0f
                            }
                            drag >= DRAG_STEP -> {
                                onMove(+1)
                                drag = 0f
                            }
                        }
                    },
                )
            },
    ) {
        Text(
            "≡",
            color = if (moving) ClickarrColors.AccentGlow else ClickarrColors.TextPrimary,
        )
    }
}

/** Confirm before removing a channel; Back or Cancel leaves it. Matches the Settings picker dialog shell. */
@Composable
private fun DeleteChannelDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val confirmFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { confirmFocus.requestFocus() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(ClickarrColors.BgBase.copy(alpha = SCRIM_ALPHA)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .width(DIALOG_WIDTH)
                    .clip(RoundedCornerShape(ClickarrDimens.RadiusCard))
                    .background(ClickarrColors.BgPanel)
                    .padding(ClickarrDimens.CardPadding),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "Are you sure you want to delete $name?",
                    style = ClickarrTextStyles.RowTitle,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onConfirm, modifier = Modifier.focusRequester(confirmFocus)) {
                        Text("Delete")
                    }
                    Button(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

private const val DRAG_STEP = 48f
private val DIALOG_WIDTH = 560.dp
private const val SCRIM_ALPHA = 0.85f

@Composable
private fun Caption(text: String) = Text(text, style = ClickarrTextStyles.Secondary, color = ClickarrColors.TextSecondary)

@Composable
private fun Label(text: String) = Text(text, style = ClickarrTextStyles.LabelAllCaps, color = ClickarrColors.TextMuted)

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        selected = selected,
        onClick = onClick,
        headlineContent = { Text(label, style = ClickarrTextStyles.Caption) },
        modifier = Modifier.widthIn(min = 140.dp, max = 260.dp),
    )
}

/** "Holiday: Christmas, Dec 1 to Dec 25" for the channel list, with this year's dates. */
private fun holidayLabel(h: Holiday, year: Int): String {
    val w = h.window(year)
    fun md(d: kotlinx.datetime.LocalDate) =
        "${d.month.name.lowercase().replaceFirstChar(Char::uppercase).take(3)} ${d.dayOfMonth}"
    return "Holiday: ${h.label}, ${md(w.start)} to ${md(w.endInclusive)}"
}

private fun refreshCaption(r: SettingsViewModel.RefreshInfo): String {
    val last = r.lastAt?.let { at ->
        val local = AppTime.local(at)
        val month = local.month.name.lowercase().replaceFirstChar(Char::uppercase).take(MONTH_ABBREVIATION)
        "$month ${local.dayOfMonth}, ${AppTime.timeOfDay(at)}"
    }
    return when {
        !r.automatic -> "New episodes join a channel only when you press Refresh."
        last == null ->
            "Each channel's source is re-read from the server once a day; new episodes join at the next program boundary."
        else -> "Last automatic refresh $last: ${r.note ?: "no changes"}."
    }
}

private const val MONTH_ABBREVIATION = 3
