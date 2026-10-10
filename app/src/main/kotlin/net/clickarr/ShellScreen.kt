package net.clickarr

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Text
import net.clickarr.ui.design.R as DesignR
import net.clickarr.feature.settings.SettingsActions
import androidx.tv.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import net.clickarr.core.common.Log
import net.clickarr.core.model.ChannelId
import net.clickarr.feature.guide.GuideScreen
import net.clickarr.feature.settings.SettingsScreen
import net.clickarr.spike.SpikeApp
import net.clickarr.spike.SpikeArgs
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens
import net.clickarr.ui.design.ClickarrLogoHorizontal
import net.clickarr.ui.design.ClickarrTextStyles
import net.clickarr.ui.design.clickarrFocusable

private enum class ShellTab(val label: String) { GUIDE("Guide"), FAVORITES("Favorites"), SETTINGS("Settings") }

/** What the shell asks the app to do. */
class ShellCallbacks(
    val onWatch: () -> Unit,
    val onCreateChannel: () -> Unit,
    val onEditChannel: (ChannelId) -> Unit,
    val onDisconnected: () -> Unit,
    val onExit: () -> Unit,
)

/**
 * The tabbed shell behind Back from the player (design language 4, "Shell"): Guide and Favorites in the
 * row, a settings cog at the far right. Channels are managed under Settings, Channels.
 */
@Composable
fun ShellScreen(
    initialTab: String,
    initialSection: String?,
    callbacks: ShellCallbacks,
    viewModel: AppViewModel = hiltViewModel(),
) {
    val channelCount by viewModel.channelCount.collectAsState()
    var tab by rememberSaveable { mutableStateOf(ShellTab.entries.firstOrNull { it.name.equals(initialTab, true) } ?: ShellTab.GUIDE) }
    var spikes by rememberSaveable { mutableStateOf(false) }
    BackHandler {
        when {
            spikes -> spikes = false
            channelCount > 0 -> callbacks.onWatch()
            else -> callbacks.onExit()
        }
    }
    if (spikes) {
        SpikeApp(SpikeArgs())
        return
    }
    // Tabs follow focus, but only focus the viewer moved there. When a focused control disappears (a pane swaps
    // its content, a text field closes), Compose hands focus to the first focusable, which is the Guide tab, and
    // that must not change tabs. A key press within the last moment is the signal that the viewer did it.
    val lastKeyAt = remember { longArrayOf(0L) }
    // The tab opened from the player gets focus inside it; after that, moving across the row keeps focus on the
    // row until Down is pressed, so Guide and Favorites do not pull focus into the grid as they go by.
    var switched by rememberSaveable { mutableStateOf(false) }
    fun select(t: ShellTab) {
        if (SystemClock.uptimeMillis() - lastKeyAt[0] <= TAB_FOCUS_WINDOW_MS) {
            if (t != tab) switched = true
            tab = t
        } else {
            Log.d(TAG) { "ignored stray focus on ${t.name}" }
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(ClickarrColors.BgBase)
            .onPreviewKeyEvent { lastKeyAt[0] = SystemClock.uptimeMillis(); false },
    ) {
        ShellTopRow(tab, onFocusTab = ::select, onSelectTab = { t -> switched = switched || t != tab; tab = t })
        Box(Modifier.fillMaxSize()) {
            when (tab) {
                ShellTab.GUIDE -> GuideScreen(onWatch = callbacks.onWatch, takeFocus = !switched)
                ShellTab.FAVORITES -> GuideScreen(onWatch = callbacks.onWatch, onlyFavorites = true, takeFocus = !switched)
                ShellTab.SETTINGS -> SettingsScreen(
                    appVersion = BuildConfig.VERSION_NAME,
                    appVersionCode = BuildConfig.VERSION_CODE,
                    initialSection = initialSection,
                    actions = SettingsActions(
                        onDisconnected = callbacks.onDisconnected,
                        onOpenSpikes = { spikes = true },
                        onExit = callbacks.onExit,
                        onCreateChannel = callbacks.onCreateChannel,
                        onEditChannel = callbacks.onEditChannel,
                    ),
                )
            }
        }
    }
}

/** Logo, Guide and Favorites, and the settings cog at the far right. */
@Composable
private fun ShellTopRow(tab: ShellTab, onFocusTab: (ShellTab) -> Unit, onSelectTab: (ShellTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ClickarrDimens.SafeArea, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClickarrLogoHorizontal(markSize = 40.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ShellTab.GUIDE, ShellTab.FAVORITES).forEach { t ->
                ShellTabPill(t, selected = tab == t, onFocus = { onFocusTab(t) }, onSelect = { onSelectTab(t) })
            }
        }
        Spacer(Modifier.weight(1f))
        ShellTabPill(
            ShellTab.SETTINGS,
            selected = tab == ShellTab.SETTINGS,
            onFocus = { onFocusTab(ShellTab.SETTINGS) },
            onSelect = { onSelectTab(ShellTab.SETTINGS) },
            modifier = Modifier.testTag("shell.settings"),
        )
    }
}

/** Tab per design language 3, "Tab": rest muted, selected deep accent, focused accent, both with a ring. */
@Composable
private fun ShellTabPill(t: ShellTab, selected: Boolean, onFocus: () -> Unit, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val color = if (selected) ClickarrColors.TextPrimary else ClickarrColors.TextSecondary
    Box(
        modifier
            .semantics(mergeDescendants = true) {}
            .clickarrFocusable(interaction, radius = ClickarrDimens.RadiusCell, idleColor = ClickarrColors.BgBase, selected = selected)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .onKeyEvent { e ->
                val select = e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter)
                if (select) onSelect()
                select
            }
            .focusable(interactionSource = interaction)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (t == ShellTab.SETTINGS) {
            Icon(painterResource(DesignR.drawable.ic_ui_settings), contentDescription = t.label, Modifier.size(30.dp), tint = color)
        } else {
            Text(t.label, style = ClickarrTextStyles.ScreenTitle, color = color)
        }
    }
}

private const val TAG = "Shell"
private const val TAB_FOCUS_WINDOW_MS = 1_500L
