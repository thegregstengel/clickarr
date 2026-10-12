package net.clickarr.data

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import net.clickarr.core.model.ProviderKind
import net.clickarr.provider.api.ProviderCredentials
import org.junit.jupiter.api.Test

/** How the registry reads a stored credential: JSON since ADR 0022, a bare Plex token before it. */
class StoredCredentialsTest {
    @Test
    fun `a bare token from before ADR 0022 is still a Plex sign-in`() {
        ProviderRegistry.decodeCredentials(ProviderKind.PLEX, "abc123") shouldBe
            ProviderCredentials(ProviderKind.PLEX, mapOf("token" to "abc123"))
    }

    @Test
    fun `a bare token is never taken for a Jellyfin sign-in`() {
        ProviderRegistry.decodeCredentials(ProviderKind.JELLYFIN, "abc123").shouldBeNull()
    }

    @Test
    fun `stored JSON round-trips`() {
        val creds = ProviderCredentials(ProviderKind.JELLYFIN, mapOf("token" to "t", "userId" to "u"))
        val stored = Json.encodeToString(ProviderCredentials.serializer(), creds)
        ProviderRegistry.decodeCredentials(ProviderKind.JELLYFIN, stored) shouldBe creds
    }

    @Test
    fun `credentials for another kind of server are refused`() {
        val stored = Json.encodeToString(ProviderCredentials.serializer(), ProviderCredentials(ProviderKind.PLEX, mapOf("token" to "t")))
        ProviderRegistry.decodeCredentials(ProviderKind.JELLYFIN, stored).shouldBeNull()
    }
}
