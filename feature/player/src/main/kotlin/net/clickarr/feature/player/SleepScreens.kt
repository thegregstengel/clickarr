package net.clickarr.feature.player

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens
import net.clickarr.ui.design.ClickarrMark
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

/**
 * Playback has stopped; a classic bouncing Clickarr mark keeps the panel awake so the screensaver can run.
 * Any button wakes and retunes live ([SleepGuard.onInput]).
 */
@Composable
fun AsleepScreen() {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        BounceLogo()
        Text(
            "Press any button to tune back in.",
            style = ClickarrTextStyles.Secondary,
            color = ClickarrColors.TextMuted,
            modifier = Modifier.align(Alignment.BottomCenter).padding(ClickarrDimens.SafeArea),
        )
    }
}

/** Constant-velocity bounce off the edges; quiet color shift on each hit. */
@Composable
private fun BounceLogo() {
    val density = LocalDensity.current
    val markPx = with(density) { MARK_DP.toPx() }
    var x by remember { mutableFloatStateOf(40f) }
    var y by remember { mutableFloatStateOf(40f) }
    var vx by remember { mutableFloatStateOf(SPEED_PX_S) }
    var vy by remember { mutableFloatStateOf(SPEED_PX_S * 0.72f) }
    var hits by remember { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxX = constraints.maxWidth.toFloat() - markPx
        val maxY = constraints.maxHeight.toFloat() - markPx
        LaunchedEffect(maxX, maxY) {
            var last = 0L
            while (true) {
                withFrameMillis { t ->
                    if (last == 0L) {
                        last = t
                        return@withFrameMillis
                    }
                    val dt = ((t - last).coerceAtMost(MAX_FRAME_MS)) / 1_000f
                    last = t
                    var nx = x + vx * dt
                    var ny = y + vy * dt
                    var nvx = vx
                    var nvy = vy
                    var bounced = false
                    if (nx <= 0f || nx >= maxX) {
                        nvx = -nvx
                        nx = nx.coerceIn(0f, maxX.coerceAtLeast(0f))
                        bounced = true
                    }
                    if (ny <= 0f || ny >= maxY) {
                        nvy = -nvy
                        ny = ny.coerceIn(0f, maxY.coerceAtLeast(0f))
                        bounced = true
                    }
                    x = nx
                    y = ny
                    vx = nvx
                    vy = nvy
                    if (bounced) hits++
                }
            }
        }
        val tint = when (hits % 4) {
            1 -> ClickarrColors.AccentGlow
            2 -> ClickarrColors.AccentPrimary
            3 -> ClickarrColors.AccentViolet
            else -> Color.White
        }
        ClickarrMark(
            MARK_DP,
            modifier = Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) },
            colorFilter = ColorFilter.tint(tint),
        )
    }
}

private val MARK_DP = 120.dp
private const val SPEED_PX_S = 140f
private const val MAX_FRAME_MS = 50L
private const val TICK_MS = 500L
private const val MS_PER_S = 1_000L
private const val PANEL_ALPHA = 0.94f
