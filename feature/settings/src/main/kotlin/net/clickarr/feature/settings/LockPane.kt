package net.clickarr.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import net.clickarr.data.SettingsLock
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens
import net.clickarr.ui.design.ClickarrTextStyles
import androidx.compose.foundation.layout.Box

/** Covers Settings until the code is entered. Guide, favorites, and watching stay open; only this is gated. */
@Composable
internal fun LockGate(lock: LockController) {
    val entry by lock.entry.collectAsState()
    val message by lock.message.collectAsState()
    Column(
        Modifier.fillMaxSize().background(ClickarrColors.BgBase).padding(ClickarrDimens.SafeArea),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Settings are locked", style = ClickarrTextStyles.ScreenTitle)
        LockCaption("Enter the ${SettingsLock.LENGTH}-digit code.")
        Dots(entry.length)
        DigitPad(onDigit = lock::digit, onClear = lock::clear)
        message?.let { Text(it, style = ClickarrTextStyles.Secondary, color = ClickarrColors.StatusWarn) }
    }
}

/** The General pane's block: set a code, or show that one is set and offer to remove it. */
@Composable
internal fun LockBlock(lock: LockController) {
    val hasCode by lock.hasCode.collectAsState()
    val setup by lock.setup.collectAsState()
    val entry by lock.entry.collectAsState()
    val message by lock.message.collectAsState()
    LockLabel("Settings lock")
    when (val s = setup) {
        LockController.Setup.Idle -> {
            if (hasCode) {
                LockCaption("A code is required to open Settings. Forgotten codes are cleared by clearing Clickarr's data on the TV.")
                Button(onClick = lock::removeCode) { Text("Remove code") }
            } else {
                LockCaption("Keep children out of Settings and the channel editor. The guide and watching stay open.")
                Button(onClick = lock::beginSetup) { Text("Set a code") }
            }
        }
        LockController.Setup.Enter, is LockController.Setup.Confirm -> {
            val prompt = when (s) {
                is LockController.Setup.Confirm -> "Enter the code again to confirm."
                else -> "Choose a ${SettingsLock.LENGTH}-digit code."
            }
            LockCaption(prompt)
            Dots(entry.length)
            DigitPad(onDigit = lock::digit, onClear = lock::clear)
            Button(onClick = lock::cancelSetup) { Text("Cancel") }
        }
    }
    message?.let { LockCaption(it) }
}

@Composable
private fun Dots(filled: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(SettingsLock.LENGTH) { i ->
            Box(
                Modifier.size(16.dp).clip(CircleShape)
                    .background(if (i < filled) ClickarrColors.AccentPrimary else ClickarrColors.BgCell),
            )
        }
    }
}

/** Phone-style pad, because most TV remotes have no digits. Keys carry "Key N" descriptions for tests and TalkBack. */
@Composable
private fun DigitPad(onDigit: (Int) -> Unit, onClear: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        PAD_ROWS.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { d ->
                    Button(
                        onClick = { onDigit(d) },
                        modifier = Modifier.width(KEY_WIDTH).semantics { contentDescription = "Key $d" },
                    ) { Text(d.toString()) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onClear, modifier = Modifier.width(KEY_WIDTH)) { Text("Clear") }
            Button(onClick = { onDigit(0) }, modifier = Modifier.width(KEY_WIDTH).semantics { contentDescription = "Key 0" }) { Text("0") }
        }
    }
}

private val KEY_WIDTH = 88.dp
private val PAD_ROWS = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9))

@Composable
private fun LockCaption(text: String) = Text(text, style = ClickarrTextStyles.Secondary, color = ClickarrColors.TextSecondary)

@Composable
private fun LockLabel(text: String) = Text(text, style = ClickarrTextStyles.LabelAllCaps, color = ClickarrColors.TextMuted)
