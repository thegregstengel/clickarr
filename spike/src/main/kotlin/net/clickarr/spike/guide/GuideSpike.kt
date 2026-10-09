package net.clickarr.spike.guide

import android.view.Choreographer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens
import net.clickarr.ui.design.clickarrFocusable
import kotlin.random.Random

/**
 * Spike A. Proves the guide grid approach from proposal section 11 at the scale that matters:
 * 50 channel rows, a 3-hour window, a custom Layout per row, shared horizontal scroll, D-pad focus
 * moving between cells. Shows live frame statistics so the result can be read off the screen.
 *
 * Pass criterion (docs/spikes.md): on a Fire TV Stick 4K (2018), janky frames under 5 percent while
 * holding D-pad down through all rows.
 */
private data class FakeProgram(val title: String, val startMin: Int, val durationMin: Int)
private data class FakeChannel(val number: Int, val name: String, val programs: List<FakeProgram>)

private val titles = listOf(
    "The Office", "Parks and Recreation", "Seinfeld", "Community", "Star Trek: TNG", "Stargate SG-1",
    "Back to the Future", "The Goonies", "SpongeBob SquarePants", "The Santa Clause", "Frasier", "Cheers",
)

private fun fakeChannels(count: Int, windowMin: Int): List<FakeChannel> {
    val rnd = Random(7)
    return List(count) { i ->
        var t = 0
        val programs = buildList {
            while (t < windowMin) {
                val d = listOf(30, 30, 30, 60, 90, 120).random(rnd)
                add(FakeProgram(titles.random(rnd), t, d))
                t += d
            }
        }
        FakeChannel(number = (i + 1) * 2, name = "Channel ${(i + 1) * 2}", programs = programs)
    }
}

@Composable
fun GuideSpike() {
    val windowMin = 180
    val channels = remember { fakeChannels(50, windowMin) }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val minuteWidthPx = with(density) { (ClickarrDimens.GuideHalfHourWidth / 30).toPx() }
    val stats = rememberFrameStats()

    Column(Modifier.fillMaxSize().background(ClickarrColors.BgBase).padding(ClickarrDimens.SafeArea / 2)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Guide spike", style = MaterialTheme.typography.headlineMedium)
            Text(
                "   frames ${stats.frames}  janky ${stats.janky} (${stats.jankPercent}%)  worst ${stats.worstMs} ms",
                style = MaterialTheme.typography.bodyMedium,
                color = if (stats.jankPercent > 5) ClickarrColors.StatusWarn else ClickarrColors.StatusOk,
            )
        }
        Row(Modifier.fillMaxWidth().height(40.dp)) {
            Box(Modifier.width(160.dp))
            Row(Modifier.horizontalScroll(scroll)) {
                for (m in 0 until windowMin step 30) {
                    Box(Modifier.width(ClickarrDimens.GuideHalfHourWidth)) {
                        Text(formatMinutes(19 * 60 + m), style = MaterialTheme.typography.labelMedium, color = ClickarrColors.TextSecondary)
                    }
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(channels, key = { it.number }) { channel ->
                Row(Modifier.fillMaxWidth().height(64.dp).padding(vertical = ClickarrDimens.GuideCellGutter / 2)) {
                    Box(Modifier.width(160.dp).fillMaxSize().background(ClickarrColors.BgPanel, RoundedCornerShape(ClickarrDimens.RadiusCell)), contentAlignment = Alignment.CenterStart) {
                        Text("  ${channel.number}  ${channel.name}", style = MaterialTheme.typography.labelMedium)
                    }
                    Box(Modifier.fillMaxSize().clipToBounds().horizontalScroll(scroll)) {
                        ProgramRow(channel.programs, minuteWidthPx)
                    }
                }
            }
        }
    }
}

/** Positions program cells by time. Only this layout knows about minutes-to-pixels. */
@Composable
private fun ProgramRow(programs: List<FakeProgram>, minuteWidthPx: Float) {
    Layout(
        content = {
            programs.forEach { p ->
                val interaction = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = ClickarrDimens.GuideCellGutter / 2)
                        .clickarrFocusable(interaction)
                        .border(1.dp, ClickarrColors.BgCellBorder, RoundedCornerShape(ClickarrDimens.RadiusCell))
                        .focusable(interactionSource = interaction)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(p.title, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.mapIndexed { i, m ->
            val w = (programs[i].durationMin * minuteWidthPx).toInt()
            m.measure(constraints.copy(minWidth = w, maxWidth = w))
        }
        val total = (programs.sumOf { it.durationMin } * minuteWidthPx).toInt()
        layout(total, constraints.maxHeight) {
            placeables.forEachIndexed { i, p -> p.place((programs[i].startMin * minuteWidthPx).toInt(), 0) }
        }
    }
}

private fun formatMinutes(total: Int): String {
    val h24 = (total / 60) % 24
    val m = total % 60
    val h12 = if (h24 % 12 == 0) 12 else h24 % 12
    val ampm = if (h24 < 12) "AM" else "PM"
    return "%d:%02d %s".format(h12, m, ampm)
}

class FrameStats {
    var frames by mutableIntStateOf(0)
    var janky by mutableIntStateOf(0)
    var worstMs by mutableLongStateOf(0L)
    val jankPercent: Int get() = if (frames == 0) 0 else janky * 100 / frames
}

/** Counts frames whose interval exceeds 1.5x the 60 Hz budget. Crude but readable from the couch. */
@Composable
private fun rememberFrameStats(): FrameStats {
    val stats = remember { FrameStats() }
    DisposableEffect(Unit) {
        var last = 0L
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (last != 0L) {
                    val ms = (frameTimeNanos - last) / 1_000_000
                    stats.frames++
                    if (ms > 25) stats.janky++
                    if (ms > stats.worstMs) stats.worstMs = ms
                }
                last = frameTimeNanos
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        Choreographer.getInstance().postFrameCallback(cb)
        onDispose { Choreographer.getInstance().removeFrameCallback(cb) }
    }
    return stats
}
