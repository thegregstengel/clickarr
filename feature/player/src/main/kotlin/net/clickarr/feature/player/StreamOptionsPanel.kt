package net.clickarr.feature.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens
import net.clickarr.ui.design.ClickarrTextStyles

/** Full-height Audio & video list over the player. Back dismisses only this panel. */
@Composable
fun StreamOptionsPanel(streams: StreamOptionsController, onDismiss: () -> Unit) {
    val options by streams.options.collectAsState()
    val selectedMedia by streams.selectedMedia.collectAsState()
    val selectedAudio by streams.selectedAudio.collectAsState()
    val firstFocus = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(options) { runCatching { firstFocus.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(ClickarrColors.BgBase.copy(alpha = SCRIM_ALPHA)),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            Modifier
                .width(PANEL_WIDTH)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topStart = ClickarrDimens.RadiusCard, bottomStart = ClickarrDimens.RadiusCard))
                .background(ClickarrColors.BgPanel)
                .padding(ClickarrDimens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Audio & video", style = ClickarrTextStyles.ScreenTitle)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    Text("Video", style = ClickarrTextStyles.LabelAllCaps, color = ClickarrColors.TextMuted)
                }
                itemsIndexed(options.videos) { i, option ->
                    val selected = (option.id == AUTO_VIDEO_ID && selectedMedia == null) || option.id == selectedMedia
                    StreamRow(
                        option.label,
                        selected = selected,
                        onClick = { streams.pickVideo(if (option.id == AUTO_VIDEO_ID) null else option.id) },
                        modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
                if (options.audios.isNotEmpty()) {
                    item {
                        Text(
                            "Audio",
                            style = ClickarrTextStyles.LabelAllCaps,
                            color = ClickarrColors.TextMuted,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    itemsIndexed(options.audios) { _, option ->
                        StreamRow(
                            option.label,
                            selected = option.id == selectedAudio,
                            onClick = { streams.pickAudio(option.id, option.language) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        selected = selected,
        onClick = onClick,
        headlineContent = { Text(label, style = ClickarrTextStyles.RowTitle) },
        trailingContent = { if (selected) Text("✓", style = ClickarrTextStyles.RowTitle) },
        modifier = modifier,
    )
}

private val PANEL_WIDTH = 560.dp
private const val SCRIM_ALPHA = 0.85f
