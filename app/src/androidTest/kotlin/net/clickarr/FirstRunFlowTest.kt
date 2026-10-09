package net.clickarr

import android.view.KeyEvent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import net.clickarr.provider.plex.fixtures.PlexFixtureServer
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test

/**
 * The Phase 1 happy path on an emulator, end to end, against a fake Plex server running inside the
 * test process: connect manually, create a channel from a show, tune, see the overlay, open the guide.
 * Screenshots land in /sdcard/Pictures/clickarr and are uploaded by the Emulator workflow.
 */
class FirstRunFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val shots = File("/sdcard/Pictures/clickarr").apply { mkdirs() }

    private fun shot(name: String) {
        device.takeScreenshot(File(shots, "$name.png"))
    }

    private fun waitForText(text: String, timeoutMs: Long = 30_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun connectCreateChannelTuneAndOpenGuide() {
        // Cold start on a software-rendered emulator: Hilt graph, Room, DataStore, first Compose frame.
        waitForText("Connect to your Plex server", timeoutMs = 90_000)
        shot("01-setup")
        compose.onNodeWithText("Enter address manually").performClick()
        waitForText("Server address and token")
        compose.onNodeWithTag("setup.url").performTextClearance()
        compose.onNodeWithTag("setup.url").performTextInput(plex.baseUrl)
        compose.onNodeWithTag("setup.token").performTextInput("fixture-token")
        shot("02-manual-entry")
        compose.onNodeWithText("Connect").performClick()

        waitForText("Create channel")
        shot("03-channels-empty")
        compose.onNodeWithText("Create channel").performClick()
        waitForText("What goes on this channel?")
        compose.onNodeWithText("Shows").performClick()
        waitForText("The Office (US)")
        shot("04-pick-shows")
        compose.onNodeWithText("The Office (US)").performClick()
        compose.onNodeWithText("Continue").performClick()
        waitForText("Channel number")
        shot("05-details")
        compose.onNodeWithText("Create channel").performClick()

        waitForText("The Office (US)")
        shot("06-channels-list")
        compose.onNodeWithText("The Office (US)").performClick()

        waitForText("THE OFFICE (US)", timeoutMs = 30_000)
        shot("07-player-overlay")

        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitForText("Today")
        shot("08-guide")
    }

    companion object {
        lateinit var plex: PlexFixtureServer

        @JvmStatic
        @BeforeClass
        fun freshInstall() {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            ctx.deleteDatabase("clickarr.db")
            ctx.noBackupFilesDir.listFiles()?.forEach { it.deleteRecursively() }
            File(ctx.filesDir, "datastore").deleteRecursively()
            plex = PlexFixtureServer()
        }

        @JvmStatic
        @AfterClass
        fun stopServer() = plex.close()
    }
}
