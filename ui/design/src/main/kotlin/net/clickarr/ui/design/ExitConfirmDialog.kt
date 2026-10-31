package net.clickarr.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.Text

/**
 * Exit confirmation (design language Dialog): safe choice focused first, Back dismisses.
 * Host this once at the app shell so Player, Shell, and About share one copy and focus policy.
 */
@Composable
fun ExitConfirmDialog(onKeepWatching: () -> Unit, onExit: () -> Unit) {
    val keepFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { keepFocus.requestFocus() } }
    Dialog(onDismissRequest = onKeepWatching, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(ClickarrColors.BgBase.copy(alpha = SCRIM_ALPHA)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .width(DIALOG_WIDTH)
                    .clip(RoundedCornerShape(ClickarrDimens.RadiusCard))
                    .background(ClickarrColors.BgSurface)
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Leave Clickarr?", style = ClickarrTextStyles.ScreenTitle)
                Text(
                    "Playback will stop on this TV.",
                    style = ClickarrTextStyles.Body,
                    color = ClickarrColors.TextSecondary,
                )
                Row(
                    Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(onClick = onKeepWatching, modifier = Modifier.focusRequester(keepFocus)) {
                        Text("Keep watching")
                    }
                    Button(onClick = onExit) { Text("Exit") }
                }
            }
        }
    }
}

private val DIALOG_WIDTH = 640.dp
private const val SCRIM_ALPHA = 0.85f
