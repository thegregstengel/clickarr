package net.clickarr.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens
import net.clickarr.ui.design.ClickarrTextStyles

/** One choice in a [PickerRow]: the stored value, what the row shows for it, and an optional second line. */
data class PickerOption<T>(val value: T, val label: String, val detail: String? = null)

/**
 * A settings row that shows its current value and opens a modal list to change it (Settings, General).
 * OK on an option picks it and closes; Back closes without a change. Focus comes back to the row either way,
 * so a long list like time zones does not have to live on the pane.
 */
@Composable
internal fun <T> PickerRow(
    label: String,
    options: List<PickerOption<T>>,
    selected: T,
    caption: String? = null,
    onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val rowFocus = remember { FocusRequester() }
    val current = options.firstOrNull { it.value == selected }?.label ?: "Not set"
    ListItem(
        selected = false,
        onClick = { open = true },
        headlineContent = { Text(label, style = ClickarrTextStyles.RowTitle) },
        supportingContent = caption?.let { { Text(it, style = ClickarrTextStyles.Caption, color = ClickarrColors.TextSecondary) } },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(current, style = ClickarrTextStyles.RowTitle, color = ClickarrColors.AccentGlow)
                Text("›", style = ClickarrTextStyles.RowTitle, color = ClickarrColors.TextMuted)
            }
        },
        modifier = Modifier.width(ROW_WIDTH).focusRequester(rowFocus),
    )
    if (open) {
        PickerDialog(
            title = label,
            options = options,
            selected = selected,
            onPick = { value ->
                open = false
                onPick(value)
            },
            onDismiss = { open = false },
        )
    }
    // After the dialog window closes, make sure the remote is back on the row that opened it.
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (wasOpen && !open) runCatching { rowFocus.requestFocus() }
        wasOpen = open
    }
}

@Composable
private fun <T> PickerDialog(title: String, options: List<PickerOption<T>>, selected: T, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    val selectedFocus = remember { FocusRequester() }
    val selectedIndex = options.indexOfFirst { it.value == selected }.coerceAtLeast(0)
    LaunchedEffect(Unit) { runCatching { selectedFocus.requestFocus() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(ClickarrColors.BgBase.copy(alpha = SCRIM_ALPHA)), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .width(DIALOG_WIDTH)
                    .fillMaxHeight(DIALOG_HEIGHT)
                    .clip(RoundedCornerShape(ClickarrDimens.RadiusCard))
                    .background(ClickarrColors.BgPanel)
                    .padding(ClickarrDimens.CardPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(title, style = ClickarrTextStyles.ScreenTitle)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(options) { i, option ->
                        ListItem(
                            selected = i == selectedIndex,
                            onClick = { onPick(option.value) },
                            headlineContent = { Text(option.label, style = ClickarrTextStyles.RowTitle) },
                            supportingContent = option.detail?.let {
                                { Text(it, style = ClickarrTextStyles.Caption, color = ClickarrColors.TextSecondary) }
                            },
                            trailingContent = { if (i == selectedIndex) Text("✓", style = ClickarrTextStyles.RowTitle) },
                            modifier = if (i == selectedIndex) Modifier.focusRequester(selectedFocus) else Modifier,
                        )
                    }
                }
            }
        }
    }
}

private val ROW_WIDTH = 720.dp
private val DIALOG_WIDTH = 560.dp
private const val DIALOG_HEIGHT = 0.8f
private const val SCRIM_ALPHA = 0.85f
