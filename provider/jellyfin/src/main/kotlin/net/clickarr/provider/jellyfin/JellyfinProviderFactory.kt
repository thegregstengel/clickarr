package net.clickarr.provider.jellyfin

import net.clickarr.core.model.ProviderKind
import net.clickarr.core.model.ServerInfo
import net.clickarr.provider.api.ClientIdentity
import net.clickarr.provider.api.MediaProvider
import net.clickarr.provider.api.MediaProviderFactory
import net.clickarr.provider.api.ProviderCredentials
import okhttp3.OkHttpClient

class JellyfinProviderFactory(private val client: OkHttpClient, private val identity: ClientIdentity) : MediaProviderFactory {
    override val kind = ProviderKind.JELLYFIN

    override fun create(server: ServerInfo, credentials: ProviderCredentials): MediaProvider {
        require(credentials.kind == ProviderKind.JELLYFIN) { "Jellyfin factory given ${credentials.kind} credentials" }
        val token = credentials.values[TOKEN] ?: error("Jellyfin credentials have no token")
        val userId = credentials.values[USER_ID] ?: error("Jellyfin credentials have no user")
        return JellyfinProvider(server.providerId, server, token, userId, identity, client)
    }

    companion object {
        const val TOKEN = "token"
        const val USER_ID = "userId"
        fun credentials(token: String, userId: String) =
            ProviderCredentials(ProviderKind.JELLYFIN, mapOf(TOKEN to token, USER_ID to userId))
    }
}
