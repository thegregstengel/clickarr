package net.clickarr.provider.plex

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import net.clickarr.core.common.ClickarrError
import net.clickarr.core.common.Log
import net.clickarr.core.common.Outcome
import net.clickarr.provider.api.ClientIdentity
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Plex-flavoured HTTP: identity headers on every request, JSON by default, typed failures.
 * Tokens travel in the X-Plex-Token header here; only media and artwork URLs carry them as
 * query parameters because players and image loaders cannot send headers everywhere.
 */
internal class PlexHttp(
    private val client: OkHttpClient,
    private val identity: ClientIdentity,
    private val token: () -> String?,
) {
    fun headers(): Map<String, String> = buildMap {
        put("Accept", "application/json")
        put("X-Plex-Product", "Clickarr")
        put("X-Plex-Version", identity.appVersion)
        put("X-Plex-Client-Identifier", identity.deviceId.value)
        put("X-Plex-Platform", identity.platform)
        put("X-Plex-Device", identity.deviceName)
        put("X-Plex-Device-Name", identity.deviceName)
        put("X-Plex-Provides", "player")
        token()?.let { put("X-Plex-Token", it) }
    }

    suspend fun <T> get(url: HttpUrl, strategy: DeserializationStrategy<T>, extraHeaders: Map<String, String> = emptyMap()): Outcome<T> =
        call("GET", url, strategy, extraHeaders)

    suspend fun <T> post(url: HttpUrl, strategy: DeserializationStrategy<T>): Outcome<T> = call("POST", url, strategy, emptyMap())

    /** Fire-and-forget style call where only success matters (timeline, transcode stop). */
    suspend fun touch(url: HttpUrl): Outcome<Unit> = withContext(Dispatchers.IO) {
        try {
            client.newCall(request("GET", url, emptyMap())).execute().use { r ->
                if (r.isSuccessful) Outcome.Success(Unit) else Outcome.Failure(errorFor(r.code, url))
            }
        } catch (e: IOException) {
            Outcome.Failure(ClickarrError.Unreachable("Cannot reach ${url.host}", e))
        }
    }

    private suspend fun <T> call(
        method: String,
        url: HttpUrl,
        strategy: DeserializationStrategy<T>,
        extraHeaders: Map<String, String>,
    ): Outcome<T> = withContext(Dispatchers.IO) {
        try {
            client.newCall(request(method, url, extraHeaders)).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    Log.w(TAG) { "$method ${url.encodedPath} -> ${r.code}" }
                    return@withContext Outcome.Failure(errorFor(r.code, url))
                }
                try {
                    Outcome.Success(plexJson.decodeFromString(strategy, body))
                } catch (e: Exception) {
                    Log.e(TAG, e) { "Unparseable response from ${url.encodedPath}" }
                    Outcome.Failure(ClickarrError.Unknown("Unexpected response from Plex", e))
                }
            }
        } catch (e: IOException) {
            Outcome.Failure(ClickarrError.Unreachable("Cannot reach ${url.host}", e))
        }
    }

    private fun request(method: String, url: HttpUrl, extraHeaders: Map<String, String>): Request {
        val b = Request.Builder().url(url)
        headers().forEach { (k, v) -> b.header(k, v) }
        extraHeaders.forEach { (k, v) -> b.header(k, v) }
        if (method == "POST") b.post(okhttp3.RequestBody.create(null, ByteArray(0)))
        return b.build()
    }

    private fun errorFor(code: Int, url: HttpUrl): ClickarrError = when (code) {
        401, 403 -> ClickarrError.Unauthorized("Plex rejected the token for ${url.host}")
        404 -> ClickarrError.NotFound("Plex has no ${url.encodedPath}")
        else -> ClickarrError.Unknown("Plex returned HTTP $code for ${url.encodedPath}")
    }

    companion object {
        const val TAG = "PlexHttp"
        fun url(base: String, path: String, vararg query: Pair<String, String?>): HttpUrl =
            base.trimEnd('/').toHttpUrl().newBuilder().encodedPath(path).apply {
                query.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) }
            }.build()
    }
}
