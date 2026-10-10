package net.clickarr.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Clock
import net.clickarr.core.common.Log
import net.clickarr.core.model.HouseholdDevice
import net.clickarr.data.HouseholdService
import net.clickarr.household.protocol.Pairing
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens
import net.clickarr.ui.design.ClickarrTextStyles

/** Settings, Sync (design language 4, "Device card", "PIN display and entry"). */
@Composable
fun HouseholdPane(viewModel: HouseholdViewModel = hiltViewModel()) {
    val role by viewModel.role.collectAsState()
    val household by viewModel.household.collectAsState()
    val devices by viewModel.devices.collectAsState()
    val message by viewModel.message.collectAsState()
    val joining by viewModel.joining.collectAsState()

    val focus = rememberSectionFocus(sectionKey(role, joining), message)
    val primary = focus.primary
    val swap = focus::swap
    when (val r = role) {
        HouseholdService.Role.None -> if (joining) JoinSection(viewModel, primary, swap) else NoneSection(viewModel, primary, swap)
        is HouseholdService.Role.Coordinator -> CoordinatorSection(r, household?.name, devices, viewModel, primary, swap)
        is HouseholdService.Role.Member -> MemberSection(r, household?.name, devices, viewModel, primary, swap)
    }
    message?.let { Text(it, style = ClickarrTextStyles.Secondary, color = ClickarrColors.StatusError) }
}

/**
 * Keeps TV focus inside the pane while its sections change. When a section is swapped out (create, join,
 * leave), the focused button disappears with it and focus would fall back to the shell's tab row; instead an
 * action first parks focus on a hidden anchor, and the new section's primary action takes it afterwards.
 */
private class SectionFocus(val primaryRequester: FocusRequester, val anchor: FocusRequester) {
    val parking = booleanArrayOf(false)
    val primary: Modifier get() = Modifier.focusRequester(primaryRequester)

    fun swap(action: () -> Unit) {
        parking[0] = true
        runCatching { anchor.requestFocus() }
        action()
    }

    fun takePrimary(): Boolean {
        parking[0] = false
        return runCatching { primaryRequester.requestFocus() }.getOrDefault(false)
    }
}

private fun sectionKey(role: HouseholdService.Role, joining: Boolean): String = when (role) {
    HouseholdService.Role.None -> if (joining) "join" else "none"
    is HouseholdService.Role.Coordinator -> if (role.pin != null) "coordinator-pin" else "coordinator"
    is HouseholdService.Role.Member -> "member"
}

@Composable
private fun rememberSectionFocus(section: String, message: String?): SectionFocus {
    val focus = remember { SectionFocus(FocusRequester(), FocusRequester()) }
    var shownSection by remember { mutableStateOf(section) }
    LaunchedEffect(section) {
        if (section != shownSection) {
            shownSection = section
            val ok = focus.takePrimary()
            Log.d(TAG) { "section $section, primary focus taken: $ok" }
        }
    }
    // A failed action leaves the section in place with a message; bring focus back from the anchor.
    LaunchedEffect(message) { if (message != null) focus.takePrimary() }
    // The anchor. If the viewer's D-pad lands here instead, pass straight through to the primary action.
    Box(
        Modifier
            .size(1.dp)
            .focusRequester(focus.anchor)
            .onFocusChanged { if (it.isFocused && !focus.parking[0]) focus.takePrimary() }
            .focusable(),
    )
    return focus
}

@Composable
private fun NoneSection(vm: HouseholdViewModel, primary: Modifier, swap: (() -> Unit) -> Unit) {
    Caption(
        "A household lets several TVs share one channel lineup and agree on what is on. " +
            "One TV coordinates; the others follow it over your Wi-Fi.",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { swap { vm.create("Home") } }, modifier = primary) { Text("Create a household") }
        Button(onClick = { swap(vm::startJoining) }) { Text("Join a household") }
    }
}

@Composable
private fun JoinSection(vm: HouseholdViewModel, primary: Modifier, swap: (() -> Unit) -> Unit) {
    val found by vm.discovered.collectAsState()
    var address by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    Label("Households found")
    if (found.isEmpty()) Caption("Looking… On the other TV open Settings, Sync, Add a device.")
    found.forEach { c ->
        ListItem(
            selected = address == c.baseUrl,
            onClick = {
                address = c.baseUrl
                fingerprint = c.fingerprint
            },
            headlineContent = { Text(c.householdName ?: c.serviceName, style = ClickarrTextStyles.RowTitle) },
            supportingContent = { Text("${c.host}:${c.port}", style = ClickarrTextStyles.Caption, color = ClickarrColors.TextSecondary) },
        )
    }
    Label("Or enter the address")
    Field("http://192.168.1.20:47831", address, "household.address") { address = it }
    Label("PIN shown on the other TV")
    Field("482 913", pin, "household.pin") { pin = it }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { swap { vm.join(address, fingerprint, pin) } }, modifier = primary) { Text("Join") }
        Button(onClick = { swap(vm::stopJoining) }) { Text("Cancel") }
    }
}

