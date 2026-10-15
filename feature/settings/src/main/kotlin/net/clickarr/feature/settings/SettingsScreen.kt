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
import net.clickarr.data.UpdateChecker
import net.clickarr.ui.design.ClickarrColors
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
    /** Where Down from the shell's cog lands: the first section, or the lock pad while Settings is locked. */
    entryFocus: FocusRequester = remember { FocusRequester() },
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    var section by rememberSaveable {
        mutableStateOf(SettingsSection.entries.firstOrNull { it.name.equals(initialSection, true) } ?: SettingsSection.GENERAL)
    }
    // The settings lock (General, "Settings lock"): one code per visit; leaving Settings locks it again.
    val locked by viewModel.lock.locked.collectAsState()
    DisposableEffect(Unit) { onDispose { viewModel.lock.relock() } }
    val park = remember { FocusPark() }
    val navFirst = entryFocus
    // When the gate goes away on a correct code, focus is parked; hand it to the section list.
    var wasLocked by remember { mutableStateOf(locked) }
    LaunchedEffect(locked) {
        if (wasLocked && !locked) runCatching { navFirst.requestFocus() }
        wasLocked = locked
    }
    Box(Modifier.fillMaxSize()) {
        FocusParkAnchor(park)
        if (locked) {
            LockGate(viewModel.lock, park, firstKey = entryFocus)
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
    val servers by viewModel.servers.collectAsState()
    val connected = servers.firstOrNull()?.kind
    // A deep link or a remembered section may name the server this TV does not use; show General instead.
    val shown = if (section.enabledFor(connected)) section else SettingsSection.GENERAL
    Row(Modifier.fillMaxSize()) {
        // Lazy so the focused entry scrolls into view; eight rows do not fit above the fold at 1080p.
        LazyColumn(
            Modifier.width(320.dp).fillMaxHeight().background(ClickarrColors.BgPanel).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(SettingsSection.entries) { s ->
                ListItem(
                    selected = shown == s,
                    enabled = s.enabledFor(connected),
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
            Text(shown.label, style = ClickarrTextStyles.ScreenTitle)
            when (shown) {
                SettingsSection.GENERAL -> GeneralPane(viewModel, park)
                SettingsSection.PLEX -> PlexPane(viewModel, actions.onDisconnected)
                SettingsSection.JELLYFIN -> JellyfinPane(viewModel, actions.onDisconnected)
                SettingsSection.CHANNELS -> ChannelsPane(viewModel, actions, park)
                SettingsSection.PLAYBACK -> PlaybackPane()
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
    Caption("Clickarr is an independent project, not affiliated with Plex or Jellyfin.")
    UpdatesBlock(appVersionCode, vm)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
