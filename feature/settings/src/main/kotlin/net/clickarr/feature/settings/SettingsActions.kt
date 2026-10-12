package net.clickarr.feature.settings

import net.clickarr.core.model.ChannelId

/** What Settings asks the app to do. */
class SettingsActions(
    val onDisconnected: () -> Unit,
    val onExit: () -> Unit,
    val onCreateChannel: () -> Unit,
    val onSuggestChannels: () -> Unit,
    val onHolidayChannels: () -> Unit,
    val onEditChannel: (ChannelId) -> Unit,
)

