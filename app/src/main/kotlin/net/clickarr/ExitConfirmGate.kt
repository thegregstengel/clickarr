package net.clickarr

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import net.clickarr.ui.design.ExitConfirmDialog

/**
 * Double-Back / true-root exit confirm. A root-boundary Back that navigates (player to shell, or shell to
 * player) arms a short window; a second Back in that window asks before leaving. Back that would finish the
 * activity (shell with no channels), and About's Exit, open the dialog immediately.
 */
class ExitConfirmGate {
    var open by mutableStateOf(false)
        private set
    private var armedUntilMs = 0L

    /** True when this Back was handled (dialog shown or navigation started). */
    fun onRootBack(wouldFinish: Boolean, navigate: () -> Unit): Boolean {
        if (open) {
            dismiss()
            return true
        }
        val now = SystemClock.uptimeMillis()
        if (wouldFinish || now < armedUntilMs) {
            open = true
            armedUntilMs = 0L
            return true
        }
        armedUntilMs = now + ARM_MS
        navigate()
        return true
    }

    fun requestExit() {
        open = true
        armedUntilMs = 0L
    }

    fun dismiss() {
        open = false
    }

    fun confirm(exit: () -> Unit) {
        open = false
        armedUntilMs = 0L
        exit()
    }

    companion object {
        private const val ARM_MS = 2_000L
    }
}

@Composable
fun rememberExitConfirmGate(): ExitConfirmGate = remember { ExitConfirmGate() }

@Composable
fun ExitConfirmHost(gate: ExitConfirmGate, onExit: () -> Unit) {
    if (gate.open) {
        ExitConfirmDialog(onKeepWatching = gate::dismiss, onExit = { gate.confirm(onExit) })
    }
}
