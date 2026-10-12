package net.clickarr.feature.setup

import net.clickarr.core.common.Outcome
import net.clickarr.provider.api.MediaProvider
import net.clickarr.provider.api.ProviderCredentials

/**
 * What the setup feature needs from the app. The app's ProviderRegistry implements it; keeping the
 * feature module ignorant of Room and the secret store keeps it small and testable.
 */
fun interface ServerConnector {
    /** Try [urls] in order, keep the first that answers, and store the connection with [credentials]. */
    suspend fun connect(name: String, urls: List<String>, credentials: ProviderCredentials): Outcome<MediaProvider>
}
