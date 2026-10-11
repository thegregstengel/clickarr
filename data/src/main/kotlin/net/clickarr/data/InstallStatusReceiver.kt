package net.clickarr.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.clickarr.core.common.Log

/** What the system installer has said about the update in progress (Settings, About). */
object InstallEvents {
    sealed interface Status {
        data object Idle : Status

        /** The session is committed; the system has not answered yet. */
        data object Started : Status

        /** The system is asking the viewer to confirm, on its own screen. */
        data object Confirming : Status

        data object Installed : Status

        data class Failed(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    fun post(status: Status) {
        _status.value = status
    }
}

/**
 * Target of the package-installer session's callback. A pending-user-action status carries the system's
 * confirmation screen, which this opens; any other status is the verdict, turned into a sentence the
 * About pane can show (a key mismatch, a blocked source, a cancelled dialog).
 */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        Log.i(TAG) { "installer status $status $detail" }
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> confirm(context, intent)
            PackageInstaller.STATUS_SUCCESS -> InstallEvents.post(InstallEvents.Status.Installed)
            else -> InstallEvents.post(InstallEvents.Status.Failed(explain(status, detail)))
        }
    }

    private fun confirm(context: Context, intent: Intent) {
        val screen = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        if (screen == null) {
            InstallEvents.post(InstallEvents.Status.Failed("The installer asked for confirmation but did not say how."))
            return
        }
        runCatching { context.startActivity(screen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onSuccess { InstallEvents.post(InstallEvents.Status.Confirming) }
            .onFailure { InstallEvents.post(InstallEvents.Status.Failed("Could not open the installer: ${it.message}")) }
    }

    private fun explain(status: Int, detail: String): String {
        val reason = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "The install was cancelled."
            PackageInstaller.STATUS_FAILURE_BLOCKED ->
                "The TV blocked the install. Allow installs from Clickarr in the TV's settings " +
                    "(Fire TV: My Fire TV, Developer options, Install unknown apps), then press Install again."
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "This build is signed with a different key than the Clickarr on this TV, so the system refuses to " +
                    "update over it. Uninstall Clickarr and install once from clickarr.net/nightly; after that, " +
                    "updates install in place."
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This build is not compatible with this TV."
            PackageInstaller.STATUS_FAILURE_INVALID -> "The downloaded file was rejected as invalid."
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install."
            else -> "The installer failed."
        }
        return if (detail.isBlank()) reason else "$reason ($detail)"
    }

    companion object {
        private const val TAG = "Updates"
        const val ACTION = "net.clickarr.INSTALL_STATUS"
    }
}
