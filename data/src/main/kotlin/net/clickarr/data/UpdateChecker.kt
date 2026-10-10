package net.clickarr.data

import android.content.Context
import android.content.Intent
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
 * Updates from GitHub releases, on request only (Settings, About and Updates); the app never checks on
 * its own. CI publishes a `version.json` next to each APK with the version code and SHA-256, so the app
 * can tell newer from older and verify what it downloaded before handing it to the system installer.
 */
@Singleton
class UpdateChecker @Inject constructor(@ApplicationContext private val context: Context, private val okHttp: OkHttpClient) {
    /** A published build on the chosen channel. */
    data class Latest(val channel: String, val versionCode: Int, val versionName: String, val apkUrl: String, val sha256: String)

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

    /** Hands the verified APK to the system installer, which asks the viewer to confirm. */
    fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    }

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
