package net.clickarr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import net.clickarr.feature.channels.ChannelsScreen
import net.clickarr.feature.guide.GuideScreen
import net.clickarr.spike.SpikeApp
import net.clickarr.spike.SpikeArgs
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens
import net.clickarr.ui.design.ClickarrTextStyles

private enum class ShellTab(val label: String) { GUIDE("Guide"), CHANNELS("Channels"), SETTINGS("Settings") }

/**
 * The tabbed shell behind Back from the player (design language 4, "Shell"). Guide lands in the next
 * increment; Settings holds the Phase 0 spikes until they have verdicts.
 */
@Composable
fun ShellScreen(onWatch: () -> Unit, onCreateChannel: () -> Unit, onExit: () -> Unit, viewModel: AppViewModel = hiltViewModel()) {
    val channelCount by viewModel.channelCount.collectAsState()
    var tab by rememberSaveable { mutableStateOf(ShellTab.CHANNELS) }
    var spikes by rememberSaveable { mutableStateOf(false) }
    BackHandler {
        when {
            spikes -> spikes = false
            channelCount > 0 -> onWatch()
            else -> onExit()
        }
    }
    if (spikes) {
        SpikeApp(SpikeArgs())
        return
    }
    Column(Modifier.fillMaxSize().background(ClickarrColors.BgBase)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ClickarrDimens.SafeArea, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            Text("Clickarr", style = ClickarrTextStyles.ScreenTitle)
            TabRow(selectedTabIndex = tab.ordinal) {
                ShellTab.entries.forEach { t ->
                    Tab(selected = tab == t, onFocus = { tab = t }) {
                        Text(t.label, style = ClickarrTextStyles.RowTitle, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            when (tab) {
                ShellTab.GUIDE -> GuideScreen(onWatch = onWatch)
                ShellTab.CHANNELS -> ChannelsScreen(onCreate = onCreateChannel, onWatch = onWatch)
                ShellTab.SETTINGS -> Column(Modifier.padding(ClickarrDimens.SafeArea), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Settings", style = ClickarrTextStyles.ScreenTitle)
                    Button(onClick = { spikes = true }) { Text("Phase 0 spikes") }
                    Button(onClick = onExit) { Text("Exit Clickarr") }
                }
            }
        }
    }
}
