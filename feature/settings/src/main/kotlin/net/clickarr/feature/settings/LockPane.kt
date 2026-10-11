package net.clickarr.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import net.clickarr.data.SettingsLock
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens
import net.clickarr.ui.design.ClickarrTextStyles

/** Covers Settings until the code is entered. Guide, favorites, and watching stay open; only this is gated. */
@Composable
internal fun LockGate(lock: LockController, park: FocusPark) {
    val entry by lock.entry.collectAsState()
    val message by lock.message.collectAsState()
    val firstKey = remember { FocusRequester() }
    // The gate does not take focus when it appears. Tabs follow focus, so the viewer is usually still on the
    // settings cog with OK about to be pressed; focusing a key now would turn that OK into a digit. Down enters
    // the pad. After a wrong code, focus is parked and comes back to the keys.
    LaunchedEffect(message) { if (message != null) runCatching { firstKey.requestFocus() } }
    val sawDown = remember { booleanArrayOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(ClickarrColors.BgBase)
            .padding(ClickarrDimens.SafeArea)
            // A key-up whose key-down went elsewhere (the cog, a tab) is not a press on the pad.
            .onPreviewKeyEvent { e ->
                when (e.type) {
                    KeyEventType.KeyDown -> { sawDown[0] = true; false }
                    KeyEventType.KeyUp -> (!sawDown[0]).also { sawDown[0] = false }
                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Settings are locked", style = ClickarrTextStyles.ScreenTitle)
        LockCaption("Enter the ${SettingsLock.LENGTH}-digit code for this TV.")
        Dots(entry.length)
        DigitPad(firstKey = firstKey, entered = entry.length, park = park, onDigit = lock::digit, onClear = lock::clear)
        message?.let { Text(it, style = ClickarrTextStyles.Secondary, color = ClickarrColors.StatusWarn) }
    }
}

/** The General pane's block: set a code, or show that one is set and offer to remove it. */
@Composable
internal fun LockBlock(lock: LockController, park: FocusPark) {
    val hasCode by lock.hasCode.collectAsState()
    val setup by lock.setup.collectAsState()
    val entry by lock.entry.collectAsState()
    val message by lock.message.collectAsState()
    val firstKey = remember { FocusRequester() }
    val action = remember { FocusRequester() }
    // After each swap (button to pad, pad to button) the new primary control takes focus from the park.
    var shown by remember { mutableStateOf(setup) }
    LaunchedEffect(setup) {
        if (setup == shown) return@LaunchedEffect
        shown = setup
        val target = if (setup == LockController.Setup.Idle) action else firstKey
        runCatching { target.requestFocus() }
    }
    LockLabel("Settings lock")
    when (val s = setup) {
        LockController.Setup.Idle -> {
            if (hasCode) {
                LockCaption(
                    "A code is required to open Settings on this TV. The code is not synced; set one on each TV you want " +
                        "locked. A forgotten code is cleared by clearing Clickarr's data in the TV's app settings.",
                )
                Button(onClick = { park.park(); lock.removeCode() }, modifier = Modifier.focusRequester(action)) { Text("Remove code") }
            } else {
                LockCaption(
                    "Lock Settings and the channel editor behind a ${SettingsLock.LENGTH}-digit code. The guide and watching stay " +
                        "open. The code lives only on this TV; it is not synced to other TVs.",
                )
                Button(onClick = { park.park(); lock.beginSetup() }, modifier = Modifier.focusRequester(action)) { Text("Set a code") }
            }
        }
        LockController.Setup.Enter, is LockController.Setup.Confirm -> {
            val prompt = when (s) {
                is LockController.Setup.Confirm -> "Enter the code again to confirm."
                else -> "Choose a ${SettingsLock.LENGTH}-digit code."
            }
            LockCaption(prompt)
            Dots(entry.length)
            DigitPad(firstKey = firstKey, entered = entry.length, park = park, onDigit = lock::digit, onClear = lock::clear)
            Button(onClick = { park.park(); lock.cancelSetup() }) { Text("Cancel") }
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

/**
 * Phone-style pad, because most TV remotes have no digits. Keys carry "Key N" descriptions for tests and
 * TalkBack. The last digit of a code may make the pad disappear, so that press parks focus first.
 */
@Composable
private fun DigitPad(firstKey: FocusRequester, entered: Int, park: FocusPark, onDigit: (Int) -> Unit, onClear: () -> Unit) {
    val press: (Int) -> Unit = { d ->
        if (entered == SettingsLock.LENGTH - 1) park.park()
        onDigit(d)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        PAD_ROWS.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { d ->
                    val keyModifier = Modifier.width(KEY_WIDTH).semantics { contentDescription = "Key $d" }
                    Button(
                        onClick = { press(d) },
                        modifier = if (d == 1) keyModifier.focusRequester(firstKey) else keyModifier,
                    ) { Text(d.toString()) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onClear, modifier = Modifier.width(KEY_WIDTH)) { Text("Clear") }
            Button(onClick = { press(0) }, modifier = Modifier.width(KEY_WIDTH).semantics { contentDescription = "Key 0" }) { Text("0") }
        }
    }
}

@Composable
private fun LockCaption(text: String) = Text(text, style = ClickarrTextStyles.Secondary, color = ClickarrColors.TextSecondary)

@Composable
private fun LockLabel(text: String) = Text(text, style = ClickarrTextStyles.LabelAllCaps, color = ClickarrColors.TextMuted)

private val KEY_WIDTH = 88.dp
private val PAD_ROWS = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9))
