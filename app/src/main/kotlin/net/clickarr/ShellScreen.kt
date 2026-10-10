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
import net.clickarr.core.common.Log
import net.clickarr.feature.channels.ChannelsScreen
import net.clickarr.feature.guide.GuideScreen
import net.clickarr.feature.settings.SettingsScreen
import net.clickarr.spike.SpikeApp
import net.clickarr.spike.SpikeArgs
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens
import net.clickarr.ui.design.ClickarrLogoHorizontal
import net.clickarr.ui.design.ClickarrTextStyles
import net.clickarr.ui.design.clickarrFocusable

private enum class ShellTab(val label: String) { GUIDE("Guide"), CHANNELS("Channels"), FAVORITES("Favorites"), SETTINGS("Settings") }

/**
 * The tabbed shell behind Back from the player (design language 4, "Shell"). Guide lands in the next
 * increment; Settings holds the Phase 0 spikes until they have verdicts.
 */
@Composable
fun ShellScreen(
    initialTab: String,
    onWatch: () -> Unit,
    onCreateChannel: () -> Unit,
    onDisconnected: () -> Unit,
    onExit: () -> Unit,
    viewModel: AppViewModel = hiltViewModel(),
) {
    val channelCount by viewModel.channelCount.collectAsState()
    var tab by rememberSaveable { mutableStateOf(ShellTab.entries.firstOrNull { it.name.equals(initialTab, true) } ?: ShellTab.CHANNELS) }
    var spikes by rememberSaveable { mutableStateOf(false) }
    BackHandler {
        when {
            spikes -> spikes = false
            channelCount > 0 -> onWatch()
            else -> onExit()
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
    fun select(t: ShellTab) {
        if (SystemClock.uptimeMillis() - lastKeyAt[0] <= TAB_FOCUS_WINDOW_MS) tab = t else Log.d(TAG) { "ignored stray focus on ${t.name}" }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(ClickarrColors.BgBase)
            .onPreviewKeyEvent { lastKeyAt[0] = SystemClock.uptimeMillis(); false },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ClickarrDimens.SafeArea, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            ClickarrLogoHorizontal(markSize = 40.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShellTab.entries.forEach { t -> ShellTabPill(t, selected = tab == t, onFocus = { select(t) }, onSelect = { tab = t }) }
            }
        }
        Box(Modifier.fillMaxSize()) {
            when (tab) {
                ShellTab.GUIDE -> GuideScreen(onWatch = onWatch)
                ShellTab.CHANNELS -> ChannelsScreen(onCreate = onCreateChannel, onWatch = onWatch)
                ShellTab.FAVORITES -> GuideScreen(onWatch = onWatch, onlyFavorites = true)
                ShellTab.SETTINGS -> SettingsScreen(
                    appVersion = BuildConfig.VERSION_NAME,
                    onDisconnected = onDisconnected,
                    onOpenSpikes = { spikes = true },
                    onExit = onExit,
                )
            }
        }
    }
}

/** Tab per design language 3, "Tab": rest muted, selected deep accent, focused accent, both with a ring. */
@Composable
private fun ShellTabPill(t: ShellTab, selected: Boolean, onFocus: () -> Unit, onSelect: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Text(
        t.label,
        style = ClickarrTextStyles.ScreenTitle,
        color = if (selected) ClickarrColors.TextPrimary else ClickarrColors.TextSecondary,
        modifier = Modifier
            .clickarrFocusable(interaction, radius = ClickarrDimens.RadiusCell, idleColor = ClickarrColors.BgBase, selected = selected)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .onKeyEvent { e ->
                val select = e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter)
                if (select) onSelect()
                select
            }
            .focusable(interactionSource = interaction)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

private const val TAG = "Shell"
private const val TAB_FOCUS_WINDOW_MS = 1_500L
