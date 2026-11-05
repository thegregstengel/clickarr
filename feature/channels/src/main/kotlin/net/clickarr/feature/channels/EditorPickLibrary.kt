package net.clickarr.feature.channels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import net.clickarr.core.common.titleSortKey
import net.clickarr.core.model.MediaRef
import net.clickarr.core.model.decadeSet
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrTextStyles

/**
 * Library picker: Genres / Decades / Clear filters on top; title list with multi-select, 0-9 / A-Z
 * jump, and Create (or Apply when editing selections) / Cancel on the far right. Genres and
 * Decades open a multi-select pane with Apply on the right so the D-pad never needs a scroll
 * back to the top.
 */
@Composable
internal fun PickLibrary(s: EditorStep.PickLibrary, vm: EditorViewModel) {
    when (s.pane) {
        LibraryPane.MAIN -> LibraryMain(s, vm)
        LibraryPane.GENRES -> FilterMultiSelect(
            title = "Genres",
            items = s.genres,
            selected = { (s.draftFilter ?: s.filter).genres.contains(it) },
            label = { it },
            onToggle = { vm.onLibrary(LibraryUiAction.ToggleGenre(it)) },
            onApply = { vm.onLibrary(LibraryUiAction.ApplyFilters) },
            onCancel = vm::back,
        )
        LibraryPane.DECADES -> {
            val decadesOn = (s.draftFilter ?: s.filter).decadeSet()
            FilterMultiSelect(
                title = "Decades",
                items = s.decades,
                selected = { it in decadesOn },
                label = { "${it}s" },
                onToggle = { vm.onLibrary(LibraryUiAction.ToggleDecade(it)) },
                onApply = { vm.onLibrary(LibraryUiAction.ApplyFilters) },
                onCancel = vm::back,
            )
        }
    }
}

@Composable
private fun LibraryMain(s: EditorStep.PickLibrary, vm: EditorViewModel) {
    val filtered = remember(s.filter, s.titles) {
        s.titles.filter { EditorLibraryOps.titleMatches(it, s.filter) }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val createFocus = remember { FocusRequester() }
    val toCreate = Modifier.focusProperties { right = createFocus }
    Column(Modifier.fillMaxWidth().fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        FilterActions(vm)
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            TitleList(
                titles = filtered,
                selected = s.selected,
                onToggle = { vm.onLibrary(LibraryUiAction.ToggleTitle(it)) },
                rowModifier = toCreate,
                listState = listState,
                modifier = Modifier.weight(1f),
            )
            LetterIndex(filtered) { key ->
                val index = filtered.indexOfFirst { titleJumpKey(it.title) == key }
                if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
            }
            Column(Modifier.width(200.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "${filtered.size} titles",
                    style = ClickarrTextStyles.Body,
                    color = ClickarrColors.TextSecondary,
                )
                Text(
                    "${s.selected.size} selected",
                    style = ClickarrTextStyles.Caption,
                    color = ClickarrColors.TextMuted,
                )
                val canCreate = EditorLibraryOps.canCreate(s)
                val pickedVisible = EditorLibraryOps.visibleSelected(s)
                val editingSelections = vm.isEditingSelections()
                val confirmLabel = if (editingSelections) "Apply" else "Create"
                Button(
                    onClick = { vm.onLibrary(LibraryUiAction.Confirm) },
                    enabled = canCreate,
                    modifier = Modifier.focusRequester(createFocus),
                ) {
                    Text(confirmLabel)
                }
                Button(onClick = vm::back) { Text("Cancel") }
                Text(
                    when {
                        pickedVisible.isNotEmpty() ->
                            "Press Right from any title to reach $confirmLabel."
                        canCreate ->
                            "$confirmLabel uses every title matching the filters."
                        else ->
                            "Apply a filter or select titles to $confirmLabel."
                    },
                    style = ClickarrTextStyles.Caption,
                    color = ClickarrColors.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun FilterActions(vm: EditorViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { vm.onLibrary(LibraryUiAction.OpenGenres) }) { Text("Genres") }
        Button(onClick = { vm.onLibrary(LibraryUiAction.OpenDecades) }) { Text("Decades") }
        Button(onClick = { vm.onLibrary(LibraryUiAction.ClearFilters) }) { Text("Clear filters") }
    }
}

@Composable
private fun <T> FilterMultiSelect(
    title: String,
    items: List<T>,
    selected: (T) -> Boolean,
    label: (T) -> String,
    onToggle: (T) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    val applyFocus = remember { FocusRequester() }
    val toApply = Modifier.focusProperties { right = applyFocus }
    Row(Modifier.fillMaxWidth().fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = ClickarrTextStyles.Body, color = ClickarrColors.TextSecondary)
            if (items.isEmpty()) {
                Text("Nothing here.", style = ClickarrTextStyles.Secondary, color = ClickarrColors.TextMuted)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(items, key = { label(it) }) { item ->
                        val on = selected(item)
                        ListItem(
                            selected = on,
                            onClick = { onToggle(item) },
                            headlineContent = { Text(label(item), style = ClickarrTextStyles.RowTitle) },
                            trailingContent = { if (on) Text("✓", style = ClickarrTextStyles.RowTitle) },
                            modifier = toApply,
                        )
                    }
                }
            }
        }
        Column(Modifier.width(200.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onApply, modifier = Modifier.focusRequester(applyFocus)) { Text("Apply") }
            Button(onClick = onCancel) { Text("Cancel") }
            Text(
                "Press Right from any row to reach Apply.",
                style = ClickarrTextStyles.Caption,
                color = ClickarrColors.TextMuted,
            )
        }
    }
}

