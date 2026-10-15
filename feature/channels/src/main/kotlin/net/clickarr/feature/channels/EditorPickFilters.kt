package net.clickarr.feature.channels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import net.clickarr.core.model.MediaFilter
import net.clickarr.core.model.decadeSet
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrTextStyles

/**
 * Whole library: genre and decade filters (multi-select), a live title list, A–Z jump index, and
 * Create / Cancel on the far right so they stay one Right press away from any row.
 *
 * Decades use [FlowRow] with fixed chip widths so every decade wraps onto later rows and stays
 * reachable with the D-pad (a single [Row] clipped focus after the 1990s chip).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PickFilters(s: EditorStep.PickFilters, vm: EditorViewModel) {
    val filtered = remember(s.filter, s.titles) { s.titles.filter { previewMatches(it, s.filter) } }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val createFocus = remember { FocusRequester() }
    val toCreate = Modifier.focusProperties { right = createFocus }
    Row(Modifier.fillMaxWidth().fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        FilterPane(s, vm, Modifier.width(340.dp))
        TitleList(filtered, toCreate, listState, Modifier.weight(1f))
        LetterIndex(filtered) { letter ->
            val index = filtered.indexOfFirst { titleSortKey(it.title).startsWith(letter, ignoreCase = true) }
            if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
        }
        Column(Modifier.width(200.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "${filtered.size} titles",
                style = ClickarrTextStyles.Body,
                color = ClickarrColors.TextSecondary,
            )
            Button(onClick = vm::confirmFilters, modifier = Modifier.focusRequester(createFocus)) {
                Text("Create")
            }
            Button(onClick = vm::back) { Text("Cancel") }
            Text(
                "Press Right from any title to reach Create.",
                style = ClickarrTextStyles.Caption,
                color = ClickarrColors.TextMuted,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterPane(s: EditorStep.PickFilters, vm: EditorViewModel, modifier: Modifier) {
    val decadesOn = s.filter.decadeSet()
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Narrow ${s.library.name}, or Create for everything.",
            style = ClickarrTextStyles.Body,
            color = ClickarrColors.TextSecondary,
        )
        Text("Genres", style = ClickarrTextStyles.LabelAllCaps, color = ClickarrColors.TextMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EditorChip("Any", s.filter.genres.isEmpty()) { vm.toggleGenre(null) }
            s.genres.take(MAX_GENRE_CHIPS).forEach { g ->
                EditorChip(g, g in s.filter.genres) { vm.toggleGenre(g) }
            }
        }
        Text("Decades", style = ClickarrTextStyles.LabelAllCaps, color = ClickarrColors.TextMuted)
        // Fixed widths so every decade wraps onto later rows; a plain Row clipped focus after 1990.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DecadeChip("Any", decadesOn.isEmpty()) { vm.toggleDecade(null) }
            s.decades.forEach { d ->
                DecadeChip("${d}s", d in decadesOn) { vm.toggleDecade(d) }
            }
        }
    }
}

@Composable
private fun TitleList(
    titles: List<PreviewTitle>,
    rowModifier: Modifier,
    listState: LazyListState,
    modifier: Modifier,
) {
    if (titles.isEmpty()) {
        Text("Nothing matches these filters.", style = ClickarrTextStyles.Secondary, color = ClickarrColors.TextMuted)
    } else {
        LazyColumn(modifier, state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(titles, key = { _, t -> "${t.title}-${t.year}" }) { _, title ->
                ListItem(
                    selected = false,
                    onClick = {},
                    headlineContent = { Text(title.title, style = ClickarrTextStyles.RowTitle) },
                    supportingContent = {
                        title.year?.let { year ->
                            Text(year.toString(), style = ClickarrTextStyles.Caption, color = ClickarrColors.TextSecondary)
                        }
                    },
                    modifier = rowModifier,
                )
            }
        }
    }
}

@Composable
private fun LetterIndex(titles: List<PreviewTitle>, onLetter: (String) -> Unit) {
    val present = remember(titles) {
        titles.mapNotNull { titleSortKey(it.title).firstOrNull()?.uppercaseChar() }
            .filter { it in 'A'..'Z' }
            .toSet()
    }
    Column(
        Modifier.width(56.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (letter in 'A'..'Z') {
            val enabled = letter in present
            ListItem(
                selected = false,
                onClick = { if (enabled) onLetter(letter.toString()) },
                headlineContent = {
                    Text(
                        letter.toString(),
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

@Composable
private fun DecadeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        selected = selected,
        onClick = onClick,
        headlineContent = { Text(label, style = ClickarrTextStyles.Caption) },
        modifier = Modifier.width(140.dp),
    )
}

/** Preview rows use the same genre/decade rules as a library [MediaFilter]. */
private fun previewMatches(title: PreviewTitle, filter: MediaFilter): Boolean {
    if (filter.genres.isNotEmpty()) {
        val ok = title.genres.any { g -> filter.genres.any { it.equals(g, ignoreCase = true) } }
        if (!ok) return false
    }
    val decades = filter.decadeSet()
    if (decades.isNotEmpty()) {
        val year = title.year ?: return false
        if (decades.none { d -> year in d until d + 10 }) return false
    }
    return true
}

private const val MAX_GENRE_CHIPS = 24
