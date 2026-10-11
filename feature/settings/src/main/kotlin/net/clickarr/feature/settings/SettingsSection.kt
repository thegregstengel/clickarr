package net.clickarr.feature.settings

/** The Settings nav. General holds device and appearance; Sync holds the household (and Drive, per ADR 0020). */
enum class SettingsSection(val label: String) {
    GENERAL("General"), SERVER("Plex Server"), CHANNELS("Channels"), PLAYBACK("Playback"),
    SYNC("Sync"), DIAGNOSTICS("Diagnostics"), ABOUT("About"),
}
