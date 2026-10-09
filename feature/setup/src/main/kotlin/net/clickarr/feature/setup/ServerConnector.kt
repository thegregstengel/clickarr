package net.clickarr.feature.setup

import net.clickarr.core.common.Outcome
import net.clickarr.provider.api.MediaProvider
import net.clickarr.provider.plex.PlexServerCandidate

/**
 * What the setup feature needs from the app. The app's ProviderRegistry implements it; keeping the
 * feature module ignorant of Room and the secret store keeps it small and testable.
 */
interface ServerConnector {
    suspend fun connectPlex(candidate: PlexServerCandidate): Outcome<MediaProvider>
    suspend fun connectPlexManual(baseUrl: String, token: String): Outcome<MediaProvider>
}
