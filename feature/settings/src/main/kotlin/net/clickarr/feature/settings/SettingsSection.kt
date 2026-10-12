package net.clickarr.feature.settings

import net.clickarr.core.model.ProviderKind

/**
 * The Settings nav. General holds device and appearance; Sync holds the household (and Drive, per ADR 0020). Plex and
 * Jellyfin each hold what is particular to that server; a TV uses one (ADR 0022), so the other is shown but disabled.
 */
enum class SettingsSection(val label: String, val server: ProviderKind? = null) {
    GENERAL("General"),
    PLEX("Plex", ProviderKind.PLEX),
    JELLYFIN("Jellyfin", ProviderKind.JELLYFIN),
    CHANNELS("Channels"),
    PLAYBACK("Playback"),
    SYNC("Sync"),
    DIAGNOSTICS("Diagnostics"),
    ABOUT("About"),
    ;

    /** Usable on a TV connected to [connected]; with no server yet, both server sections stay open. */
    fun enabledFor(connected: ProviderKind?): Boolean = server == null || connected == null || server == connected
}
