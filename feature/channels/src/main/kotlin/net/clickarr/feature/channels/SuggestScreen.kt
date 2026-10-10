package net.clickarr.feature.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import net.clickarr.core.model.ChannelIcon
import net.clickarr.feature.channels.SuggestViewModel.Step
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens
import net.clickarr.ui.design.ClickarrTextStyles
import net.clickarr.ui.design.GlyphIcon

/** Suggested channels from the library; tick the ones to keep, then create them all at once. */
@Composable
fun SuggestScreen(onDone: () -> Unit, viewModel: SuggestViewModel = hiltViewModel()) {
    val step by viewModel.step.collectAsState()
    Column(
        Modifier.fillMaxSize().background(ClickarrColors.BgBase).padding(ClickarrDimens.SafeArea),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Suggested channels", style = ClickarrTextStyles.ScreenTitle)
        when (val s = step) {
            Step.Loading -> Text("Reading your library…", style = ClickarrTextStyles.Secondary, color = ClickarrColors.TextSecondary)
            is Step.Pick -> Pick(s, viewModel)
            is Step.Creating -> Text("Building channel ${s.done + 1} of ${s.total}…", style = ClickarrTextStyles.Secondary)
            is Step.Done -> {
                Text("Created ${s.created} channel${if (s.created == 1) "" else "s"}.", style = ClickarrTextStyles.Secondary)
                s.failed.forEach { Text(it, style = ClickarrTextStyles.Caption, color = ClickarrColors.StatusError) }
                Button(onClick = onDone) { Text("Done") }
            }
            is Step.Failed -> {
                Text(s.message, style = ClickarrTextStyles.Secondary, color = ClickarrColors.StatusError)
                Button(onClick = onDone) { Text("Back") }
            }
        }
    }
}

@Composable
private fun Pick(s: Step.Pick, vm: SuggestViewModel) {
    Text(
        "Picked from your collections, the genres and decades you have plenty of, and your playlists. " +
            "Unselect anything you do not want; nothing is created until you say so.",
        style = ClickarrTextStyles.Secondary,
        color = ClickarrColors.TextSecondary,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = vm::createPicked) { Text("Create selected (${s.picked.size})") }
    }
    if (s.suggestions.isEmpty()) {
        Text(
            "Nothing to suggest yet: the library has no collections, playlists, or genres with enough titles.",
            style = ClickarrTextStyles.Secondary,
        )
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(s.suggestions) { i, sug ->
            val on = i in s.picked
            ListItem(
                selected = on,
                onClick = { vm.toggle(i) },
                headlineContent = { Text(sug.name, style = ClickarrTextStyles.RowTitle) },
                supportingContent = { Text(sug.reason, style = ClickarrTextStyles.Caption, color = ClickarrColors.TextSecondary) },
                leadingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        (sug.icon as? ChannelIcon.Glyph)?.let { GlyphIcon(it.name, 28.dp) }
                    }
                },
                trailingContent = { Text(if (on) "✓" else "", style = ClickarrTextStyles.RowTitle) },
            )
        }
    }
}
