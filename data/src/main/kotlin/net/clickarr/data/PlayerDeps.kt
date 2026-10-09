package net.clickarr.data

import javax.inject.Inject
import javax.inject.Singleton
import net.clickarr.core.common.Clock
import net.clickarr.core.scheduling.ScheduleStrategy
import net.clickarr.provider.api.DeviceProfile
import net.clickarr.provider.api.MediaProvider
import okhttp3.OkHttpClient

/** Everything a player screen needs from the app, in one injectable bundle. */
@Singleton
class PlayerDeps @Inject constructor(
    val strategy: ScheduleStrategy,
    val channels: ChannelRepository,
    val registry: ProviderRegistry,
    val profile: DeviceProfile,
    val clock: Clock,
    val prefs: DevicePrefs,
    val okHttp: OkHttpClient,
) {
    fun provider(id: net.clickarr.core.model.ProviderId): MediaProvider? = registry.get(id)
}
