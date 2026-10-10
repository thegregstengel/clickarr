package net.clickarr.feature.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import kotlin.time.Duration.Companion.minutes
import net.clickarr.core.model.ChannelIcon
import net.clickarr.core.model.ChannelId
import net.clickarr.core.model.OrderingMode
import net.clickarr.feature.channels.EditorViewModel.Step
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens
import net.clickarr.ui.design.ClickarrGlyphs
import net.clickarr.ui.design.ClickarrTextStyles
import net.clickarr.ui.design.GlyphIcon

/** Create-channel wizard, or the editor for an existing channel. One step per screen, D-pad friendly. */
@Composable
fun ChannelEditorScreen(channelId: String?, onDone: () -> Unit, viewModel: EditorViewModel = hiltViewModel()) {
    val step by viewModel.step.collectAsState()
    LaunchedEffect(channelId) { if (channelId != null) viewModel.load(ChannelId(channelId)) }
    LaunchedEffect(step) { if (step is Step.Saved || step is Step.Closed) onDone() }

    Column(
        Modifier.fillMaxSize().background(ClickarrColors.BgBase).padding(ClickarrDimens.SafeArea),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(if (channelId == null) "New channel" else "Edit channel", style = ClickarrTextStyles.ScreenTitle)
        StepContent(step, viewModel)
    }
}

@Composable
private fun StepContent(step: Step, viewModel: EditorViewModel) {
    when (val s = step) {
        Step.ChooseKind -> ChooseKind(viewModel)
        Step.Loading -> Text("Loading…", style = ClickarrTextStyles.Secondary, color = ClickarrColors.TextSecondary)
        is Step.ChooseLibrary -> Picker("Which library?", s.libraries, { it.name }) { viewModel.chooseLibrary(it) }
        is Step.PickShows -> PickShows(s, viewModel)
        is Step.PickFilters -> PickFilters(s, viewModel)
        is Step.PickCollection -> Picker("Which collection?", s.collections, { it.name }) { viewModel.confirmCollection(it) }
        is Step.PickPlaylist -> Picker("Which playlist?", s.playlists, { it.name }) { viewModel.confirmPlaylist(it) }
        is Step.Details -> Details(s.draft, viewModel)
        is Step.Saving -> Text("Building the schedule for ${s.name}…", style = ClickarrTextStyles.Secondary)
        is Step.Saved -> Text("Channel ${s.number} saved", style = ClickarrTextStyles.Secondary)
        Step.Closed -> Unit
        is Step.Failed -> {
            Text(s.message, style = ClickarrTextStyles.Secondary, color = ClickarrColors.StatusError)
            Button(onClick = viewModel::restart) { Text("Start over") }
        }
    }
}

@Composable
private fun ChooseKind(vm: EditorViewModel) {
    Text("What goes on this channel?", style = ClickarrTextStyles.Body, color = ClickarrColors.TextSecondary)
    EditorViewModel.Kind.entries.forEach { k ->
        ListItem(
            selected = false,
            onClick = { vm.choose(k) },
            headlineContent = { Text(k.label, style = ClickarrTextStyles.RowTitle) },
            supportingContent = { Text(k.hint, style = ClickarrTextStyles.Caption, color = ClickarrColors.TextSecondary) },
        )
    }
}

@Composable
private fun <T> Picker(title: String, items: List<T>, label: (T) -> String, onPick: (T) -> Unit) {
    Text(title, style = ClickarrTextStyles.Body, color = ClickarrColors.TextSecondary)
    if (items.isEmpty()) Text("Nothing here.", style = ClickarrTextStyles.Secondary, color = ClickarrColors.TextMuted)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(items) { item ->
            ListItem(
                selected = false,
                onClick = { onPick(item) },
                headlineContent = { Text(label(item), style = ClickarrTextStyles.RowTitle) },
            )
        }
    }
}

@Composable
private fun PickShows(s: Step.PickShows, vm: EditorViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("${s.selected.size} selected", style = ClickarrTextStyles.Body, color = ClickarrColors.TextSecondary)
        Button(onClick = vm::confirmShows) { Text("Continue") }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(s.shows, key = { it.ref.id.value }) { show ->
            val picked = show.ref in s.selected
            ListItem(
                selected = picked,
                onClick = { vm.toggleShow(show.ref) },
                headlineContent = { Text(show.title, style = ClickarrTextStyles.RowTitle) },
                supportingContent = {
                    val detail = "${show.episodeCount} episodes" + (show.year?.let { " · $it" } ?: "")
                    Text(detail, style = ClickarrTextStyles.Caption, color = ClickarrColors.TextSecondary)
                },
                trailingContent = { if (picked) Text("✓", style = ClickarrTextStyles.RowTitle) },
            )
        }
    }
}

