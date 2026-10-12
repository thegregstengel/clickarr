package net.clickarr.provider.jellyfin

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.clickarr.core.common.ClickarrError
import net.clickarr.core.common.Outcome
import net.clickarr.provider.api.ClientIdentity
import net.clickarr.provider.api.ProviderCredentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * Jellyfin sign-in (ADR 0022). Quick Connect: the TV shows a code, the viewer approves it in a Jellyfin app or the web
 * UI, and the TV trades the secret for a token. Or username and password, sent once; only the token is kept.
 */
class JellyfinAuth(client: OkHttpClient, identity: ClientIdentity) {
    private val http = JellyfinHttp(client, identity) { null }

    /** A Quick Connect request in progress: show [code], keep [secret] to poll with. */
    data class QuickConnect(val code: String, val secret: String) {
        override fun toString() = "QuickConnect(code=$code, <secret>)"
    }

    /** A signed-in user on a server. */
    data class SignIn(val userName: String, val credentials: ProviderCredentials)

    suspend fun quickConnectEnabled(baseUrl: String): Outcome<Boolean> =
        http.get(JellyfinHttp.url(baseUrl, "/QuickConnect/Enabled"), Boolean.serializer())

    suspend fun startQuickConnect(baseUrl: String): Outcome<QuickConnect> =
        http.post(JellyfinHttp.url(baseUrl, "/QuickConnect/Initiate"), null, JfQuickConnect.serializer())
            .map { QuickConnect(it.code, it.secret) }

    /** True once the viewer has approved the code. */
    suspend fun approved(baseUrl: String, request: QuickConnect): Outcome<Boolean> =
        http.get(JellyfinHttp.url(baseUrl, "/QuickConnect/Connect", "secret" to request.secret), JfQuickConnect.serializer())
            .map { it.authenticated }

    suspend fun finishQuickConnect(baseUrl: String, request: QuickConnect): Outcome<SignIn> =
        http.post(
            JellyfinHttp.url(baseUrl, "/Users/AuthenticateWithQuickConnect"),
            buildJsonObject { put("Secret", request.secret) },
            JfAuthResult.serializer(),
        ).map(::signIn)

    suspend fun password(baseUrl: String, user: String, password: String): Outcome<SignIn> =
        http.post(
            JellyfinHttp.url(baseUrl, "/Users/AuthenticateByName"),
            buildJsonObject {
                put("Username", user)
                put("Pw", password)
            },
            JfAuthResult.serializer(),
        ).map(::signIn)

    private fun signIn(r: JfAuthResult) = SignIn(r.user.name, JellyfinProviderFactory.credentials(r.accessToken, r.user.id))

    companion object {
        private const val DEFAULT_PORT = 8096

        /**
         * What the viewer typed, as a server address: `jellyfin.local` becomes `http://jellyfin.local:8096`, and a full
         * URL is kept as written. Null when it cannot be an address.
         */
        fun normalize(typed: String): String? {
            val t = typed.trim().trimEnd('/')
            if (t.isEmpty()) return null
            val hasScheme = t.contains("://")
            val parsed = (if (hasScheme) t else "http://$t").toHttpUrlOrNull() ?: return null
            val explicitPort = Regex("^(?:[a-zA-Z]+://)?[^/]*:\\d+(?:/|$)").containsMatchIn(t)
            val url = if (!hasScheme && !explicitPort) parsed.newBuilder().port(DEFAULT_PORT).build() else parsed
            return url.toString().trimEnd('/')
        }

        /** For the failure screen: a wrong password reads better than "401". */
        fun explain(error: ClickarrError): String = when (error) {
            is ClickarrError.Unauthorized -> "Jellyfin did not accept that username and password."
            else -> error.message
        }
    }
}
