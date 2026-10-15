package net.clickarr

import android.view.KeyEvent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import net.clickarr.provider.plex.fixtures.PlexFixtureServer
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test

/**
 * The Phase 1 happy path on an emulator, end to end, against a fake Plex server running inside the
 * test process: connect manually, create a channel from a show, tune, see the overlay, open the guide.
 * Screenshots land in /sdcard/Pictures/clickarr and are uploaded by the Emulator workflow.
 */
class FirstRunFlowTest : TvFlow() {
    @Test
    fun connectCreateChannelTuneAndOpenGuide() = flow {
        // Cold start on a software-rendered emulator: Hilt graph, Room, DataStore, first Compose frame.
        waitForText("Connect to your media server", timeoutMs = 90_000)
        shot("01-setup")
        click("Enter address manually")
        waitForText("Server address and token")
        compose.onNodeWithTag("setup.url").performTextClearance()
        compose.onNodeWithTag("setup.url").performTextInput(plex.baseUrl)
        compose.onNodeWithTag("setup.token").performTextInput("fixture-token")
        shot("02-manual-entry")
        click("Connect")

        waitForText("Custom channel")
        shot("03-channels-empty")
        click("Custom channel")
        waitForText("Which library?")
        click("TV Shows")
        waitForText("What from TV Shows?")
        click("Shows")
        waitForText("The Office (US)")
        shot("04-pick-shows")
        click("The Office (US)")
        click("Continue")
        waitForText("Channel number")
        shot("05-details")
        click("Create channel")

        waitForText("Custom channel") // back on Settings, Channels
        waitForText("The Office (US)")

        // A second channel so the guide has two rows to move between.
        click("Custom channel")
        waitForText("Which library?")
        click("TV Shows")
        waitForText("What from TV Shows?")
        click("Shows")
        waitForText("Parks and Recreation")
        click("Parks and Recreation")
        click("Continue")
        waitForText("Channel number")
        reach("Create channel")
        click("Create channel")
        waitForText("Custom channel")
        waitForText("Parks and Recreation")
        shot("06-channels-list")
        click("Suggest channels")
        waitForText("Create selected")
        shot("06b-suggest")
        device.pressBack()
        waitForText("Custom channel")

        // Holiday channels: Christmas from two hand-picked movies, on the shared default number 100.
        // Christmas is out of season for most of the year, so the guide steps below are unaffected.
        click("Holiday channels")
        waitForText("Valentine's Day")
        shot("06c-holidays")
        click("Christmas")
        waitForText("Christmas: what plays on it?")
        click("Selection")
        waitForText("Back to the Future")
        shot("06d-holiday-pick")
        click("Back to the Future")
        click("The Goonies")
        waitForText("2 picked")
        click("Create channel")
        waitForText("On · channel 100", timeoutMs = 30_000)
        shot("06e-holidays-on")
        device.pressBack()
        waitForText("Holiday: Christmas")
        // Tune from the guide: OK on the Guide tab keeps focus in the row; Down enters on the current program.
        click("Guide")
        waitForText("Today")
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.waitForIdle()
        device.pressDPadCenter()

        waitForText("THE OFFICE (US)", timeoutMs = 30_000)
        waitForText("S1E", timeoutMs = 30_000) // the schedule resolved to an episode
        shot("07-player-overlay")

        // Right opens the mini-guide for this channel; Back closes it; Menu opens the full guide.
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitForText("Now")
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.waitForIdle()
        shot("07b-mini-guide")
        device.pressBack()
        compose.waitForIdle()
        device.pressKeyCode(KeyEvent.KEYCODE_MENU)
        waitForText("Today")
        shot("08-guide")
        // Down moves to the program on channel 3 that overlaps the focused time (design language 2.8).
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.waitForIdle()
        Thread.sleep(SHOT_SETTLE_MS)
        shot("08b-guide-down")
        // Right moves to the next program, which is in the future; OK on it opens the details card.
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.waitForIdle()
        device.pressDPadCenter()
        waitForText("Tune to channel")
        shot("08c-guide-details")
        click("Close")

        openSettings()
        waitForText("This TV")
        shot("09-settings-general")
        click("Plex")
        waitForText("Disconnect and sign in again")
        waitForText("Tell Plex what you watched")
        shot("10-settings-server")
        click("Playback")
        waitForText("Between programs")
        waitForText("Captions")
        reach("Never") // the still-watching choices, at the bottom of the pane
        waitForText("Sleep timer")
        shot("10b-settings-playback")
        click("Diagnostics")
        waitForText("scheduler version")
        shot("11-settings-diagnostics")
        click("About")
        waitForText("MIT licensed")
        shot("12-settings-about")
        click("General")
        waitForText("Theme")
        click("Theme") // opens the picker
        click("Dracula")
        waitForText("Dracula") // the row shows the new value once the picker closes
        shot("12b-general-dracula")
        click("Theme")
        click("Clickarr") // back to the default palette for the rest of the run
        waitForText("Appearance")

        // Settings lock: set 1234 (twice), leave, come back to the gate, unlock, remove the code again.
        click("Set a code")
        repeat(2) { listOf(1, 2, 3, 4).forEach(::clickKey) }
        waitForText("Code set. Settings will ask for it from now on.")
        shot("12c-lock-set")
        click("Guide")
        waitForText("Today")
        openSettings()
        waitForText("Settings are locked")
        shot("12d-lock-gate")
        listOf(1, 2, 3, 4).forEach(::clickKey)
        waitForText("This TV")
        click("Remove code")
        waitForText("Code removed. Settings open freely again.")

        click("Sync")
        waitForText("Local household")
        click("Local household")
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

        // A member's edits go through the coordinator: create a channel here, see it come back in the sync.
        click("Custom channel")
        waitForText("Which library?")
        click("TV Shows")
        waitForText("What from TV Shows?")
        click("Shows")
        waitForText("The Office (US)")
        click("The Office (US)")
        click("Continue")
        waitForText("Channel number")
        reach("Create channel")
        click("Create channel")
        waitForText("Custom channel")
        waitForText("The Office (US)")
        shot("18-channel-via-coordinator")
        val created = otherTv.coordinator.state.value.channels.single { it.name == "The Office (US)" }

        // Edit it: focus the row for the preview card, open the editor, rename and renumber.
        click("The Office (US)") // a row in Settings, Channels opens the editor
        waitForText("Save changes")
        compose.onNodeWithTag("editor.name").performTextClearance()
        compose.onNodeWithTag("editor.name").performTextInput("Office Reruns")
        click("+")
        click("Comedy") // a glyph from the icon palette
        compose.onAllNodes(hasText("+", substring = false)).onFirst().requestFocus() // leave the field so the keyboard closes
        compose.waitForIdle()
        shot("19-edit-channel")
        reach("Save changes")
        click("Save changes")
        waitForText("Custom channel") // back on the Channels tab
        waitForText("Office Reruns")
        shot("20-channels-edited")
        val edited = otherTv.coordinator.state.value.channels.single { it.id == created.id }
        check(edited.name == "Office Reruns" && edited.number == created.number + 1) { "coordinator should have the edit: $edited" }
    }

    companion object {
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
