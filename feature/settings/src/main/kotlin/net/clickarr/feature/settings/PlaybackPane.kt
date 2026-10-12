package net.clickarr.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import net.clickarr.core.common.AppTime
import net.clickarr.data.DevicePrefs
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrTextStyles

/** Settings, Playback: what this TV decodes, and how watching behaves (between programs, captions, sleep). */
@Composable
internal fun PlaybackPane(vm: PlaybackSettingsViewModel = hiltViewModel()) {
    val p = vm.profile
    PaneLabel("Direct play profile")
    PaneCaption("Up to ${p.maxWidth}×${p.maxHeight}, video ${p.videoCodecs.joinToString()}, audio ${p.audioCodecs.joinToString()}.")
    PaneCaption("Probed from this TV's decoders and display at startup. Anything outside it is transcoded by Plex.")
    val between by vm.betweenPrograms.collectAsState()
    PaneLabel("Between programs")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PaneChip("Coming up next", selected = between == DevicePrefs.BETWEEN_PROMO) { vm.setBetweenPrograms(DevicePrefs.BETWEEN_PROMO) }
        PaneChip("Countdown", selected = between == DevicePrefs.BETWEEN_COUNTDOWN) { vm.setBetweenPrograms(DevicePrefs.BETWEEN_COUNTDOWN) }
    }
    PaneCaption(
        "On channels with 15, 30, or 60-minute slots, the gap before the next program shows its backdrop, time, and " +
            "synopsis with the show's theme music, all from what Plex already has. Or a plain countdown.",
    )
    val captions by vm.captions.collectAsState()
    PaneLabel("Captions")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PaneChip("Off", selected = !captions) { vm.setCaptions(false) }
        PaneChip("On", selected = captions) { vm.setCaptions(true) }
    }
    PaneCaption(
        "Shows the program's subtitles in this TV's language when it has them. Hold OK while watching to switch. " +
            "Clickarr draws them in its own clean style; captions burned in by a Plex transcode keep the server's look.",
    )
    SleepBlock(vm)
}

/** Settings, Plex Server: whether, and how much, Plex hears about what plays on this TV. */
@Composable
internal fun WatchReportingBlock(vm: PlaybackSettingsViewModel = hiltViewModel()) {
    val watch by vm.watchReporting.collectAsState()
    PaneLabel("Tell Plex what you watched")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PaneChip("Off", selected = watch == DevicePrefs.WATCH_OFF) { vm.setWatchReporting(DevicePrefs.WATCH_OFF) }
        PaneChip("Mark watched", selected = watch == DevicePrefs.WATCH_WATCHED) { vm.setWatchReporting(DevicePrefs.WATCH_WATCHED) }
        PaneChip("Progress too", selected = watch == DevicePrefs.WATCH_PROGRESS) { vm.setWatchReporting(DevicePrefs.WATCH_PROGRESS) }
    }
    PaneCaption(
        when (watch) {
            DevicePrefs.WATCH_OFF -> "Plex never hears about what plays here."
            DevicePrefs.WATCH_PROGRESS ->
                "Episodes you sit through are marked watched, and the running position is sent too, so they appear in Continue Watching."
            else -> "An episode is marked watched once this TV has played most of it in one sitting. Tuning in for the last five minutes " +
                "does not count, and nothing lands in Continue Watching."
        },
    )
}

/** Sleep timer and "Are you still watching?". Five timer chips do not fit one row at every UI size, so they wrap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SleepBlock(vm: PlaybackSettingsViewModel) {
    val sleepAt by vm.sleepAt.collectAsState()
    PaneLabel("Sleep timer")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PaneChip("Off", selected = sleepAt == null) { vm.setSleepIn(null) }
        SLEEP_CHOICES.forEach { (label, minutes) -> PaneChip(label, selected = false) { vm.setSleepIn(minutes) } }
    }
    PaneCaption(
        sleepAt?.let { "Playback stops at ${AppTime.timeOfDay(it)} and the TV's own screensaver takes over." }
            ?: "Stop playing after a while, for falling asleep in front of the TV.",
    )
    val hours by vm.stillWatchingHours.collectAsState()
    PaneLabel("Are you still watching?")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PaneChip("Never", selected = hours == 0) { vm.setStillWatchingHours(0) }
        listOf(2, 3, 4).forEach { h -> PaneChip("After $h hours", selected = hours == h) { vm.setStillWatchingHours(h) } }
    }
    PaneCaption(
        "After that long without a button press, Clickarr asks. With no answer in a minute it stops, so a channel left " +
            "on overnight does not stream all night or mark episodes watched.",
    )
}

private val SLEEP_CHOICES = listOf("30 min" to 30, "1 hour" to 60, "90 min" to 90, "2 hours" to 120)

@Composable
private fun PaneCaption(text: String) = Text(text, style = ClickarrTextStyles.Secondary, color = ClickarrColors.TextSecondary)

@Composable
private fun PaneLabel(text: String) = Text(text, style = ClickarrTextStyles.LabelAllCaps, color = ClickarrColors.TextMuted)

@Composable
private fun PaneChip(label: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        selected = selected,
        onClick = onClick,
        headlineContent = { Text(label, style = ClickarrTextStyles.Caption) },
        modifier = Modifier.widthIn(min = 140.dp, max = 260.dp),
    )
}
