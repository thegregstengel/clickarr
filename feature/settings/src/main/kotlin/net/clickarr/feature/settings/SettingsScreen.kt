package net.clickarr.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens
import net.clickarr.ui.design.ClickarrTextStyles

/** Settings: left nav, right pane (design language 4, "Settings"). */
@Composable
fun SettingsScreen(
    appVersion: String,
    onDisconnected: () -> Unit,
    onOpenSpikes: () -> Unit,
    onExit: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    var section by rememberSaveable { mutableStateOf(SettingsSection.GENERAL) }
    Row(Modifier.fillMaxSize()) {
        // Lazy so the focused entry scrolls into view; eight rows do not fit above the fold at 1080p.
        LazyColumn(
            Modifier.width(320.dp).fillMaxHeight().background(ClickarrColors.BgPanel).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(SettingsSection.entries) { s ->
                ListItem(
                    selected = section == s,
                    onClick = { section = s },
                    headlineContent = { Text(s.label, style = ClickarrTextStyles.RowTitle) },
                )
            }
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(section.label, style = ClickarrTextStyles.ScreenTitle)
            when (section) {
                SettingsSection.GENERAL -> GeneralPane(viewModel)
                SettingsSection.SERVER -> ServerPane(viewModel, onDisconnected)
                SettingsSection.CHANNELS -> ChannelsPane(viewModel)
                SettingsSection.APPEARANCE -> AppearancePane(viewModel)
                SettingsSection.PLAYBACK -> PlaybackPane(viewModel)
                SettingsSection.HOUSEHOLD -> HouseholdPane()
                SettingsSection.DIAGNOSTICS -> DiagnosticsPane(viewModel)
                SettingsSection.ABOUT -> AboutPane(appVersion, viewModel, onOpenSpikes, onExit)
            }
        }
    }
}

@Composable
private fun Caption(text: String) = Text(text, style = ClickarrTextStyles.Secondary, color = ClickarrColors.TextSecondary)

@Composable
private fun Label(text: String) = Text(text, style = ClickarrTextStyles.LabelAllCaps, color = ClickarrColors.TextMuted)

@Composable
private fun GeneralPane(vm: SettingsViewModel) {
    val g by vm.general.collectAsState()
    Label("This TV")
    Text(g.deviceName.ifBlank { "Clickarr TV" }, style = ClickarrTextStyles.RowTitle)
    Caption("Device id ${g.deviceId.take(8)}. The name is what other TVs in a household will see.")
}

@Composable
private fun ServerPane(vm: SettingsViewModel, onDisconnected: () -> Unit) {
    val servers by vm.servers.collectAsState()
    if (servers.isEmpty()) {
        Caption("No server connected.")
    }
    servers.forEach { s ->
        Label("Plex")
        Text(s.name, style = ClickarrTextStyles.RowTitle)
        Caption("${s.baseUrl}${s.version?.let { "  ·  v$it" } ?: ""}")
    }
    if (servers.isNotEmpty()) {
        Button(onClick = { vm.disconnect(onDisconnected) }) { Text("Disconnect and sign in again") }
        Caption("Channels stay; they resume once the same server is connected again.")
    }
}

@Composable
private fun ChannelsPane(vm: SettingsViewModel) {
    val list by vm.channelList.collectAsState()
    val message by vm.message.collectAsState()
    Caption("${list.size} channel${if (list.size == 1) "" else "s"}.")
    Button(onClick = vm::refreshAllLineups) { Text("Refresh all lineups from Plex") }
    message?.let { Caption(it) }
}

@Composable
private fun AppearancePane(vm: SettingsViewModel) {
    val g by vm.general.collectAsState()
    Label("Overlay stays for")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(3, 5, 8).forEach { s ->
            ListItem(
                selected = g.overlayTimeoutMs == s * 1000,
                onClick = { vm.setOverlayTimeout(s * 1000) },
                headlineContent = { Text("$s seconds", style = ClickarrTextStyles.Caption) },
            )
        }
    }
}

@Composable
private fun PlaybackPane(vm: SettingsViewModel) {
    val p = vm.profile
    Label("Direct play profile")
    Caption("Up to ${p.maxWidth}×${p.maxHeight}, video ${p.videoCodecs.joinToString()}, audio ${p.audioCodecs.joinToString()}.")
    Caption("Anything outside this is transcoded by Plex. A per-device probe replaces this fixed profile in Phase 3.")
}

@Composable
private fun DiagnosticsPane(vm: SettingsViewModel) {
    LaunchedEffect(Unit) { vm.loadDiagnostics() }
    val d by vm.diagnostics.collectAsState()
    Caption("Everything a bug report needs. Tokens are never included.")
    Button(onClick = vm::loadDiagnostics) { Text("Refresh") }
    // Plain column: the pane already scrolls vertically, and a lazy list cannot live inside that.
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 18.sp, color = ClickarrColors.TextPrimary)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        d.lines.forEach { Text(it, style = mono) }
        Label("Recent log")
        d.log.forEach { Text(it, style = mono.copy(color = ClickarrColors.TextSecondary)) }
    }
}

@Composable
private fun AboutPane(appVersion: String, vm: SettingsViewModel, onOpenSpikes: () -> Unit, onExit: () -> Unit) {
    val updateStatus by vm.updateStatus.collectAsState()
    Text("Clickarr $appVersion", style = ClickarrTextStyles.RowTitle)
    Caption("Turn your media library into TV. Open source, MIT licensed. clickarr.net")
    Caption("Plex is a trademark of Plex, Inc. Clickarr is an independent project.")
    Button(onClick = { vm.checkForUpdates(appVersion) }) { Text("Check for updates") }
    updateStatus?.let { Caption(it) }
    Button(onClick = onOpenSpikes) { Text("Phase 0 spikes") }
    Button(onClick = onExit) { Text("Exit Clickarr") }
}