@Composable
private fun TitleList(
    titles: List<LibraryTitle>,
    selected: Set<MediaRef>,
    onToggle: (MediaRef) -> Unit,
    rowModifier: Modifier,
    listState: LazyListState,
    modifier: Modifier,
) {
    if (titles.isEmpty()) {
        Text("Nothing matches these filters.", style = ClickarrTextStyles.Secondary, color = ClickarrColors.TextMuted)
    } else {
        LazyColumn(modifier, state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(titles, key = { _, t -> t.ref.id.value }) { _, title ->
                val picked = title.ref in selected
                ListItem(
                    selected = picked,
                    onClick = { onToggle(title.ref) },
                    headlineContent = { Text(title.title, style = ClickarrTextStyles.RowTitle) },
                    supportingContent = {
                        val line = buildList {
                            title.detail?.let(::add)
                            title.year?.let { add(it.toString()) }
                        }.joinToString(" · ")
                        if (line.isNotEmpty()) {
                            Text(line, style = ClickarrTextStyles.Caption, color = ClickarrColors.TextSecondary)
                        }
                    },
                    trailingContent = { if (picked) Text("✓", style = ClickarrTextStyles.RowTitle) },
                    modifier = rowModifier,
                )
            }
        }
    }
}

@Composable
private fun LetterIndex(titles: List<LibraryTitle>, onKey: (Char) -> Unit) {
    val present = remember(titles) {
        titles.mapNotNull { titleJumpKey(it.title) }.toSet()
    }
    Column(
        Modifier.width(56.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (key in JUMP_KEYS) {
            val enabled = key in present
            ListItem(
                selected = false,
                onClick = { if (enabled) onKey(key) },
                headlineContent = {
                    Text(
                        key.toString(),
                        style = ClickarrTextStyles.Caption,
                        color = if (enabled) ClickarrColors.TextPrimary else ClickarrColors.TextMuted,
                    )
                },
                modifier = Modifier.width(56.dp),
            )
        }
    }
}

@Composable
internal fun EditorChip(label: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        selected = selected,
        onClick = onClick,
        headlineContent = { Text(label, style = ClickarrTextStyles.Caption) },
        modifier = Modifier.widthIn(min = 140.dp, max = 260.dp),
    )
}

/** First character of the sort key when it is a digit or letter; used by the jump index. */
internal fun titleJumpKey(title: String): Char? {
    val c = titleSortKey(title).firstOrNull()?.uppercaseChar() ?: return null
    return when {
        c in '0'..'9' || c in 'A'..'Z' -> c
        else -> null
    }
}

private val JUMP_KEYS: List<Char> = ('0'..'9') + ('A'..'Z')
