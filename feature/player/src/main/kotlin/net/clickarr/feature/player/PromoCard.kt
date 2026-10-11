package net.clickarr.feature.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlin.time.Duration
import kotlinx.datetime.Instant
import net.clickarr.core.model.Channel
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens
import net.clickarr.ui.design.ClickarrTextStyles

/**
 * "Coming up next" between programs (Settings, Playback, Between programs): the next program's backdrop, slowly
 * drifting, with its title, time, and synopsis, and the rest of the evening on the right. With the channel
 * overlay up along the bottom, only the picture and the header stay, so the two never overlap.
 */
@Composable
fun PromoCard(promo: PromoDirector.Promo, channel: Channel?, now: Instant, showText: Boolean) {
    val drift = rememberInfiniteTransition(label = "promo").animateFloat(
        initialValue = 1f,
        targetValue = DRIFT_SCALE,
        animationSpec = infiniteRepeatable(tween(DRIFT_MS, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift",
    )
    Box(Modifier.fillMaxSize().background(ClickarrColors.BgBase)) {
        promo.backdropUrl?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = drift.value
                    scaleY = drift.value
                },
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to ClickarrColors.BgBase.copy(alpha = SCRIM), 0.7f to ClickarrColors.BgBase.copy(alpha = 0f)),
            ),
        )
        channel?.let {
            Column(Modifier.align(Alignment.TopStart).padding(ClickarrDimens.SafeArea)) {
                Text(it.number.toString(), style = ClickarrTextStyles.ChannelNumber)
                Text(it.name.uppercase(), style = ClickarrTextStyles.LabelAllCaps, color = ClickarrColors.TextSecondary)
            }
        }
        if (showText) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(ClickarrDimens.SafeArea),
                horizontalArrangement = Arrangement.spacedBy(64.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                ComingUp(promo, now)
                if (promo.later.isNotEmpty()) LaterTonight(promo)
            }
        }
    }
}

@Composable
private fun ComingUp(promo: PromoDirector.Promo, now: Instant) {
    Column(Modifier.width(TEXT_WIDTH), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("COMING UP NEXT", style = ClickarrTextStyles.LabelAllCaps, color = ClickarrColors.AccentGlow)
        Text(promo.title, style = ClickarrTextStyles.ScreenTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
        promo.subtitle?.let { Text(it, style = ClickarrTextStyles.RowTitle, color = ClickarrColors.TextSecondary) }
        val wait = (promo.at - now).coerceAtLeast(Duration.ZERO)
        Text("${timeOfDay(promo.at)}  ·  in ${mmss(wait)}", style = ClickarrTextStyles.Secondary, color = ClickarrColors.AccentGlow)
        promo.summary?.let {
            Text(
                it,
                style = ClickarrTextStyles.Secondary,
                color = ClickarrColors.TextSecondary,
                maxLines = SUMMARY_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LaterTonight(promo: PromoDirector.Promo) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("LATER", style = ClickarrTextStyles.LabelAllCaps, color = ClickarrColors.TextMuted)
        promo.later.forEach { a ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(timeOfDay(a.start), style = ClickarrTextStyles.Secondary, color = ClickarrColors.TextSecondary)
                Text(a.entry.title, style = ClickarrTextStyles.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private const val DRIFT_SCALE = 1.08f
private const val DRIFT_MS = 40_000
private const val SCRIM = 0.92f
private const val SUMMARY_LINES = 3
private val TEXT_WIDTH = 880.dp
