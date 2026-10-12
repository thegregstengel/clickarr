package net.clickarr.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import net.clickarr.data.DevicePrefs
import net.clickarr.data.DriveSync
import net.clickarr.data.HouseholdService
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrTextStyles

/** Off, a local household over the LAN, or Google Drive: one at a time (ADR 0020). */
@Composable
fun SyncPane(viewModel: SyncViewModel = hiltViewModel()) {
    val role by viewModel.role.collectAsState()
    val mode by viewModel.syncMode.collectAsState()
    val effective = when {
        role is HouseholdService.Role.Drive -> DevicePrefs.SYNC_DRIVE
        role !is HouseholdService.Role.None -> DevicePrefs.SYNC_LOCAL
        mode == DevicePrefs.SYNC_LOCAL -> DevicePrefs.SYNC_LOCAL
        else -> DevicePrefs.SYNC_OFF
    }
    Text("Keep TVs on the same channels", style = ClickarrTextStyles.LabelAllCaps, color = ClickarrColors.TextMuted)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModeChip("Off", effective == DevicePrefs.SYNC_OFF, viewModel::chooseOff)
        ModeChip("Local household", effective == DevicePrefs.SYNC_LOCAL, viewModel::chooseLocal)
        ModeChip("Google Drive", effective == DevicePrefs.SYNC_DRIVE, viewModel::chooseDrive)
    }
    when (effective) {
        DevicePrefs.SYNC_LOCAL -> HouseholdPane()
        DevicePrefs.SYNC_DRIVE -> DriveSection(viewModel)
        else -> Text(
            "A local household keeps the TVs in this home on one lineup over Wi-Fi. Google Drive backs up your channels " +
                "and keeps TVs in different homes on the same lineup, as long as they use the same media server.",
            style = ClickarrTextStyles.Secondary,
            color = ClickarrColors.TextSecondary,
        )
    }
}

@Composable
private fun DriveSection(vm: SyncViewModel) {
    if (!vm.driveConfigured) {
        Text(
            "Google Drive sync is not configured in this build. Builds from clickarr.net carry the Google client; see docs/release.md.",
            style = ClickarrTextStyles.Secondary,
            color = ClickarrColors.StatusWarn,
        )
        return
    }
    val status by vm.driveStatus.collectAsState()
    // Each state swaps its buttons for another set. Focus parks on a hidden anchor before a button-driven swap
    // and the new state's first button takes it, so the shell never sees focus fall onto the Guide tab.
    val park = remember { FocusPark() }
    val primary = remember { FocusRequester() }
    FocusParkAnchor(park)
    var shown by remember { mutableStateOf(status::class) }
    LaunchedEffect(status::class) {
        if (status::class != shown) {
            shown = status::class
            runCatching { primary.requestFocus() }
        }
    }
    when (val s = status) {
        DriveSync.Status.SignedOut -> {
            Text(
                "Sign in on a phone with a short code. Clickarr keeps one small file in a Clickarr folder in your Drive, and " +
                    "its permission covers only files it created; your media server sign-in never leaves this TV.",
                style = ClickarrTextStyles.Secondary,
                color = ClickarrColors.TextSecondary,
            )
            Button(onClick = { park.park(); vm.signIn() }, modifier = Modifier.focusRequester(primary)) {
                Text("Sign in with Google")
            }
            val problem by vm.driveProblem.collectAsState()
            problem?.let { Text(it, style = ClickarrTextStyles.Secondary, color = ClickarrColors.StatusWarn) }
        }
        is DriveSync.Status.Linking -> {
            Text("On a phone or computer, go to", style = ClickarrTextStyles.Secondary, color = ClickarrColors.TextSecondary)
            Text(s.url.removePrefix("https://"), style = ClickarrTextStyles.ScreenTitle)
            Text("and enter this code", style = ClickarrTextStyles.Secondary, color = ClickarrColors.TextSecondary)
            Text(
                s.userCode,
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 64.sp, letterSpacing = 4.sp),
                color = ClickarrColors.AccentGlow,
            )
            Text(
                "This TV signs in on its own once the phone finishes.",
                style = ClickarrTextStyles.Caption,
                color = ClickarrColors.TextMuted,
            )
            Button(onClick = { park.park(); vm.cancelSignIn() }, modifier = Modifier.focusRequester(primary)) { Text("Cancel") }
        }
        is DriveSync.Status.SignedIn -> {
            Text("Signed in as ${s.email ?: "your Google account"}", style = ClickarrTextStyles.RowTitle)
            val problem = s.message
            val line = when {
                s.syncing -> "Syncing…"
                problem != null -> problem
                s.lastSync != null -> "Synced. Every TV on this account and media server shows the same lineup."
                else -> "Not synced yet."
            }
            val color = if (problem != null) ClickarrColors.StatusError else ClickarrColors.TextSecondary
            Text(line, style = ClickarrTextStyles.Secondary, color = color)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = vm::syncNow, modifier = Modifier.focusRequester(primary)) { Text("Sync now") }
                Button(onClick = { park.park(); vm.signOut() }) { Text("Sign out") }
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        selected = selected,
        onClick = onClick,
        headlineContent = { Text(label, style = ClickarrTextStyles.Caption) },
        modifier = Modifier.widthIn(min = 140.dp, max = 300.dp),
    )
}
