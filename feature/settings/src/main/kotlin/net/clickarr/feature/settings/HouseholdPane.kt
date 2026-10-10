package net.clickarr.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import net.clickarr.core.model.HouseholdDevice
import net.clickarr.data.HouseholdService
import net.clickarr.household.protocol.Pairing
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens
import net.clickarr.ui.design.ClickarrTextStyles

/** Settings, Household (design language 4, "Device card", "PIN display and entry"). */
@Composable
fun HouseholdPane(viewModel: HouseholdViewModel = hiltViewModel()) {
    val role by viewModel.role.collectAsState()
    val household by viewModel.household.collectAsState()
    val devices by viewModel.devices.collectAsState()
    val message by viewModel.message.collectAsState()
    val joining by viewModel.joining.collectAsState()

    // When a section is swapped out (create, join, leave), the focused button disappears with it and TV focus
    // would fall back to the shell's tab row, switching tabs. Hand focus to the new section's primary action.
    val section = when (role) {
        HouseholdService.Role.None -> if (joining) "join" else "none"
        is HouseholdService.Role.Coordinator -> if (role.pin != null) "coordinator-pin" else "coordinator"
        is HouseholdService.Role.Member -> "member"
    }
    val primaryFocus = remember { FocusRequester() }
    var shownSection by remember { mutableStateOf(section) }
    LaunchedEffect(section) {
        if (section != shownSection) {
            shownSection = section
            runCatching { primaryFocus.requestFocus() }
        }
    }
    val primary = Modifier.focusRequester(primaryFocus)
    when (val r = role) {
        HouseholdService.Role.None -> if (joining) JoinSection(viewModel, primary) else NoneSection(viewModel, primary)
        is HouseholdService.Role.Coordinator -> CoordinatorSection(r, household?.name, devices, viewModel, primary)
        is HouseholdService.Role.Member -> MemberSection(r, household?.name, devices, viewModel, primary)
    }
    message?.let { Text(it, style = ClickarrTextStyles.Secondary, color = ClickarrColors.StatusError) }
}

@Composable
private fun NoneSection(vm: HouseholdViewModel, primary: Modifier) {
    Caption(
        "A household lets several TVs share one channel lineup and agree on what is on. " +
            "One TV coordinates; the others follow it over your Wi-Fi.",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { vm.create("Home") }, modifier = primary) { Text("Create a household") }
        Button(onClick = vm::startJoining) { Text("Join a household") }
    }
}

@Composable
private fun JoinSection(vm: HouseholdViewModel, primary: Modifier) {
    val found by vm.discovered.collectAsState()
    var address by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    Label("Households found")
    if (found.isEmpty()) Caption("Looking… On the other TV open Settings, Household, Add a device.")
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
        Button(onClick = { vm.join(address, fingerprint, pin) }, modifier = primary) { Text("Join") }
        Button(onClick = vm::stopJoining) { Text("Cancel") }
    }
}

@Composable
private fun CoordinatorSection(
    r: HouseholdService.Role.Coordinator,
    name: String?,
    devices: List<HouseholdDevice>,
    vm: HouseholdViewModel,
    primary: Modifier,
) {
    Caption("This TV coordinates household ${name ?: ""}. Other TVs find it on your network on port ${r.port}.")
    val pin = r.pin
    if (pin != null) {
        Label("On the new TV, enter this code")
        Text(
            Pairing.display(pin.pin),
            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 72.sp, letterSpacing = 6.sp),
            color = ClickarrColors.AccentGlow,
        )
        Caption("Expires in about two minutes. One code pairs one TV.")
        Button(onClick = vm::stopAddDevice, modifier = primary) { Text("Stop") }
    } else {
        Button(onClick = vm::beginAddDevice, modifier = primary) { Text("Add a device") }
    }
    DevicesList(devices, vm, canRemove = true)
    Button(onClick = vm::leave) { Text("Dissolve household") }
}

@Composable
private fun MemberSection(
    r: HouseholdService.Role.Member,
    name: String?,
    devices: List<HouseholdDevice>,
    vm: HouseholdViewModel,
    primary: Modifier,
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
        Button(onClick = vm::leave) { Text("Leave household") }
    }
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
