package net.clickarr.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import net.clickarr.data.UpdateChecker
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.GlyphIcon
import net.clickarr.core.model.ChannelIcon
import net.clickarr.core.model.ChannelId
import androidx.compose.ui.Alignment
import net.clickarr.ui.design.ClickarrPalettes
import net.clickarr.ui.design.ClickarrDimens
import net.clickarr.ui.design.ClickarrTextStyles

/** Settings: left nav, right pane (design language 4, "Settings"). */
@Composable
fun SettingsScreen(
    appVersion: String,
    appVersionCode: Int,
    initialSection: String?,
    actions: SettingsActions,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    var section by rememberSaveable {
        mutableStateOf(SettingsSection.entries.firstOrNull { it.name.equals(initialSection, true) } ?: SettingsSection.GENERAL)
    }
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
                SettingsSection.SERVER -> ServerPane(viewModel, actions.onDisconnected)
                SettingsSection.CHANNELS -> ChannelsPane(viewModel, actions)
                SettingsSection.PLAYBACK -> PlaybackPane(viewModel)
                SettingsSection.SYNC -> SyncPane()
                SettingsSection.DIAGNOSTICS -> DiagnosticsPane(viewModel)
                SettingsSection.ABOUT -> AboutPane(appVersion, appVersionCode, viewModel, actions)
            }
        }
    }
}

@Composable
private fun Caption(text: String) = Text(text, style = ClickarrTextStyles.Secondary, color = ClickarrColors.TextSecondary)

@Composable
private fun Label(text: String) = Text(text, style = ClickarrTextStyles.LabelAllCaps, color = ClickarrColors.TextMuted)

/** A choice in a row. ListItem fills the width by default, which would push its siblings off screen. */
@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        selected = selected,
        onClick = onClick,
        headlineContent = { Text(label, style = ClickarrTextStyles.Caption) },
        modifier = Modifier.widthIn(min = 140.dp, max = 260.dp),
    )
}

@Composable
private fun GeneralPane(vm: SettingsViewModel) {
    val g by vm.general.collectAsState()
    Label("This TV")
    Text(g.deviceName.ifBlank { "Clickarr TV" }, style = ClickarrTextStyles.RowTitle)
    Caption("Device id ${g.deviceId.take(8)}. The name is what other TVs in a household will see.")
    Label("Theme")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ClickarrPalettes.all.forEach { p -> Chip(p.label, selected = g.theme == p.name) { vm.setTheme(p.name) } }
    }
    Label("Size")
    Caption("How large everything is drawn. Small fits the most on screen.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Small" to 0.55f, "Medium" to 0.65f, "Large" to 0.75f).forEach { (label, scale) ->
            Chip(label, selected = g.uiScale == scale) { vm.setUiScale(scale) }
        }
    }
    Label("Overlay stays for")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(3, 5, 8).forEach { s ->
            Chip("$s seconds", selected = g.overlayTimeoutMs == s * 1000) { vm.setOverlayTimeout(s * 1000) }
        }
    }
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
private fun ChannelsPane(vm: SettingsViewModel, actions: SettingsActions) {
    val list by vm.channelList.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val message by vm.message.collectAsState()
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = actions.onCreateChannel) { Text("Create channel") }
        Button(onClick = actions.onSuggestChannels) { Text("Suggest channels") }
        Button(onClick = vm::refreshAllLineups) { Text("Refresh all lineups from Plex") }
    }
    message?.let { Caption(it) }
    val g by vm.general.collectAsState()
    Label("Guide shows the next")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(3, 6, 12, 24).forEach { h -> Chip("$h hours", selected = g.guideHours == h) { vm.setGuideHours(h) } }
    }
    if (list.isEmpty()) {
        Caption("No channels yet. Create one from a show, a whole library, a collection, or a playlist.")
    }
    // Select a channel to edit it. Stars are set from the guide's channel column.
    list.forEach { ch ->
        ListItem(
            selected = false,
            onClick = { actions.onEditChannel(ch.id) },
            headlineContent = { Text(ch.name, style = ClickarrTextStyles.RowTitle) },
            leadingContent = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(ch.number.toString(), style = ClickarrTextStyles.RowTitle, modifier = Modifier.width(56.dp))
                    (ch.icon as? ChannelIcon.Glyph)?.let { GlyphIcon(it.name, 32.dp) }
                }
            },
            trailingContent = {
                if (ch.id in favorites) Text("★", style = ClickarrTextStyles.RowTitle, color = ClickarrColors.AccentGlow)
            },
        )
    }
}


@Composable
private fun PlaybackPane(vm: SettingsViewModel) {
    val p = vm.profile
    Label("Direct play profile")
    Caption("Up to ${p.maxWidth}×${p.maxHeight}, video ${p.videoCodecs.joinToString()}, audio ${p.audioCodecs.joinToString()}.")
    Caption("Probed from this TV's decoders and display at startup. Anything outside it is transcoded by Plex.")
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
private fun AboutPane(appVersion: String, appVersionCode: Int, vm: SettingsViewModel, actions: SettingsActions) {
    Text("Clickarr $appVersion (build $appVersionCode)", style = ClickarrTextStyles.RowTitle)
    Caption("Turn your media library into TV. Open source, MIT licensed. clickarr.net")
    Caption("Plex is a trademark of Plex, Inc. Clickarr is an independent project.")
    UpdatesBlock(appVersionCode, vm)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = actions.onOpenSpikes) { Text("Phase 0 spikes") }
        Button(onClick = actions.onExit) { Text("Exit Clickarr") }
    }
}

/** Channel choice, check, download with progress, and the hand-off to the system installer. */
@Composable
private fun UpdatesBlock(appVersionCode: Int, vm: SettingsViewModel) {
    val channel by vm.updateChannel.collectAsState()
    val state by vm.updateState.collectAsState()
    Label("Updates")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Nightly", selected = channel == UpdateChecker.CHANNEL_NIGHTLY) { vm.setUpdateChannel(UpdateChecker.CHANNEL_NIGHTLY) }
        Chip("Release", selected = channel == UpdateChecker.CHANNEL_RELEASE) { vm.setUpdateChannel(UpdateChecker.CHANNEL_RELEASE) }
    }
    Caption("Nightly and release builds are signed with different keys, so switching between them means uninstalling Clickarr once.")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { vm.checkForUpdates(appVersionCode) }) { Text("Check for updates") }
        when (val s = state) {
            is SettingsViewModel.UpdateState.Available -> Button(onClick = vm::downloadUpdate) { Text("Download ${s.latest.versionName}") }
            is SettingsViewModel.UpdateState.Ready -> Button(onClick = vm::installUpdate) { Text("Install ${s.latest.versionName}") }
            else -> Unit
        }
    }
    val line = when (val s = state) {
        SettingsViewModel.UpdateState.Idle -> null
        SettingsViewModel.UpdateState.Checking -> "Checking…"
        is SettingsViewModel.UpdateState.UpToDate -> "You have the latest ${channel} build (${s.versionName})."
        is SettingsViewModel.UpdateState.Available -> "${s.latest.versionName} (build ${s.latest.versionCode}) is available."
        is SettingsViewModel.UpdateState.Downloading -> "Downloading ${s.latest.versionName}: ${(s.progress * 100).toInt()}%"
        is SettingsViewModel.UpdateState.Ready -> "Downloaded and verified. Install opens the system installer; Clickarr restarts after."
        is SettingsViewModel.UpdateState.Failed -> s.message
    }
    line?.let { Caption(it) }
}
