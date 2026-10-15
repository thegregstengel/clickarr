package net.clickarr

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import net.clickarr.provider.jellyfin.fixtures.JellyfinFixtureServer
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test

/**
 * First run against a fake Jellyfin server (ADR 0022): type the address, sign in with Quick Connect, build a channel,
 * tune it, and check Settings shows Jellyfin with Plex disabled. The Emulator workflow runs this class in its own
 * install, so it starts with no server even though FirstRunFlowTest signs in to Plex.
 */
class JellyfinFlowTest : TvFlow() {
    @Test
    fun quickConnectCreateChannelAndTune() = flow {
        waitForText("Connect to your media server", timeoutMs = 90_000)
        click("Jellyfin")
        waitForText("Jellyfin server address")
        compose.onNodeWithTag("jellyfin.url").performTextClearance()
        compose.onNodeWithTag("jellyfin.url").performTextInput(jellyfin.baseUrl)
        shot("j01-jellyfin-address")
        click("Next")

        waitForText("Sign in to Jellyfin")
        shot("j02-jellyfin-sign-in")
        click("Quick Connect")
        waitForText("Settings, Quick Connect")
        shot("j03-quick-connect")
        // The viewer approves the code in a Jellyfin app; the TV notices on its next poll.
        jellyfin.approveQuickConnect()

        waitForText("Custom channel", timeoutMs = 30_000)
        click("Custom channel")
        waitForText("Which library?")
        click("Shows")
        waitForText("What from Shows?")
        click("Shows")
        waitForText("The Office (US)")
        shot("j04-pick-shows")
        click("The Office (US)")
        click("Continue")
        waitForText("Channel number")
        click("Create channel")
        waitForText("Custom channel")
        waitForText("The Office (US)")

        // A collection channel, so the Jellyfin collection path is exercised end to end.
        click("Custom channel")
        waitForText("Which library?")
        click("Movies")
        waitForText("What from Movies?")
        click("Collections")
        waitForText("Which collection?")
        click("The Lord of the Rings")
        waitForText("Channel number")
        reach("Create channel")
        click("Create channel")
        waitForText("The Lord of the Rings")
        shot("j05-channels")

        click("Guide")
        waitForText("Today")
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.waitForIdle()
        device.pressDPadCenter()
        waitForText("THE OFFICE (US)", timeoutMs = 30_000)
        waitForText("S1E", timeoutMs = 30_000)
        shot("j06-player-overlay")
        device.pressKeyCode(KeyEvent.KEYCODE_MENU)
        waitForText("Today")
        shot("j07-guide")

        openSettings()
        waitForText("This TV")
        val plex = compose.onAllNodes(hasText("Plex", substring = false)).onFirst().fetchSemanticsNode()
        check(SemanticsProperties.Disabled in plex.config) { "Settings, Plex should be disabled on a Jellyfin TV" }
        click("Jellyfin")
        waitForText("Tell Jellyfin what you watched")
        waitForText("Den") // the fake server's name
        shot("j08-settings-jellyfin")
    }

    companion object {
        lateinit var jellyfin: JellyfinFixtureServer

        @JvmStatic
        @BeforeClass
        fun freshInstall() {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            ctx.deleteDatabase("clickarr.db")
            ctx.noBackupFilesDir.listFiles()?.forEach { it.deleteRecursively() }
            File(ctx.filesDir, "datastore").deleteRecursively()
            jellyfin = JellyfinFixtureServer()
        }

        @JvmStatic
        @AfterClass
        fun stopServer() {
            jellyfin.close()
        }
    }
}
