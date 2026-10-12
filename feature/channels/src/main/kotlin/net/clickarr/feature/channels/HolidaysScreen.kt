package net.clickarr.feature.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import kotlinx.datetime.LocalDate
import net.clickarr.core.model.Channel
import net.clickarr.core.model.Holiday
import net.clickarr.feature.channels.HolidaysViewModel.Step
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens
import net.clickarr.ui.design.ClickarrTextStyles

/** Settings, Channels, Holiday channels: switch each holiday on, choose what plays, or switch it off. */
@Composable
fun HolidaysScreen(onDone: () -> Unit, viewModel: HolidaysViewModel = hiltViewModel()) {
    val step by viewModel.step.collectAsState()
    val channels by viewModel.channels.collectAsState()
    androidx.activity.compose.BackHandler { if (step is Step.List) onDone() else viewModel.back() }
    Column(
        Modifier.fillMaxSize().background(ClickarrColors.BgBase).padding(ClickarrDimens.SafeArea),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Holiday channels", style = ClickarrTextStyles.ScreenTitle)
        when (val s = step) {
            Step.List -> HolidayList(channels, viewModel.today(), viewModel::open)
            is Step.ChooseMode -> ChooseMode(s.holiday, channels[s.holiday], viewModel)
            is Step.Loading -> Hint("Reading your library…")
            is Step.Pick -> Picker(s, viewModel)
            is Step.Working -> Hint("Building the ${s.holiday.label} channel…")
            is Step.Failed -> {
                Text(s.message, style = ClickarrTextStyles.Secondary, color = ClickarrColors.StatusError)
                Button(onClick = viewModel::back) { Text("Back") }
            }
        }
    }
}

@Composable
private fun HolidayList(channels: Map<Holiday, Channel>, today: LocalDate, onOpen: (Holiday) -> Unit) {
    Hint("A holiday channel shows in the guide only in the days leading up to its holiday, and comes back each year.")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Holiday.entries.forEach { h ->
            val ch = channels[h]
            val year = if (today > h.window(today.year).endInclusive) today.year + 1 else today.year
            val w = h.window(year)
            ListItem(
                selected = false,
                onClick = { onOpen(h) },
                headlineContent = { Text(h.label, style = ClickarrTextStyles.RowTitle) },
                supportingContent = {
                    val dates = "${day(w.start)} to ${day(w.endInclusive)}"
                    Text(dates, style = ClickarrTextStyles.Caption, color = ClickarrColors.TextSecondary)
                },
                trailingContent = {
                    Text(
                        ch?.let { "On · channel ${it.number}" } ?: "Off",
                        style = ClickarrTextStyles.RowTitle,
                        color = if (ch != null) ClickarrColors.AccentGlow else ClickarrColors.TextMuted,
                    )
                },
                modifier = Modifier.width(LIST_WIDTH),
            )
        }
    }
}

@Composable
private fun ChooseMode(holiday: Holiday, existing: Channel?, vm: HolidaysViewModel) {
    Text("${holiday.label}: what plays on it?", style = ClickarrTextStyles.RowTitle)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HolidaysViewModel.Mode.entries.forEach { mode ->
            ListItem(
                selected = false,
                onClick = { vm.choose(holiday, mode) },
                headlineContent = { Text(mode.label, style = ClickarrTextStyles.RowTitle) },
                supportingContent = { Text(mode.hint, style = ClickarrTextStyles.Caption, color = ClickarrColors.TextSecondary) },
                modifier = Modifier.width(LIST_WIDTH),
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (existing != null) Button(onClick = { vm.turnOff(holiday) }) { Text("Turn off") }
        Button(onClick = vm::back) { Text("Back") }
    }
    if (existing != null) Hint("Choosing again replaces what plays on channel ${existing.number}; its number stays.")
}

/** The list on the left; the number, the count, and Create on the right, so they are always one press away. */
@Composable
private fun Picker(s: Step.Pick, vm: HolidaysViewModel) {
    Text("${s.holiday.label}: pick ${s.mode.label.lowercase()}", style = ClickarrTextStyles.RowTitle)
    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.weight(1f)) {
            if (s.options.isEmpty()) Hint("Nothing to choose from in your library for this.")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(s.options, key = { it.key }) { option ->
                    val on = option.key in s.picked
                    ListItem(
                        selected = on,
                        onClick = { vm.toggle(option.key) },
                        headlineContent = { Text(option.label, style = ClickarrTextStyles.RowTitle) },
                        supportingContent = option.detail?.let { detail ->
                            { Text(detail, style = ClickarrTextStyles.Caption, color = ClickarrColors.TextSecondary) }
                        },
                        trailingContent = { Text(if (on) "✓" else "", style = ClickarrTextStyles.RowTitle) },
                    )
                }
            }
        }
        PickerPanel(s, vm)
    }
}

@Composable
private fun PickerPanel(s: Step.Pick, vm: HolidaysViewModel) {
    Column(
        Modifier.width(PANEL_WIDTH).background(ClickarrColors.BgPanel, RoundedCornerShape(ClickarrDimens.RadiusCard)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Channel number", style = ClickarrTextStyles.LabelAllCaps, color = ClickarrColors.TextMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.changeNumber(-1) }) { Text("−") }
            Text(s.number.toString(), style = ClickarrTextStyles.ChannelNumber)
            Button(onClick = { vm.changeNumber(+1) }) { Text("+") }
        }
        Text(
            s.numberProblem ?: "Holiday channels can all share one number; only one is ever on.",
            style = ClickarrTextStyles.Caption,
            color = if (s.numberProblem != null) ClickarrColors.StatusWarn else ClickarrColors.TextMuted,
        )
        Text("${s.picked.size} picked", style = ClickarrTextStyles.RowTitle)
        Button(onClick = vm::create) { Text(if (s.picked.isEmpty()) "Pick at least one" else "Create channel") }
        Button(onClick = vm::back) { Text("Back") }
    }
}

@Composable
private fun Hint(text: String) = Text(text, style = ClickarrTextStyles.Secondary, color = ClickarrColors.TextSecondary)

private fun day(d: LocalDate) = "${d.month.name.lowercase().replaceFirstChar(Char::uppercase).take(3)} ${d.dayOfMonth}"

private val LIST_WIDTH = 900.dp
private val PANEL_WIDTH = 380.dp
