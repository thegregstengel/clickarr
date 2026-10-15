package net.clickarr.feature.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import kotlin.time.Duration.Companion.minutes
import net.clickarr.core.model.ChannelIcon
import net.clickarr.core.model.EpisodeRuns
import net.clickarr.core.model.OrderingMode
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens
import net.clickarr.ui.design.ClickarrGlyphs
import net.clickarr.ui.design.ClickarrTextStyles
import net.clickarr.ui.design.GlyphIcon

/** The form is taller than a 1080p screen once the icon row is in; the focused control scrolls into view. */
@Composable
internal fun EditorDetails(d: EditorDraft, vm: EditorViewModel) {
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DetailsFields(d, vm)
    }
}

@Composable
private fun DetailsFields(d: EditorDraft, vm: EditorViewModel) {
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
    val firstIcon = remember { FocusRequester() }
    val toIcons = Modifier.focusProperties { down = firstIcon }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { vm.updateDraft { it.copy(number = (it.number - 1).coerceAtLeast(1)) } }, modifier = toIcons) {
            Text("−")
        }
        Text(d.number.toString(), style = ClickarrTextStyles.ChannelNumber)
        Button(onClick = { vm.updateDraft { it.copy(number = it.number + 1) } }, modifier = toIcons) { Text("+") }
    }
    val numbers by vm.numbers.collectAsState()
    val owner = numbers.let { vm.numberOwner(d.number, d.editing) }
    Text(
        if (owner == null) "Free. Numbers start at the next one not in use." else "Taken: channel ${d.number} is ${owner.name}.",
        style = ClickarrTextStyles.Caption,
        color = if (owner == null) ClickarrColors.TextMuted else ClickarrColors.StatusWarn,
    )
    Text("Icon", style = ClickarrTextStyles.LabelAllCaps, color = ClickarrColors.TextMuted)
    IconPicker(d.icon, firstIcon) { icon -> vm.updateDraft { it.copy(icon = icon) } }
    Text("Order", style = ClickarrTextStyles.LabelAllCaps, color = ClickarrColors.TextMuted)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EditorChip("In order", d.order == OrderingMode.SEQUENTIAL) {
            vm.updateDraft { it.copy(order = OrderingMode.SEQUENTIAL) }
        }
        EditorChip("Shuffle", d.order == OrderingMode.SHUFFLE) {
            vm.updateDraft { it.copy(order = OrderingMode.SHUFFLE) }
        }
    }
    Text("Episodes in a row", style = ClickarrTextStyles.LabelAllCaps, color = ClickarrColors.TextMuted)
    Text(
        "How many episodes of one show play back to back, in aired order, before the channel moves on.",
        style = ClickarrTextStyles.Caption,
        color = ClickarrColors.TextMuted,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EditorChip("One at a time", d.runs == null) { vm.updateDraft { it.copy(runs = null) } }
        RUN_CHOICES.forEach { (label, runs) ->
            EditorChip(label, d.runs == runs) { vm.updateDraft { it.copy(runs = runs) } }
        }
    }
    d.editing?.holiday?.let { h ->
        Text(
            "Holiday channel: on air for ${h.label} each year. Change what plays on it in Settings, Channels, Holiday channels.",
            style = ClickarrTextStyles.Caption,
            color = ClickarrColors.TextMuted,
        )
    }
    Text("Time slots", style = ClickarrTextStyles.LabelAllCaps, color = ClickarrColors.TextMuted)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EditorChip("Back to back", d.rounding == null) { vm.updateDraft { it.copy(rounding = null) } }
        EditorChip("15 min", d.rounding == 15.minutes) { vm.updateDraft { it.copy(rounding = 15.minutes) } }
        EditorChip("30 min", d.rounding == 30.minutes) { vm.updateDraft { it.copy(rounding = 30.minutes) } }
        EditorChip("1 hour", d.rounding == 60.minutes) { vm.updateDraft { it.copy(rounding = 60.minutes) } }
    }
    d.problem?.let { Text(it, style = ClickarrTextStyles.Secondary, color = ClickarrColors.StatusWarn) }
    DetailsActions(d, vm)
}

@Composable
private fun DetailsActions(d: EditorDraft, vm: EditorViewModel) {
    val editing = d.editing
    if (editing == null) {
        Button(onClick = vm::save, modifier = Modifier.padding(top = 8.dp)) { Text("Create channel") }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = vm::save) { Text("Save changes") }
            Button(onClick = { vm.act(EditAction.REFRESH_LINEUP) }) { Text("Refresh lineup from the server") }
            Button(onClick = { vm.act(EditAction.DELETE) }) { Text("Delete channel") }
        }
        Text(
            "A refreshed lineup starts when the current program ends, so every TV switches together.",
            style = ClickarrTextStyles.Caption,
            color = ClickarrColors.TextMuted,
        )
    }
}

/** The Lucide palette as a row of square chips; the first chip is "no icon" (design language 1.6). */
@Composable
private fun IconPicker(selected: ChannelIcon?, first: FocusRequester, onPick: (ChannelIcon?) -> Unit) {
    val current = (selected as? ChannelIcon.Glyph)?.name
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            ListItem(
                selected = current == null,
                onClick = { onPick(null) },
                headlineContent = { Text("None", style = ClickarrTextStyles.Caption) },
                modifier = Modifier.width(96.dp).focusRequester(first),
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

private val RUN_CHOICES = listOf(
    "2 in a row" to EpisodeRuns(2, 2),
    "3 in a row" to EpisodeRuns(3, 3),
    "2 to 4" to EpisodeRuns(2, 4),
    "3 to 6" to EpisodeRuns(3, 6),
)