@Composable
private fun PickFilters(s: Step.PickFilters, vm: EditorViewModel) {
    Text(
        "Narrow it down, or continue for everything in ${s.library.name}.",
        style = ClickarrTextStyles.Body,
        color = ClickarrColors.TextSecondary,
    )
    Button(onClick = vm::confirmFilters) { Text("Continue") }
    Text("Decade", style = ClickarrTextStyles.LabelAllCaps, color = ClickarrColors.TextMuted)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Any", s.filter.decadeStart == null) { vm.setDecade(null) }
        s.decades.forEach { d -> Chip("${d}s", s.filter.decadeStart == d) { vm.setDecade(d) } }
    }
    Text("Genres", style = ClickarrTextStyles.LabelAllCaps, color = ClickarrColors.TextMuted)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(s.genres.take(24)) { g ->
            val on = g in s.filter.genres
            ListItem(
                selected = on,
                onClick = { vm.toggleGenre(g) },
                headlineContent = { Text(g, style = ClickarrTextStyles.RowTitle) },
                trailingContent = { if (on) Text("✓", style = ClickarrTextStyles.RowTitle) },
            )
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        selected = selected,
        onClick = onClick,
        headlineContent = { Text(label, style = ClickarrTextStyles.Caption) },
        modifier = Modifier.widthIn(min = 140.dp, max = 260.dp),
    )
}

/** The Lucide palette as a row of square chips; the first chip is "no icon" (design language 1.6). */
@Composable
private fun IconPicker(selected: ChannelIcon?, onPick: (ChannelIcon?) -> Unit) {
    val current = (selected as? ChannelIcon.Glyph)?.name
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            ListItem(
                selected = current == null,
                onClick = { onPick(null) },
                headlineContent = { Text("None", style = ClickarrTextStyles.Caption) },
                modifier = Modifier.width(96.dp),
            )
        }
        items(ClickarrGlyphs.all, key = { it.name }) { g ->
            ListItem(
                selected = current == g.name,
                onClick = { onPick(ChannelIcon.Glyph(g.name)) },
                headlineContent = { GlyphIcon(g.name, 28.dp) },
                supportingContent = { Text(g.label, style = ClickarrTextStyles.Caption, maxLines = 1) },
                modifier = Modifier.width(112.dp),
            )
        }
    }
}

@Composable
private fun Details(d: EditorViewModel.Draft, vm: EditorViewModel) {
    Text("Name", style = ClickarrTextStyles.LabelAllCaps, color = ClickarrColors.TextMuted)
    Row(
        Modifier
            .fillMaxWidth(0.5f)
            .background(ClickarrColors.BgCell, RoundedCornerShape(ClickarrDimens.RadiusCell))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        BasicTextField(
            value = d.name,
            onValueChange = { v -> vm.updateDraft { it.copy(name = v) } },
            singleLine = true,
            textStyle = ClickarrTextStyles.RowTitle.copy(color = ClickarrColors.TextPrimary),
            modifier = Modifier.testTag("editor.name"),
        )
    }
    Text("Channel number", style = ClickarrTextStyles.LabelAllCaps, color = ClickarrColors.TextMuted)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { vm.updateDraft { it.copy(number = (it.number - 1).coerceAtLeast(1)) } }) { Text("−") }
        Text(d.number.toString(), style = ClickarrTextStyles.ChannelNumber)
        Button(onClick = { vm.updateDraft { it.copy(number = it.number + 1) } }) { Text("+") }
    }
    Text("Icon", style = ClickarrTextStyles.LabelAllCaps, color = ClickarrColors.TextMuted)
    IconPicker(d.icon) { icon -> vm.updateDraft { it.copy(icon = icon) } }
    Text("Order", style = ClickarrTextStyles.LabelAllCaps, color = ClickarrColors.TextMuted)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("In order", d.order == OrderingMode.SEQUENTIAL) { vm.updateDraft { it.copy(order = OrderingMode.SEQUENTIAL) } }
        Chip("Shuffle", d.order == OrderingMode.SHUFFLE) { vm.updateDraft { it.copy(order = OrderingMode.SHUFFLE) } }
    }
    Text("Time slots", style = ClickarrTextStyles.LabelAllCaps, color = ClickarrColors.TextMuted)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Back to back", d.rounding == null) { vm.updateDraft { it.copy(rounding = null) } }
        Chip("15 min", d.rounding == 15.minutes) { vm.updateDraft { it.copy(rounding = 15.minutes) } }
        Chip("30 min", d.rounding == 30.minutes) { vm.updateDraft { it.copy(rounding = 30.minutes) } }
    }
    val editing = d.editing
    if (editing == null) {
        Button(onClick = vm::save, modifier = Modifier.padding(top = 8.dp)) { Text("Create channel") }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = vm::save) { Text("Save changes") }
            Button(onClick = { vm.act(EditorViewModel.EditAction.REFRESH_LINEUP) }) { Text("Refresh lineup from Plex") }
            Button(onClick = { vm.act(EditorViewModel.EditAction.DELETE) }) { Text("Delete channel") }
        }
        Text(
            "A refreshed lineup starts when the current program ends, so every TV switches together.",
            style = ClickarrTextStyles.Caption,
            color = ClickarrColors.TextMuted,
        )
    }
}
