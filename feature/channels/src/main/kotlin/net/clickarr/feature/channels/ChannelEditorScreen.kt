package net.clickarr.feature.channels

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import net.clickarr.core.model.ChannelId
import net.clickarr.core.model.ProgrammingSource
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens
import net.clickarr.ui.design.ClickarrTextStyles

/** Custom channel wizard, or the editor for an existing channel. One step per screen, D-pad friendly. */
@Composable
fun ChannelEditorScreen(channelId: String?, onDone: () -> Unit, viewModel: EditorViewModel = hiltViewModel()) {
    val step by viewModel.step.collectAsState()
    LaunchedEffect(channelId) {
        if (channelId != null) viewModel.load(ChannelId(channelId)) else viewModel.startCreate()
    }
    LaunchedEffect(step) { if (step is EditorStep.Saved || step is EditorStep.Closed) onDone() }
    val editing = (step as? EditorStep.Details)?.draft?.editing != null
    val atStart = step is EditorStep.ChooseLibrary || step is EditorStep.Failed
    val canBack = step !is EditorStep.Loading && step !is EditorStep.Saving &&
        step !is EditorStep.Saved && step !is EditorStep.Closed && !editing
    BackHandler(enabled = canBack) {
        if (atStart) onDone() else viewModel.back()
    }

    Column(
        Modifier.fillMaxSize().background(ClickarrColors.BgBase).padding(ClickarrDimens.SafeArea),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            if (channelId == null) "Custom channel" else "Edit channel",
            style = ClickarrTextStyles.ScreenTitle,
        )
        StepContent(step, viewModel)
    }
}

@Composable
private fun StepContent(step: EditorStep, viewModel: EditorViewModel) {
    when (val s = step) {
        EditorStep.Loading -> Text("Loading…", style = ClickarrTextStyles.Secondary, color = ClickarrColors.TextSecondary)
        is EditorStep.ChooseLibrary -> Picker("Which library?", s.libraries, { it.name }) { viewModel.chooseLibrary(it) }
        is EditorStep.ChooseMode -> ChooseMode(s, viewModel)
        is EditorStep.PickShows -> PickShows(s, viewModel)
        is EditorStep.PickFilters -> PickFilters(s, viewModel)
        is EditorStep.PickCollection -> Picker("Which collection?", s.collections, { it.name }) {
            viewModel.confirmSource(ProgrammingSource.Collection(it.ref), it.name)
        }
        is EditorStep.PickPlaylist -> Picker("Which playlist?", s.playlists, { it.name }) {
            viewModel.confirmSource(ProgrammingSource.Playlist(it.ref), it.name)
        }
        is EditorStep.Details -> EditorDetails(s.draft, viewModel)
        is EditorStep.Saving -> Text(
            if (s.building) "Building the schedule for ${s.name}…" else "Saving ${s.name}…",
            style = ClickarrTextStyles.Secondary,
        )
        is EditorStep.Saved -> Text("Channel ${s.number} saved", style = ClickarrTextStyles.Secondary)
        EditorStep.Closed -> Unit
        is EditorStep.Failed -> {
            Text(s.message, style = ClickarrTextStyles.Secondary, color = ClickarrColors.StatusError)
            Button(onClick = viewModel::startCreate) { Text("Start over") }
        }
    }
}

@Composable
private fun ChooseMode(s: EditorStep.ChooseMode, vm: EditorViewModel) {
    Text(
        "What from ${s.library.name}?",
        style = ClickarrTextStyles.Body,
        color = ClickarrColors.TextSecondary,
    )
    s.modes.forEach { mode ->
        ListItem(
            selected = false,
            onClick = { vm.chooseMode(mode) },
            headlineContent = { Text(mode.label, style = ClickarrTextStyles.RowTitle) },
            supportingContent = { Text(mode.hint, style = ClickarrTextStyles.Caption, color = ClickarrColors.TextSecondary) },
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

/** Shows on the left; the picked list and Continue on the right, one Right press away from any row. */
@Composable
private fun PickShows(s: EditorStep.PickShows, vm: EditorViewModel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
        Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${s.selected.size} selected", style = ClickarrTextStyles.Body, color = ClickarrColors.TextSecondary)
            val picked = s.shows.filter { it.ref in s.selected }
            picked.take(PICKED_PREVIEW).forEach {
                Text(it.title, style = ClickarrTextStyles.Caption, color = ClickarrColors.TextSecondary, maxLines = 1)
            }
            if (picked.size > PICKED_PREVIEW) {
                Text(
                    "and ${picked.size - PICKED_PREVIEW} more",
                    style = ClickarrTextStyles.Caption,
                    color = ClickarrColors.TextMuted,
                )
            }
            Button(onClick = vm::confirmShows) { Text("Continue") }
            Text(
                "Press Right from any show to get here.",
                style = ClickarrTextStyles.Caption,
                color = ClickarrColors.TextMuted,
            )
        }
    }
}

private const val PICKED_PREVIEW = 8
