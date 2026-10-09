package net.clickarr.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import net.clickarr.core.common.Outcome
import net.clickarr.data.ProviderRegistry
import net.clickarr.feature.setup.ServerConnector
import net.clickarr.provider.api.MediaProvider
import net.clickarr.provider.plex.PlexServerCandidate

/** Adapts the app's registry to the small interfaces feature modules declare. */
class RegistryConnector @Inject constructor(private val registry: ProviderRegistry) : ServerConnector {
    override suspend fun connectPlex(candidate: PlexServerCandidate): Outcome<MediaProvider> = registry.addPlex(candidate)

    override suspend fun connectPlexManual(baseUrl: String, token: String): Outcome<MediaProvider> =
        registry.addPlexManual(baseUrl, token)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds
    abstract fun serverConnector(impl: RegistryConnector): ServerConnector
}