@Composable
private fun CoordinatorSection(
    r: HouseholdService.Role.Coordinator,
    name: String?,
    devices: List<HouseholdDevice>,
    vm: HouseholdViewModel,
    primary: Modifier,
    swap: (() -> Unit) -> Unit,
) {
    val transport = if (r.tls) "over TLS" else "without TLS; this TV could not start a secure server, so pair only on a trusted network"
    Caption("This TV coordinates household ${name ?: ""}. Other TVs find it on your network on port ${r.port}, $transport.")
    val pin = r.pin
    if (pin != null) {
        Label("On the new TV, enter this code")
        Text(
            Pairing.display(pin.pin),
            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 72.sp, letterSpacing = 6.sp),
            color = ClickarrColors.AccentGlow,
        )
        Caption("Expires in about two minutes. One code pairs one TV.")
        Button(onClick = { swap(vm::stopAddDevice) }, modifier = primary) { Text("Stop") }
    } else {
        Button(onClick = { swap(vm::beginAddDevice) }, modifier = primary) { Text("Add a device") }
    }
    DevicesList(devices, vm, canRemove = true)
    Button(onClick = { swap(vm::leave) }) { Text("Dissolve household") }
}

@Composable
private fun MemberSection(
    r: HouseholdService.Role.Member,
    name: String?,
    devices: List<HouseholdDevice>,
    vm: HouseholdViewModel,
    primary: Modifier,
    swap: (() -> Unit) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Dot(ok = r.connected)
        Column {
            Text("Connected to household ${name ?: ""}", style = ClickarrTextStyles.RowTitle)
            val status = if (r.connected) {
                "Coordinator at ${r.baseUrl.removePrefix("http://")}"
            } else {
                "Coordinator offline. You can still watch; changes need it online."
            }
            Caption(status)
        }
    }
    r.lastSync?.let { Caption("Synced ${ago(it.toEpochMilliseconds())}") }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = vm::syncNow, modifier = primary) { Text("Sync now") }
        Button(onClick = { swap(vm::leave) }) { Text("Leave household") }
        Button(onClick = { swap(vm::promote) }) { Text("Make this TV the coordinator") }
    }
    Caption("Taking over keeps the channels this TV has. Dissolve the household on the old coordinator, then join the other TVs here.")
    DevicesList(devices, vm, canRemove = false)
}

@Composable
private fun DevicesList(devices: List<HouseholdDevice>, vm: HouseholdViewModel, canRemove: Boolean) {
    Label("Household devices")
    val now = Clock.System.now().toEpochMilliseconds()
    devices.forEach { d ->
        val seen = d.lastSeen?.toEpochMilliseconds()
        val online = seen != null && now - seen < 5.minutes.inWholeMilliseconds
        val isSelf = d.id.value == vm.selfId
        ListItem(
            selected = false,
            onClick = { if (canRemove && !isSelf) vm.removeDevice(d.id) },
            headlineContent = { Text(d.name + if (isSelf) " (this TV)" else "", style = ClickarrTextStyles.RowTitle) },
            supportingContent = {
                val status = if (isSelf) "This device" else "Synced ${seen?.let { ago(it) } ?: "never"}"
                Text(status, style = ClickarrTextStyles.Caption, color = ClickarrColors.TextSecondary)
            },
            trailingContent = { Dot(ok = online || isSelf) },
        )
    }
    if (canRemove && devices.size > 1) Caption("Select a device to remove it from the household.")
}

@Composable
private fun Dot(ok: Boolean) {
    Box(Modifier.size(12.dp).background(if (ok) ClickarrColors.StatusOk else ClickarrColors.TextMuted, CircleShape))
}

@Composable
private fun Field(placeholder: String, value: String, tag: String, onChange: (String) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth(0.6f)
            .background(ClickarrColors.BgCell, RoundedCornerShape(ClickarrDimens.RadiusCell))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, style = ClickarrTextStyles.RowTitle, color = ClickarrColors.TextMuted)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = ClickarrTextStyles.RowTitle.copy(color = ClickarrColors.TextPrimary),
            modifier = Modifier.testTag(tag),
        )
    }
}

@Composable
private fun Caption(text: String) = Text(text, style = ClickarrTextStyles.Secondary, color = ClickarrColors.TextSecondary)

@Composable
private fun Label(text: String) = Text(text, style = ClickarrTextStyles.LabelAllCaps, color = ClickarrColors.TextMuted)

private fun ago(epochMs: Long): String {
    val minutes = (Clock.System.now().toEpochMilliseconds() - epochMs) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes == 1L -> "1 minute ago"
        minutes < 60 -> "$minutes minutes ago"
        else -> "${minutes / 60} hours ago"
    }
}

private const val TAG = "HouseholdPane"
