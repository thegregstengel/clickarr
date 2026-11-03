package net.clickarr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint

/** Launches into the app: Plex sign-in when no server is connected, otherwise the player on the last channel. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // finishAndRemoveTask clears the singleTask entry so Leave + relaunch does not leave a zombie
        // root that finishes on the first Back to Google TV Home.
        setContent { ClickarrApp(onExit = { finishAndRemoveTask() }) }
    }
}
