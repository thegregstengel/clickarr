package net.clickarr.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import net.clickarr.core.common.AppTime
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.DisposableEffect
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
import net.clickarr.data.DevicePrefs
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
    // The settings lock (General, "Settings lock"): one code per visit; leaving Settings locks it again.
    val locked by viewModel.lock.locked.collectAsState()
    DisposableEffect(Unit) { onDispose { viewModel.lock.relock() } }
    val park = remember { FocusPark() }
    val navFirst = remember { FocusRequester() }
    // When the gate goes away on a correct code, focus is parked; hand it to the section list.
    var wasLocked by remember { mutableStateOf(locked) }
    LaunchedEffect(locked) {
        if (wasLocked && !locked) runCatching { navFirst.requestFocus() }
        wasLocked = locked
    }
    Box(Modifier.fillMaxSize()) {
        FocusParkAnchor(park)
        if (locked) {
            LockGate(viewModel.lock, park)
        } else {
            SettingsBody(section, { section = it }, navFirst, park, appVersion, appVersionCode, actions, viewModel)
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun SettingsBody(
    section: SettingsSection,
    onSection: (SettingsSection) -> Unit,
    navFirst: FocusRequester,
    park: FocusPark,
    appVersion: String,
    appVersionCode: Int,
    actions: SettingsActions,
    viewModel: SettingsViewModel,
) {
    Row(Modifier.fillMaxSize()) {
        // Lazy so the focused entry scrolls into view; eight rows do not fit above the fold at 1080p.
        LazyColumn(
            Modifier.width(320.dp).fillMaxHeight().background(ClickarrColors.BgPanel).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(SettingsSection.entries) { s ->
                ListItem(
                    selected = section == s,
                    onClick = { onSection(s) },
                    headlineContent = { Text(s.label, style = ClickarrTextStyles.RowTitle) },
                    modifier = if (s == SettingsSection.entries.first()) Modifier.focusRequester(navFirst) else Modifier,
                )
            }
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(section.label, style = ClickarrTextStyles.ScreenTitle)
            when (section) {
                SettingsSection.GENERAL -> GeneralPane(viewModel, park)
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

private fun refreshCaption(r: SettingsViewModel.RefreshInfo): String {
    val last = r.lastAt?.let { at ->
        val local = AppTime.local(at)
        val month = local.month.name.lowercase().replaceFirstChar(Char::uppercase).take(MONTH_ABBREVIATION)
        "$month ${local.dayOfMonth}, ${AppTime.timeOfDay(at)}"
    }
    return when {
        !r.automatic -> "New episodes join a channel only when you press Refresh."
        last == null -> "Each channel's source is re-read from Plex once a day; new episodes join at the next program boundary."
        else -> "Last automatic refresh $last: ${r.note ?: "no changes"}."
    }
}

private const val MONTH_ABBREVIATION = 3
private val ZONES = listOf(
    "Eastern" to "America/New_York", "Central" to "America/Chicago", "Mountain" to "America/Denver",
    "Arizona" to "America/Phoenix", "Pacific" to "America/Los_Angeles", "Alaska" to "America/Anchorage",
    "Hawaii" to "Pacific/Honolulu", "UTC" to "UTC", "London" to "Europe/London", "Berlin" to "Europe/Berlin",
    "Sydney" to "Australia/Sydney", "Tokyo" to "Asia/Tokyo",
)

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
private fun GeneralPane(vm: SettingsViewModel, park: FocusPark) {
    val g by vm.general.collectAsState()
    Label("This TV")
    Text(g.deviceName.ifBlank { "Clickarr TV" }, style = ClickarrTextStyles.RowTitle)
    Caption("Device id ${g.deviceId.take(8)}. The name is what other TVs in a household will see.")
    Label("Appearance")
    PickerRow("Theme", ClickarrPalettes.all.map { PickerOption(it.name, it.label) }, g.theme) { vm.setTheme(it) }
    PickerRow("Size", SIZES, g.uiScale, caption = "How large everything is drawn. Small fits the most on screen.") { vm.setUiScale(it) }
    PickerRow("Overlay stays for", OVERLAY_SECONDS.map { PickerOption(it * MS_PER_S, "$it seconds") }, g.overlayTimeoutMs) {
        vm.setOverlayTimeout(it)
    }
    Label("Time")
    Caption("Now ${AppTime.timeOfDay(vm.now())}. Automatic time checks a public time server so the guide lines up with the clock.")
    PickerRow("Clock", CLOCKS, g.autoTime) { vm.setAutoTime(it) }
    PickerRow("Time zone", zoneOptions(), g.timeZoneId) { vm.setTimeZone(it) }
    LockBlock(vm.lock, park)
}

private const val MS_PER_S = 1_000
private val OVERLAY_SECONDS = listOf(3, 5, 8)
private val SIZES = listOf(PickerOption(0.55f, "Small"), PickerOption(0.65f, "Medium"), PickerOption(0.75f, "Large"))
private val CLOCKS = listOf(
    PickerOption(true, "Automatic", "Checked against a public time server at start and every few hours"),
    PickerOption(false, "Device clock", "Trust the TV's own clock"),
)

private fun zoneOptions(): List<PickerOption<String?>> =
    listOf(PickerOption<String?>(null, "Device", AppTime.zone.id)) + ZONES.map { (label, id) -> PickerOption<String?>(id, label, id) }

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
    val refresh by vm.refreshInfo.collectAsState()
    Label("Keep lineups current")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Automatic, daily", selected = refresh.automatic) { vm.setAutoRefresh(true) }
        Chip("Only when I press Refresh", selected = !refresh.automatic) { vm.setAutoRefresh(false) }
    }
    Caption(refreshCaption(refresh))
    val g by vm.general.collectAsState()
    Label("Guide shows the next")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(3, 6, 12, 24).forEach { h -> Chip("$h hours", selected = g.guideHours == h) { vm.setGuideHours(h) } }
    }
    val favoritesTab by vm.favoritesTab.collectAsState()
    Label("Favorites tab")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Show", selected = favoritesTab) { vm.setFavoritesTab(true) }
        Chip("Hide", selected = !favoritesTab) { vm.setFavoritesTab(false) }
    }
    Caption("Hide takes Favorites out of the top row. Stars in the guide still work, for whenever you want it back.")
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
    val watch by vm.watchReporting.collectAsState()
    Label("Tell Plex what you watched")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Off", selected = watch == DevicePrefs.WATCH_OFF) { vm.setWatchReporting(DevicePrefs.WATCH_OFF) }
        Chip("Mark watched", selected = watch == DevicePrefs.WATCH_WATCHED) { vm.setWatchReporting(DevicePrefs.WATCH_WATCHED) }
        Chip("Progress too", selected = watch == DevicePrefs.WATCH_PROGRESS) { vm.setWatchReporting(DevicePrefs.WATCH_PROGRESS) }
    }
    Caption(
        when (watch) {
            DevicePrefs.WATCH_OFF -> "Plex never hears about what plays here."
            DevicePrefs.WATCH_PROGRESS ->
                "Episodes you sit through are marked watched, and the running position is sent too, so they appear in Continue Watching."
            else -> "An episode is marked watched once this TV has played most of it in one sitting. Tuning in for the last five minutes " +
                "does not count, and nothing lands in Continue Watching."
        },
    )
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

/**
 * Channel choice, then one button that is the check, the download, and the install in turn. One button
 * rather than one per step, so focus stays on it while the state moves underneath; a button that vanished
 * mid-download used to drop focus into the section list, which read as leaving the screen.
 */
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
    Button(onClick = {
        when (state) {
            is SettingsViewModel.UpdateState.Available -> vm.downloadUpdate()
            is SettingsViewModel.UpdateState.Ready, is SettingsViewModel.UpdateState.NeedsPermission -> vm.installUpdate()
            SettingsViewModel.UpdateState.Checking,
            is SettingsViewModel.UpdateState.Downloading,
            is SettingsViewModel.UpdateState.Installing,
            -> Unit
            else -> vm.checkForUpdates(appVersionCode)
        }
    }) { Text(updateButtonLabel(state)) }
    (state as? SettingsViewModel.UpdateState.Downloading)?.let { ProgressBar(it.progress) }
    updateStatusLine(state, channel)?.let { Caption(it) }
}

private fun updateButtonLabel(s: SettingsViewModel.UpdateState): String = when (s) {
    SettingsViewModel.UpdateState.Checking -> "Checking…"
    is SettingsViewModel.UpdateState.Available -> "Download ${s.latest.versionName}"
    is SettingsViewModel.UpdateState.Downloading -> "Downloading… ${(s.progress * PERCENT).toInt()}%"
    is SettingsViewModel.UpdateState.Ready -> "Install ${s.latest.versionName}"
    is SettingsViewModel.UpdateState.NeedsPermission -> "Install ${s.latest.versionName}"
    is SettingsViewModel.UpdateState.Installing -> "Installing…"
    else -> "Check for updates"
}

private fun updateStatusLine(s: SettingsViewModel.UpdateState, channel: String): String? = when (s) {
    SettingsViewModel.UpdateState.Idle, SettingsViewModel.UpdateState.Checking, is SettingsViewModel.UpdateState.Downloading -> null
    is SettingsViewModel.UpdateState.UpToDate -> "You have the latest $channel build (${s.versionName})."
    is SettingsViewModel.UpdateState.Available ->
        "${s.latest.versionName} (build ${s.latest.versionCode}) is available. The download is checked against its checksum."
    is SettingsViewModel.UpdateState.Ready ->
        s.problem ?: "Downloaded and verified. Install asks the TV to confirm, then Clickarr restarts on the new build."
    is SettingsViewModel.UpdateState.NeedsPermission ->
        "Allow Clickarr to install apps on the TV's settings page that just opened, then press Install again. " +
            "Fire TV: My Fire TV, Developer options, Install unknown apps."
    is SettingsViewModel.UpdateState.Installing -> s.note
    is SettingsViewModel.UpdateState.Failed -> s.message
}

private const val PERCENT = 100

@Composable
private fun ProgressBar(progress: Float) {
    Box(
        Modifier.fillMaxWidth(PROGRESS_WIDTH).height(6.dp).clip(RoundedCornerShape(3.dp)).background(ClickarrColors.BgCell),
    ) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(ClickarrColors.AccentPrimary))
    }
}

private const val PROGRESS_WIDTH = 0.6f
