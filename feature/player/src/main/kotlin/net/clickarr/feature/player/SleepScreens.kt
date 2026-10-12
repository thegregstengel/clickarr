package net.clickarr.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import android.os.SystemClock
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens
import net.clickarr.ui.design.ClickarrTextStyles

/** "Are you still watching?" over the picture, with the seconds left before playback stops. */
@Composable
fun StillWatchingPrompt(deadlineMs: Long) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(deadlineMs) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(TICK_MS)
        }
    }
    val seconds = ((deadlineMs - now).coerceAtLeast(0) + MS_PER_S - 1) / MS_PER_S
    Box(Modifier.fillMaxSize().padding(ClickarrDimens.SafeArea), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .background(ClickarrColors.BgSurface.copy(alpha = PANEL_ALPHA), RoundedCornerShape(ClickarrDimens.RadiusCard))
                .padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Are you still watching?", style = ClickarrTextStyles.ScreenTitle)
            Text("Press any button to keep watching.", style = ClickarrTextStyles.Secondary, color = ClickarrColors.TextSecondary)
            Text("Stopping in $seconds s", style = ClickarrTextStyles.Secondary, color = ClickarrColors.AccentGlow)
        }
    }
}

/** Playback has stopped for the night; the screen is free to dim, and any button picks the channel up live. */
@Composable
fun AsleepScreen() {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Clickarr is asleep", style = ClickarrTextStyles.ScreenTitle, color = ClickarrColors.TextMuted)
            Text("Press any button to tune back in.", style = ClickarrTextStyles.Secondary, color = ClickarrColors.TextMuted)
        }
    }
}

private const val TICK_MS = 500L
private const val MS_PER_S = 1_000L
private const val PANEL_ALPHA = 0.94f
