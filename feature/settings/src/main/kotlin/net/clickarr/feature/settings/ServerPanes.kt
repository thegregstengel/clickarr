package net.clickarr.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import net.clickarr.core.model.ProviderKind
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrTextStyles

/** Settings, Plex: the connected Plex server and what Plex hears about what plays here. */
@Composable
internal fun PlexPane(vm: SettingsViewModel, onDisconnected: () -> Unit) {
    ServerBlock(vm, ProviderKind.PLEX, onDisconnected)
    PaneNote("Signed in through plex.tv or with a token. The sign-in stays on this TV and is never synced.")
    PaneNote("Plex is a trademark of Plex, Inc.")
}

/** Settings, Jellyfin: the connected Jellyfin server and what Jellyfin hears about what plays here. */
@Composable
internal fun JellyfinPane(vm: SettingsViewModel, onDisconnected: () -> Unit) {
    ServerBlock(vm, ProviderKind.JELLYFIN, onDisconnected)
    PaneNote("Signed in with Quick Connect or a password. Only the server's sign-in token is kept, on this TV, never synced.")
    PaneNote(
        "Theme music under \"Coming up next\" comes from Jellyfin's Theme Songs plugin; without it, the promo plays silently.",
    )
}

/** The server of [kind] this TV is signed in to: name, address, version, disconnect, and watch reporting. */
@Composable
private fun ServerBlock(vm: SettingsViewModel, kind: ProviderKind, onDisconnected: () -> Unit) {
    val servers by vm.servers.collectAsState()
    val mine = servers.filter { it.kind == kind }
    if (mine.isEmpty()) {
        PaneNote("This TV is not signed in to ${kind.label}.")
        return
    }
    mine.forEach { s ->
        Text(s.name, style = ClickarrTextStyles.RowTitle)
        PaneNote("${s.baseUrl}${s.version?.let { "  ·  v$it" } ?: ""}")
    }
    Button(onClick = { vm.disconnect(onDisconnected) }) { Text("Disconnect and sign in again") }
    PaneNote("Channels stay; they resume once the same server is connected again.")
    WatchReportingBlock(kind.label)
}

@Composable
private fun PaneNote(text: String) = Text(text, style = ClickarrTextStyles.Secondary, color = ClickarrColors.TextSecondary)
