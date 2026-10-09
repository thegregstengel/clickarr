package net.clickarr.provider.plex

import net.clickarr.core.model.ProviderKind
import net.clickarr.core.model.ServerInfo
import net.clickarr.provider.api.ClientIdentity
import net.clickarr.provider.api.MediaProvider
import net.clickarr.provider.api.MediaProviderFactory
import net.clickarr.provider.api.ProviderCredentials
import okhttp3.OkHttpClient

class PlexProviderFactory(private val client: OkHttpClient, private val identity: ClientIdentity) : MediaProviderFactory {
    override val kind = ProviderKind.PLEX

    override fun create(server: ServerInfo, credentials: ProviderCredentials): MediaProvider {
        require(credentials.kind == ProviderKind.PLEX) { "Plex factory given ${credentials.kind} credentials" }
        val token = credentials.values[TOKEN] ?: error("Plex credentials have no token")
        return PlexProvider(server.providerId, server, token, identity, client)
    }

    companion object {
        const val TOKEN = "token"
        fun credentials(token: String) = ProviderCredentials(ProviderKind.PLEX, mapOf(TOKEN to token))
    }
}
