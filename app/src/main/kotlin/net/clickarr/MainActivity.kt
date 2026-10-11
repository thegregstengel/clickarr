package net.clickarr

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import net.clickarr.spike.SpikeApp
import net.clickarr.spike.SpikeArgs
import net.clickarr.ui.design.ClickarrTheme

/**
 * Launches into the app (setup, then the player). The Phase 0 spike menu stays reachable with
 * `--es spike <name>` until the spikes have verdicts (docs/spikes.md).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var args by mutableStateOf(SpikeArgs())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        args = intent.toSpikeArgs()
        setContent {
            if (args.spike != null) ClickarrTheme { SpikeApp(args) } else ClickarrApp(onExit = { finish() })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        args = intent.toSpikeArgs()
    }

    // Another app may start the launcher activity with extras; only a debuggable build honours spike extras.
    private fun Intent.toSpikeArgs() = if (!BuildConfig.DEBUG) SpikeArgs() else SpikeArgs(
        spike = getStringExtra("spike"),
        url = getStringExtra("url"),
        offsetSec = getIntExtra("offsetSec", 0),
    )
}
