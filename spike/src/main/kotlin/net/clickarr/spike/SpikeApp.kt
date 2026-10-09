package net.clickarr.spike

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.clickarr.spike.guide.GuideSpike
import net.clickarr.spike.nsd.NsdSpike
import net.clickarr.spike.player.PlayerSpike
import net.clickarr.spike.tls.TlsSpike
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens
import net.clickarr.ui.design.ClickarrTheme

enum class Spike(val title: String, val summary: String) {
    GUIDE("A: Guide skeleton", "50 synthetic channels, 3 h window. Measures frame time while scrolling."),
    PLAYER("B: Playback at offset", "ExoPlayer plays a URL from a given offset. Measures time to first frame."),
    TLS("C: TLS household server", "Netty + Android Keystore certificate, pinned client self-test."),
    NSD("D: LAN discovery", "Advertise and browse _clickarr._tcp with NsdManager."),
}

@Composable
fun SpikeApp(args: SpikeArgs) {
    ClickarrTheme {
        var current by rememberSaveable {
            mutableStateOf(Spike.entries.firstOrNull { it.name.equals(args.spike, ignoreCase = true) })
        }
        if (current != null) {
            BackHandler { current = null }
        }
        when (current) {
            null -> SpikeHome(onSelect = { current = it })
            Spike.GUIDE -> GuideSpike()
            Spike.PLAYER -> PlayerSpike(args)
            Spike.TLS -> TlsSpike()
            Spike.NSD -> NsdSpike()
        }
    }
}

@Composable
private fun SpikeHome(onSelect: (Spike) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ClickarrColors.BgBase)
            .padding(ClickarrDimens.SafeArea),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Clickarr Phase 0 spikes", style = MaterialTheme.typography.headlineLarge)
        Text(
            "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" +
                if (Build.MANUFACTURER.equals("Amazon", true)) ", Fire OS" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = ClickarrColors.TextSecondary,
        )
        Spacer(Modifier.height(8.dp))
        Spike.entries.forEach { spike ->
            Button(onClick = { onSelect(spike) }) {
                Column {
                    Text(spike.title, style = MaterialTheme.typography.titleMedium)
                    Text(spike.summary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
