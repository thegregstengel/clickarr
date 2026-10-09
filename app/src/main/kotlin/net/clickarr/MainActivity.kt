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

/**
 * Phase 0: the app launches straight into the spike menu. Phase 1 replaces this with the player
 * (proposal: launch Clickarr, television starts playing).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var args by mutableStateOf(SpikeArgs())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        args = intent.toSpikeArgs()
        setContent { SpikeApp(args) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        args = intent.toSpikeArgs()
    }

    private fun Intent.toSpikeArgs() = SpikeArgs(
        spike = getStringExtra("spike"),
        url = getStringExtra("url"),
        offsetSec = getIntExtra("offsetSec", 0),
    )
}
