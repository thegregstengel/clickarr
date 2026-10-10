package net.clickarr.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
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
                "and keeps TVs in different homes on the same lineup, as long as they use the same Plex server.",
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
    when (val s = status) {
        DriveSync.Status.SignedOut -> {
            Text(
                "Sign in on a phone with a short code. Clickarr keeps one small file in a folder only it can see; your Plex " +
                    "sign-in never leaves this TV.",
                style = ClickarrTextStyles.Secondary,
                color = ClickarrColors.TextSecondary,
            )
            Button(onClick = vm::signIn) { Text("Sign in with Google") }
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
        }
        is DriveSync.Status.SignedIn -> {
            Text("Signed in as ${s.email ?: "your Google account"}", style = ClickarrTextStyles.RowTitle)
            val line = when {
                s.syncing -> "Syncing…"
                s.message != null -> s.message
                s.lastSync != null -> "Synced. Every TV on this account and Plex server shows the same lineup."
                else -> "Not synced yet."
            }
            val color = if (s.message != null) ClickarrColors.StatusError else ClickarrColors.TextSecondary
            Text(line, style = ClickarrTextStyles.Secondary, color = color)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = vm::syncNow) { Text("Sync now") }
                Button(onClick = vm::signOut) { Text("Sign out") }
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
