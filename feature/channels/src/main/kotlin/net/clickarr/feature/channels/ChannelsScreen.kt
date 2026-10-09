package net.clickarr.feature.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import net.clickarr.core.model.Channel
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens
import net.clickarr.ui.design.ClickarrTextStyles

/** Channels tab: list on the left, a static preview card for the focused channel on the right. */
@Composable
fun ChannelsScreen(
    onCreate: () -> Unit,
    onTune: (Channel) -> Unit,
    viewModel: ChannelsViewModel = hiltViewModel(),
) {
    val channels by viewModel.channels.collectAsState()
    val preview by viewModel.preview.collectAsState()

    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.width(480.dp).fillMaxHeight().background(ClickarrColors.BgPanel).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Channels", style = ClickarrTextStyles.ScreenTitle, modifier = Modifier.padding(bottom = 8.dp))
            Button(onClick = onCreate) { Text("Create channel") }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(channels, key = { it.id.value }) { ch ->
                    ListItem(
                        selected = preview?.channel?.id == ch.id,
                        onClick = { onTune(ch) },
                        headlineContent = { Text(ch.name, style = ClickarrTextStyles.RowTitle) },
                        leadingContent = { Text(ch.number.toString(), style = ClickarrTextStyles.RowTitle) },
                        modifier = Modifier.onFocusChanged { if (it.isFocused) viewModel.focus(ch) },
                    )
                }
            }
            if (channels.isEmpty()) {
                Text(
                    "No channels yet. Create one from a show, a collection, or a playlist.",
                    style = ClickarrTextStyles.Secondary,
                    color = ClickarrColors.TextSecondary,
                )
            }
        }
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.BottomStart) {
            preview?.let { PreviewCard(it, onDelete = { viewModel.delete(it.channel.id) }) }
        }
    }
}

@Composable
private fun PreviewCard(p: ChannelsViewModel.Preview, onDelete: () -> Unit) {
    Column(
        Modifier
            .width(560.dp)
            .background(ClickarrColors.BgSurface.copy(alpha = 0.9f), RoundedCornerShape(ClickarrDimens.RadiusCard))
            .padding(ClickarrDimens.CardPadding),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "${p.channel.number}  ${p.channel.name.uppercase()}",
            style = ClickarrTextStyles.LabelAllCaps,
            color = ClickarrColors.TextSecondary,
        )
        Text(p.now?.entry?.title ?: "Nothing scheduled", style = ClickarrTextStyles.ProgramTitle, maxLines = 2)
        p.now?.entry?.subtitle?.let { Text(it, style = ClickarrTextStyles.Secondary, color = ClickarrColors.TextSecondary) }
        p.next?.let { Text("Up next: ${it.entry.title}", style = ClickarrTextStyles.Caption, color = ClickarrColors.TextMuted) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = onDelete) { Text("Delete channel") }
        }
    }
}
