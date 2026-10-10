package net.clickarr.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.clickarr.core.common.ClickarrError
import net.clickarr.core.common.Log
import net.clickarr.core.common.Outcome
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The few Drive calls the sync needs, against the hidden per-app folder (`appDataFolder`). Files there
 * are invisible in the viewer's Drive and go away when they disconnect the app from their account.
 * The base URL is a parameter so tests can point it at a local server.
 */
class DriveAppData(
    private val okHttp: OkHttpClient,
    private val token: suspend () -> Outcome<String>,
    private val apiBase: String = "https://www.googleapis.com",
) {
    /** A file in the app folder with the revision the writer recorded in its properties. */
    data class File(val id: String, val revision: Long?)

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun find(name: String): Outcome<File?> = call {
        val q = "name = '${name.replace("'", "\\'")}'"
        val url = "$apiBase/drive/v3/files?spaces=appDataFolder&pageSize=1&fields=files(id,appProperties)&q=${encode(q)}"
        Request.Builder().url(url).get()
    }.map { body ->
        body.jsonObject["files"]?.jsonArray?.firstOrNull()?.jsonObject?.let { toFile(it) }
    }

    suspend fun download(id: String): Outcome<String> = callText { Request.Builder().url("$apiBase/drive/v3/files/$id?alt=media").get() }

    suspend fun create(name: String, content: String, revision: Long): Outcome<File> = call {
        val meta = buildJsonObject {
            put("name", name)
            putJsonArray("parents") { add(kotlinx.serialization.json.JsonPrimitive("appDataFolder")) }
            putJsonObject("appProperties") { put("revision", revision.toString()) }
        }
        Request.Builder().url("$apiBase/upload/drive/v3/files?uploadType=multipart&fields=id,appProperties").post(multipart(meta, content))
    }.map { toFile(it.jsonObject) }

    suspend fun update(id: String, content: String, revision: Long): Outcome<File> = call {
        val meta = buildJsonObject { putJsonObject("appProperties") { put("revision", revision.toString()) } }
        val url = "$apiBase/upload/drive/v3/files/$id?uploadType=multipart&fields=id,appProperties"
        Request.Builder().url(url).patch(multipart(meta, content))
    }.map { toFile(it.jsonObject) }

    suspend fun delete(id: String): Outcome<Unit> = callText { Request.Builder().url("$apiBase/drive/v3/files/$id").delete() }.map { }

    private fun multipart(meta: JsonObject, content: String): MultipartBody = MultipartBody.Builder()
        .setType("multipart/related".toMediaType())
        .addPart(MultipartBody.Part.create(meta.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())))
        .addPart(MultipartBody.Part.create(content.toRequestBody("application/json; charset=UTF-8".toMediaType())))
        .build()

    private fun toFile(o: JsonObject) = File(
        id = o.getValue("id").jsonPrimitive.content,
        revision = o["appProperties"]?.jsonObject?.get("revision")?.jsonPrimitive?.content?.toLongOrNull(),
    )

    private suspend fun call(build: () -> Request.Builder): Outcome<kotlinx.serialization.json.JsonElement> =
        callText(build).map { text -> if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text) }

    private suspend fun callText(build: () -> Request.Builder): Outcome<String> {
        val bearer = when (val t = token()) {
            is Outcome.Success -> t.value
            is Outcome.Failure -> return t
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                okHttp.newCall(build().header("Authorization", "Bearer $bearer").build()).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    when {
                        r.isSuccessful -> Outcome.Success(text)
                        r.code == HTTP_UNAUTHORIZED ->
                            Outcome.Failure(ClickarrError.Unauthorized("Google Drive sign-in expired; sign in again"))
                        r.code == HTTP_NOT_FOUND -> Outcome.Failure(ClickarrError.NotFound("The sync file is gone from Drive"))
                        else -> Outcome.Failure(ClickarrError.Unknown("Drive answered ${r.code}"))
                    }
                }
            }.getOrElse {
                Log.w(TAG, it) { "drive request failed" }
                Outcome.Failure(ClickarrError.Unreachable("Could not reach Google Drive: ${it.message}"))
            }
        }
    }

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val TAG = "Drive"
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_NOT_FOUND = 404
    }
}
