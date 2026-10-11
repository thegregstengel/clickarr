package net.clickarr.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.clickarr.core.common.ClickarrError
import net.clickarr.core.common.Log
import net.clickarr.core.common.Outcome
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Updates from GitHub releases, on request only (Settings, About); the app never checks on
 * its own. CI publishes a `version.json` next to each APK with the version code, SHA-256, and the signing
 * certificate's digest, so the app can tell newer from older, verify the download, and say up front when a
 * build is signed with a key the system would refuse to update over.
 */
@Singleton
class UpdateChecker @Inject constructor(@ApplicationContext private val context: Context, private val okHttp: OkHttpClient) {
    /** A published build on the chosen channel. [signer] is the certificate SHA-256, or empty when CI did not say. */
    data class Latest(
        val channel: String,
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val signer: String,
    )

    suspend fun latest(channel: String): Outcome<Latest> = withContext(Dispatchers.IO) {
        val base = if (channel == CHANNEL_RELEASE) RELEASE_BASE else NIGHTLY_BASE
        runCatching {
            okHttp.newCall(Request.Builder().url("$base/version.json").build()).execute().use { response ->
                if (!response.isSuccessful) error("GitHub answered ${response.code}")
                val body = Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                Latest(
                    channel = channel,
                    versionCode = body.getValue("versionCode").jsonPrimitive.content.toInt(),
                    versionName = body.getValue("versionName").jsonPrimitive.content,
                    apkUrl = "$base/" + body.getValue("apk").jsonPrimitive.content,
                    sha256 = body.getValue("sha256").jsonPrimitive.content.lowercase(),
                    signer = body["signer"]?.jsonPrimitive?.content?.lowercase().orEmpty(),
                )
            }
        }.fold(
            onSuccess = { Outcome.Success(it) },
            onFailure = {
                Log.w(TAG, it) { "update check failed" }
                Outcome.Failure(ClickarrError.Unreachable("Could not reach GitHub: ${it.message}"))
            },
        )
    }

    /** SHA-256 of the certificate this install was signed with, lower-case hex, or null when the system will not say. */
    fun installedSigner(): String? = runCatching {
        val pm = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        signatures?.firstOrNull()?.toByteArray()?.let(::sha256Hex)
    }.getOrNull()

    /**
     * False when [latest] carries a signer digest and it is not this install's: the system would refuse the
     * update, so the app says so before downloading. Unknown on either side counts as a match.
     */
    fun signedLikeThisInstall(latest: Latest): Boolean {
        if (latest.signer.isBlank()) return true
        val mine = installedSigner() ?: return true
        return mine == latest.signer
    }

    /** Android 8 and later ask the viewer to allow installs from each app once; until then the installer blocks. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens the TV's "install unknown apps" page for Clickarr, or the settings app when the TV has no such page. */
    fun openInstallPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val page = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        val opened = runCatching { context.startActivity(page.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (!opened) runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Downloads the APK, verifies its SHA-256, and returns the file ready for [install]. */
    suspend fun download(latest: Latest, onProgress: (Float) -> Unit = {}): Outcome<File> = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "clickarr-update.apk")
        runCatching {
            okHttp.newCall(Request.Builder().url(latest.apkUrl).build()).execute().use { response ->
                if (!response.isSuccessful) error("download answered ${response.code}")
                val body = response.body ?: error("empty download")
                val total = body.contentLength().takeIf { it > 0 }
                val digest = MessageDigest.getInstance("SHA-256")
                body.byteStream().use { input ->
                    file.outputStream().use { out ->
                        val buffer = ByteArray(BUFFER)
                        var done = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            done += n
                            if (total != null) onProgress(done.toFloat() / total)
                        }
                    }
                }
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                if (sha != latest.sha256) {
                    file.delete()
                    error("the download did not match its checksum")
                }
                file
            }
        }.fold(
            onSuccess = { Outcome.Success(it) },
            onFailure = {
                Log.w(TAG, it) { "update download failed" }
                Outcome.Failure(ClickarrError.Unknown("Download failed: ${it.message}"))
            },
        )
    }

    /**
     * Streams the verified APK into a package-installer session and commits it. The system confirms with
     * the viewer and reports back through [InstallStatusReceiver], which is where the result lands
     * ([InstallEvents.status]); a failure there names the reason, unlike a viewer intent that just closes.
     */
    suspend fun install(file: File) = withContext(Dispatchers.IO) {
        InstallEvents.post(InstallEvents.Status.Started)
        val installer = context.packageManager.packageInstaller
        val committed = runCatching {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(file.length())
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("clickarr.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val callback = Intent(context, InstallStatusReceiver::class.java).setAction(InstallStatusReceiver.ACTION)
                val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pending = PendingIntent.getBroadcast(context, sessionId, callback, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
                session.commit(pending.intentSender)
            }
        }
        committed.onFailure {
            Log.w(TAG, it) { "install session failed; handing the file to a viewer instead" }
            installWithViewer(file)
        }
        Unit
    }

    /** The older route: an ACTION_VIEW on the APK. The system shows its own dialog and tells us nothing back. */
    private fun installWithViewer(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(intent) }
            .onSuccess { InstallEvents.post(InstallEvents.Status.Confirming) }
            .onFailure { InstallEvents.post(InstallEvents.Status.Failed("The TV has no package installer to hand this to.")) }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "Updates"
        private const val BUFFER = 64 * 1024
        const val CHANNEL_NIGHTLY = "nightly"
        const val CHANNEL_RELEASE = "release"
        const val RELEASES_URL = "https://github.com/thegregstengel/clickarr/releases"
        private const val NIGHTLY_BASE = "https://github.com/thegregstengel/clickarr/releases/download/nightly"
        private const val RELEASE_BASE = "https://github.com/thegregstengel/clickarr/releases/latest/download"
    }
}
