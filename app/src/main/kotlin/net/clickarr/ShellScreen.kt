package net.clickarr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens
import net.clickarr.ui.design.ClickarrTextStyles

/**
 * Placeholder for the tabbed shell (Guide, Channels, Favorites, Settings). The real tabs land in the
 * next Phase 1 increments; this keeps the navigation graph complete meanwhile.
 */
@Composable
fun ShellScreen(onWatch: () -> Unit, onExit: () -> Unit, viewModel: AppViewModel = hiltViewModel()) {
    val channelCount by viewModel.channelCount.collectAsState()
    BackHandler { if (channelCount > 0) onWatch() else onExit() }
    Column(
        Modifier.fillMaxSize().background(ClickarrColors.BgBase).padding(ClickarrDimens.SafeArea),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Clickarr", style = ClickarrTextStyles.ProgramTitle)
        Text(
            if (channelCount == 0) "No channels yet. The channel editor arrives in the next build." else "$channelCount channels",
            style = ClickarrTextStyles.Secondary,
            color = ClickarrColors.TextSecondary,
        )
        if (channelCount > 0) Button(onClick = onWatch) { Text("Watch") }
        Button(onClick = onExit) { Text("Exit") }
    }
}
