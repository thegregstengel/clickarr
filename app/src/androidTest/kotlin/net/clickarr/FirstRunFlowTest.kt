package net.clickarr

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
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
        // Let the real display catch up with the composition before capturing the frame.
        compose.waitForIdle()
        device.waitForIdle()
        Thread.sleep(SHOT_SETTLE_MS)
        device.takeScreenshot(File(shots, "$name.png"))
    }

    private fun waitForText(text: String, timeoutMs: Long = 30_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * Compose for TV components act on D-pad key events, not synthetic taps, so performClick() does
     * nothing on them. Invoke the semantic click action when there is one; otherwise focus the node
     * and press the center key like a remote would.
     */
    private fun click(text: String) {
        val node: SemanticsNodeInteraction = compose.onAllNodes(hasText(text, substring = false)).onFirst()
        val hasOnClick = node.fetchSemanticsNode().config.contains(SemanticsActions.OnClick)
        if (hasOnClick) {
            node.performSemanticsAction(SemanticsActions.OnClick)
        } else {
            node.requestFocus()
            compose.waitForIdle()
            device.pressDPadCenter()
        }
        compose.waitForIdle()
    }

    /** Runs the flow; on failure, screenshots the live screen and logs the semantics tree before teardown. */
    private fun flow(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            runCatching { device.takeScreenshot(File(shots, "99-failure.png")) }
            runCatching { compose.onRoot(useUnmergedTree = true).printToLog("Clickarr/Test") }
            throw t
        }
    }

    @Test
    fun connectCreateChannelTuneAndOpenGuide() = flow {
        // Cold start on a software-rendered emulator: Hilt graph, Room, DataStore, first Compose frame.
        waitForText("Connect to your Plex server", timeoutMs = 90_000)
        shot("01-setup")
        click("Enter address manually")
        waitForText("Server address and token")
        compose.onNodeWithTag("setup.url").performTextClearance()
        compose.onNodeWithTag("setup.url").performTextInput(plex.baseUrl)
        compose.onNodeWithTag("setup.token").performTextInput("fixture-token")
        shot("02-manual-entry")
        click("Connect")

        waitForText("Create channel")
        shot("03-channels-empty")
        click("Create channel")
        waitForText("What goes on this channel?")
        click("Shows")
        waitForText("The Office (US)")
        shot("04-pick-shows")
        click("The Office (US)")
        click("Continue")
        waitForText("Channel number")
        shot("05-details")
        click("Create channel")

        waitForText("Create channel") // back on the Channels tab
        waitForText("The Office (US)")

        // A second channel so the guide has two rows to move between.
        click("Create channel")
        waitForText("What goes on this channel?")
        click("Shows")
        waitForText("Parks and Recreation")
        click("Parks and Recreation")
        click("Continue")
        waitForText("Channel number")
        click("Create channel")
        waitForText("Create channel")
        waitForText("Parks and Recreation")
        shot("06-channels-list")
        click("The Office (US)")

        waitForText("THE OFFICE (US)", timeoutMs = 30_000)
        waitForText("S1E", timeoutMs = 30_000) // the schedule resolved to an episode
        shot("07-player-overlay")

        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitForText("Today")
        shot("08-guide")
        // Down moves to the program on channel 3 that overlaps the focused time (design language 2.8).
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.waitForIdle()
        Thread.sleep(SHOT_SETTLE_MS)
        shot("08b-guide-down")

        click("Settings")
        waitForText("This TV")
        shot("09-settings-general")
        click("Media Server")
        waitForText("Disconnect and sign in again")
        shot("10-settings-server")
        click("Diagnostics")
        waitForText("scheduler version")
        shot("11-settings-diagnostics")
        click("About")
        waitForText("MIT licensed")
        shot("12-settings-about")

        click("Household")
        waitForText("Create a household")
        shot("13-household-none")
        click("Create a household")
        waitForText("This TV coordinates", timeoutMs = 30_000)
        click("Add a device")
        waitForText("enter this code")
        shot("14-household-pin")

        // Dissolve, then join the test process's coordinator as a member by address and PIN.
        click("Stop")
        click("Dissolve household")
        waitForText("Create a household")
        click("Join a household")
        waitForText("Households found")
        compose.onNodeWithTag("household.address").performTextInput(otherTv.baseUrl)
        compose.onNodeWithTag("household.pin").performTextInput(otherTv.pin())
        shot("15-household-join")
        click("Join")
        waitForText("Connected to household Test Home", timeoutMs = 30_000)
        shot("16-household-member")
        click("Channels")
        waitForText("Movies")
        shot("17-channels-synced")
    }

    companion object {
        private const val SHOT_SETTLE_MS = 700L
        lateinit var plex: PlexFixtureServer
        lateinit var otherTv: TestCoordinator

        @JvmStatic
        @BeforeClass
        fun freshInstall() {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            ctx.deleteDatabase("clickarr.db")
            ctx.noBackupFilesDir.listFiles()?.forEach { it.deleteRecursively() }
            File(ctx.filesDir, "datastore").deleteRecursively()
            plex = PlexFixtureServer()
            otherTv = TestCoordinator()
        }

        @JvmStatic
        @AfterClass
        fun stopServer() {
            plex.close()
            otherTv.close()
        }
    }
}
