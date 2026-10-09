package net.clickarr.spike.player

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.clickarr.core.common.Redact
import net.clickarr.spike.SpikeArgs
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens
import okhttp3.OkHttpClient

/**
 * Spike B. Plays [SpikeArgs.url] starting at [SpikeArgs.offsetSec] and reports:
 * time from prepare() to first rendered frame, the position actually reached, decoder and format.
 * This is the tune-in path from proposal section 10.2 with nothing else around it.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerSpike(args: SpikeArgs) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Waiting for URL. Launch with --es url ... --ei offsetSec N") }
    var detail by remember { mutableStateOf("") }

    val player = remember {
        val okHttp = OkHttpClient.Builder().build()
        val dataSourceFactory = OkHttpDataSource.Factory(okHttp).setUserAgent("Clickarr-Spike/0.0")
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory))
            .build()
    }

    DisposableEffect(args.url) {
        val url = args.url
        if (url != null) {
            val t0 = SystemClock.elapsedRealtime()
            var firstFrameReported = false
            player.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    if (firstFrameReported) return
                    firstFrameReported = true
                    val ms = SystemClock.elapsedRealtime() - t0
                    val pos = player.currentPosition / 1000.0
                    status = "First frame after $ms ms at ${"%.1f".format(pos)} s (requested ${args.offsetSec} s)"
                }

                override fun onPlayerError(error: PlaybackException) {
                    status = "Error: ${error.errorCodeName} ${Redact.apply(error.message ?: "")}"
                }
            })
            player.addAnalyticsListener(object : AnalyticsListener {
                override fun onVideoDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long,
                ) {
                    detail = "decoder $decoderName (${initializationDurationMs} ms) " +
                        "format ${player.videoFormat?.sampleMimeType} ${player.videoFormat?.width}x${player.videoFormat?.height}"
                }
            })
            status = "Loading ${Redact.apply(url)}"
            player.setMediaItem(MediaItem.fromUri(url), args.offsetSec * 1000L)
            player.prepare()
            player.playWhenReady = true
        }
        onDispose { player.release() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = false } },
            modifier = Modifier.fillMaxSize(),
        )
        Column(Modifier.padding(ClickarrDimens.SafeArea).background(ClickarrColors.BgSurface.copy(alpha = 0.85f)).padding(16.dp)) {
            Text("Player spike", style = MaterialTheme.typography.titleMedium)
            Text(status, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = ClickarrColors.TextSecondary)
        }
    }
}
