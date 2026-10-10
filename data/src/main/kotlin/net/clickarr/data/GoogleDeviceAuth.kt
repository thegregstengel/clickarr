package net.clickarr.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.clickarr.core.common.ClickarrError
import net.clickarr.core.common.Log
import net.clickarr.core.common.Outcome
import net.clickarr.core.secrets.SecretStore
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/** The OAuth client for Google's TV and limited-input device flow. Empty in builds without one (ADR 0020). */
data class GoogleOAuthConfig(val clientId: String, val clientSecret: String) {
    val configured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()
}

/**
 * Google sign-in for a TV: the app shows a short code, the viewer enters it on a phone at google.com/device,
 * and the TV polls until Google says yes. Tokens live in the Keystore-backed [SecretStore] and are used only
 * against Drive's per-app folder and the userinfo endpoint (for the account name on screen).
 */
class GoogleDeviceAuth(private val config: GoogleOAuthConfig, private val okHttp: OkHttpClient, private val secrets: SecretStore) {
    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val intervalSec: Int,
        val expiresInSec: Int,
    )

    sealed interface Poll {
        data object Pending : Poll
        data object SlowDown : Poll
        data object Authorized : Poll
        data class Denied(val reason: String) : Poll
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun begin(): Outcome<DeviceCode> = post(
        DEVICE_CODE_URL,
        mapOf("client_id" to config.clientId, "scope" to SCOPES),
    ).map { o ->
        DeviceCode(
            deviceCode = o.str("device_code"),
            userCode = o.str("user_code"),
            verificationUrl = o["verification_url"]?.jsonPrimitive?.content ?: "https://www.google.com/device",
            intervalSec = o["interval"]?.jsonPrimitive?.content?.toIntOrNull() ?: DEFAULT_INTERVAL_SEC,
            expiresInSec = o["expires_in"]?.jsonPrimitive?.content?.toIntOrNull() ?: DEFAULT_EXPIRES_SEC,
        )
    }

    /** One poll of the token endpoint; call every [DeviceCode.intervalSec] seconds until not [Poll.Pending]. */
    suspend fun poll(code: DeviceCode): Poll {
        val r = postRaw(
            TOKEN_URL,
            mapOf(
                "client_id" to config.clientId, "client_secret" to config.clientSecret,
                "device_code" to code.deviceCode, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            ),
        )
        val body = r.second
        val error = body["error"]?.jsonPrimitive?.content
        return when {
            r.first && error == null -> {
                storeTokens(body)
                Poll.Authorized
            }
            error == "authorization_pending" -> Poll.Pending
            error == "slow_down" -> Poll.SlowDown
            else -> Poll.Denied(body["error_description"]?.jsonPrimitive?.content ?: error ?: "Google refused the sign-in")
        }
    }

    suspend fun isSignedIn(): Boolean = secrets.get(KEY_REFRESH) != null

    suspend fun email(): String? = secrets.get(KEY_EMAIL)

    /** A valid access token, refreshing it when within a minute of expiry. */
    suspend fun accessToken(): Outcome<String> {
        val cached = secrets.get(KEY_ACCESS)
        val expiry = secrets.get(KEY_EXPIRY)?.toLongOrNull() ?: 0L
        if (cached != null && System.currentTimeMillis() < expiry - EXPIRY_MARGIN_MS) return Outcome.Success(cached)
        val refresh = secrets.get(KEY_REFRESH) ?: return Outcome.Failure(ClickarrError.Unauthorized("Not signed in to Google"))
        val refreshed = post(
            TOKEN_URL,
            mapOf(
                "client_id" to config.clientId, "client_secret" to config.clientSecret,
                "refresh_token" to refresh, "grant_type" to "refresh_token",
            ),
        )
        return when (refreshed) {
            is Outcome.Failure -> refreshed
            is Outcome.Success -> {
                storeTokens(refreshed.value)
                Outcome.Success(refreshed.value.str("access_token"))
            }
        }
    }

    suspend fun signOut() {
        secrets.get(KEY_REFRESH)?.let { token ->
            val revoke = Request.Builder().url("$REVOKE_URL?token=$token").post(FormBody.Builder().build()).build()
            runCatching { withContext(Dispatchers.IO) { okHttp.newCall(revoke).execute().close() } }
        }
        listOf(KEY_REFRESH, KEY_ACCESS, KEY_EXPIRY, KEY_EMAIL).forEach { secrets.remove(it) }
    }

    /** Fetches and remembers the account's email so Settings can say who is signed in. */
    suspend fun rememberEmail(): String? {
        val token = (accessToken() as? Outcome.Success)?.value ?: return null
        val email = runCatching {
            withContext(Dispatchers.IO) {
                okHttp.newCall(Request.Builder().url(USERINFO_URL).header("Authorization", "Bearer $token").build()).execute().use {
                    json.parseToJsonElement(it.body?.string().orEmpty()).jsonObject["email"]?.jsonPrimitive?.content
                }
            }
        }.getOrNull()
        email?.let { secrets.put(KEY_EMAIL, it) }
        return email
    }

    private suspend fun storeTokens(body: JsonObject) {
        body["refresh_token"]?.jsonPrimitive?.content?.let { secrets.put(KEY_REFRESH, it) }
        body["access_token"]?.jsonPrimitive?.content?.let { secrets.put(KEY_ACCESS, it) }
        val expiresIn = body["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: DEFAULT_EXPIRES_SEC.toLong()
        secrets.put(KEY_EXPIRY, (System.currentTimeMillis() + expiresIn * MS).toString())
    }

    private suspend fun post(url: String, form: Map<String, String>): Outcome<JsonObject> {
        val (ok, body) = postRaw(url, form)
        if (ok) return Outcome.Success(body)
        val message = body["error_description"]?.jsonPrimitive?.content ?: body["error"]?.jsonPrimitive?.content ?: "Google did not answer"
        return Outcome.Failure(ClickarrError.Unreachable(message))
    }

    private suspend fun postRaw(url: String, form: Map<String, String>): Pair<Boolean, JsonObject> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).post(FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()).build()
        runCatching {
            okHttp.newCall(request).execute().use { r ->
                val text = r.body?.string().orEmpty()
                val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrDefault(JsonObject(emptyMap()))
                r.isSuccessful to obj
            }
        }.getOrElse {
            Log.w(TAG, it) { "google auth request failed" }
            false to JsonObject(emptyMap())
        }
    }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    companion object {
        private const val TAG = "GoogleAuth"
        private const val DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
        private const val USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo"
        private const val SCOPES = "https://www.googleapis.com/auth/drive.appdata email"
        private const val DEFAULT_INTERVAL_SEC = 5
        private const val DEFAULT_EXPIRES_SEC = 1800
        private const val EXPIRY_MARGIN_MS = 60_000L
        private const val MS = 1000L
        private const val KEY_REFRESH = "google:refresh"
        private const val KEY_ACCESS = "google:access"
        private const val KEY_EXPIRY = "google:expiry"
        private const val KEY_EMAIL = "google:email"
    }
}
