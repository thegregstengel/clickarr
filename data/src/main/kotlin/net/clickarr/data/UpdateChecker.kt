package net.clickarr.data

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
 * Asks GitHub for the latest release. Manual only, from Settings, About: the app never phones home
 * on its own, and installing is still the viewer's job (Downloader or sideload).
 */
@Singleton
class UpdateChecker @Inject constructor(private val okHttp: OkHttpClient) {
    data class Latest(val version: String, val url: String)

    suspend fun latest(): Outcome<Latest> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(LATEST_URL).header("Accept", "application/vnd.github+json").build()
        runCatching {
            okHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("GitHub answered ${response.code}")
                val body = Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                val tag = body.getValue("tag_name").jsonPrimitive.content.removePrefix("v")
                val url = body["html_url"]?.jsonPrimitive?.content ?: RELEASES_URL
                Latest(tag, url)
            }
        }.fold(
            onSuccess = { Outcome.Success(it) },
            onFailure = {
                Log.w(TAG, it) { "update check failed" }
                Outcome.Failure(ClickarrError.Unreachable("Could not reach GitHub: ${it.message}"))
            },
        )
    }

    companion object {
        private const val TAG = "Updates"
        const val RELEASES_URL = "https://github.com/thegregstengel/clickarr/releases"
        private const val LATEST_URL = "https://api.github.com/repos/thegregstengel/clickarr/releases/latest"

        /** True when [latest] is a higher release than [current]; suffixes like -dev or -debug are ignored. */
        fun isNewer(latest: String, current: String): Boolean {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(latest)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
