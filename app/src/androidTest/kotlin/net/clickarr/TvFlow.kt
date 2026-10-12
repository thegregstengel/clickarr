package net.clickarr

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.requestFocus
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.junit.Rule

/**
 * What every emulator walkthrough needs: the app under a Compose rule, remote-style clicks, waits, and screenshots in
 * /sdcard/Pictures/clickarr, which the Emulator workflow uploads.
 */
abstract class TvFlow {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    protected val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    protected val shots = File("/sdcard/Pictures/clickarr").apply { mkdirs() }

    protected fun shot(name: String) {
        // Let the real display catch up with the composition before capturing the frame.
        compose.waitForIdle()
        device.waitForIdle()
        Thread.sleep(SHOT_SETTLE_MS)
        device.takeScreenshot(File(shots, "$name.png"))
    }

    protected fun waitForText(text: String, timeoutMs: Long = 30_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * Compose for TV components act on D-pad key events, not synthetic taps, so performClick() does
     * nothing on them. Invoke the semantic click action when there is one; otherwise focus the node
     * and press the center key like a remote would.
     */
    protected fun click(text: String) {
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
    protected fun flow(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            runCatching { device.takeScreenshot(File(shots, "99-failure.png")) }
            runCatching { compose.onRoot(useUnmergedTree = true).printToLog("Clickarr/Test") }
            throw t
        }
    }

    /** A key on the lock pad, found by its "Key N" description so a channel number cannot be mistaken for it. */
    protected fun clickKey(digit: Int) {
        compose.onAllNodes(hasContentDescription("Key $digit")).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    /** The settings cog has no text; focus it by tag and press OK like a remote. */
    protected fun openSettings() {
        compose.onNodeWithTag("shell.settings").requestFocus()
        compose.waitForIdle()
        device.pressDPadCenter()
        compose.waitForIdle()
    }

    /** Focus a control the way a remote would, which scrolls it into view, and check it is really on screen. */
    protected fun reach(text: String) {
        val node = compose.onAllNodes(hasText(text, substring = false)).onFirst()
        node.requestFocus()
        compose.waitForIdle()
        node.assertIsDisplayed()
    }

    companion object {
        const val SHOT_SETTLE_MS = 700L
    }
}
