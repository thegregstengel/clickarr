package net.clickarr.provider.jellyfin

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement
import net.clickarr.core.common.ClickarrError
import net.clickarr.core.common.Log
import net.clickarr.core.common.Outcome
import net.clickarr.provider.api.ClientIdentity
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Jellyfin-flavoured HTTP: the `Authorization: MediaBrowser …` header on every request (with the token once signed
 * in), JSON in and out, typed failures. Only subtitle and theme URLs handed to the player carry the token as a query
 * parameter, because the player fetches those without our headers.
 */
internal class JellyfinHttp(
    private val client: OkHttpClient,
    private val identity: ClientIdentity,
    private val token: () -> String?,
) {
    fun authorization(): String = buildString {
        append("MediaBrowser Client=\"Clickarr\"")
        append(", Device=\"").append(clean(identity.deviceName)).append('"')
        append(", DeviceId=\"").append(clean(identity.deviceId.value)).append('"')
        append(", Version=\"").append(clean(identity.appVersion)).append('"')
        token()?.let { append(", Token=\"").append(it).append('"') }
    }

    fun headers(): Map<String, String> = mapOf("Authorization" to authorization(), "Accept" to "application/json")

    suspend fun <T> get(url: HttpUrl, strategy: DeserializationStrategy<T>): Outcome<T> = call("GET", url, null, strategy)

    suspend fun <T> post(url: HttpUrl, body: JsonElement?, strategy: DeserializationStrategy<T>): Outcome<T> =
        call("POST", url, body, strategy)

    /** A call where only success matters: playback reports, marking played, stopping a transcode. */
    suspend fun touch(url: HttpUrl, method: String, body: JsonElement? = null): Outcome<Unit> = withContext(Dispatchers.IO) {
        try {
            client.newCall(request(method, url, body)).execute().use { r ->
                if (r.isSuccessful) Outcome.Success(Unit) else Outcome.Failure(errorFor(r.code, url))
            }
        } catch (e: IOException) {
            Outcome.Failure(ClickarrError.Unreachable("Cannot reach ${url.host}", e))
        }
    }

    private suspend fun <T> call(method: String, url: HttpUrl, body: JsonElement?, strategy: DeserializationStrategy<T>): Outcome<T> =
        withContext(Dispatchers.IO) {
            try {
                client.newCall(request(method, url, body)).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    if (!r.isSuccessful) {
                        Log.w(TAG) { "$method ${url.encodedPath} -> ${r.code}" }
                        return@withContext Outcome.Failure(errorFor(r.code, url))
                    }
                    try {
                        Outcome.Success(jellyfinJson.decodeFromString(strategy, text))
                    } catch (e: Exception) {
                        Log.e(TAG, e) { "Unparseable response from ${url.encodedPath}" }
                        Outcome.Failure(ClickarrError.Unknown("Unexpected response from Jellyfin", e))
                    }
                }
            } catch (e: IOException) {
                Outcome.Failure(ClickarrError.Unreachable("Cannot reach ${url.host}", e))
            }
        }

    private fun request(method: String, url: HttpUrl, body: JsonElement?): Request {
        val b = Request.Builder().url(url)
        headers().forEach { (k, v) -> b.header(k, v) }
        val payload = (body?.toString() ?: "").toRequestBody(JSON)
        when (method) {
            "POST" -> b.post(payload)
            "DELETE" -> b.delete()
        }
        return b.build()
    }

    private fun errorFor(code: Int, url: HttpUrl): ClickarrError = when (code) {
        401, 403 -> ClickarrError.Unauthorized("Jellyfin refused the sign-in for ${url.host}")
        404 -> ClickarrError.NotFound("Jellyfin has no ${url.encodedPath}")
        else -> ClickarrError.Unknown("Jellyfin returned HTTP $code for ${url.encodedPath}")
    }

    companion object {
        const val TAG = "JellyfinHttp"
        private val JSON = "application/json".toMediaType()

        /** Header values must be plain ASCII; quotes and commas would break the MediaBrowser field list. */
        internal fun clean(value: String): String =
            value.map { c -> if (c.code in 0x20..0x7e && c != '"' && c != ',') c else '_' }.joinToString("").ifBlank { "TV" }

        /** [path] under [base], keeping a base path such as `/jellyfin` behind a reverse proxy. */
        fun url(base: String, path: String, vararg query: Pair<String, String?>): HttpUrl {
            val root = base.trimEnd('/').toHttpUrl()
            return root.newBuilder().encodedPath(root.encodedPath.trimEnd('/') + path).apply {
                query.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) }
            }.build()
        }
    }
}
