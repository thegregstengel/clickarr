package net.clickarr.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import net.clickarr.BuildConfig
import net.clickarr.core.common.Clock
import net.clickarr.core.common.OffsetClock
import net.clickarr.data.HouseholdClockOffset
import net.clickarr.core.database.ClickarrDatabase
import net.clickarr.core.scheduling.CyclicLineupStrategy
import net.clickarr.core.scheduling.ScheduleStrategy
import net.clickarr.core.secrets.SecretStore
import net.clickarr.data.DevicePrefs
import net.clickarr.provider.api.ClientIdentity
import net.clickarr.playback.media3.DeviceProfiles
import net.clickarr.provider.api.DeviceProfile
import net.clickarr.provider.plex.PlexAuth
import net.clickarr.provider.plex.PlexProviderFactory
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): ClickarrDatabase = ClickarrDatabase.build(context)

    @Provides
    @Singleton
    fun secrets(@ApplicationContext context: Context): SecretStore = SecretStore(context)

    @Provides
    @Singleton
    fun prefs(@ApplicationContext context: Context): DevicePrefs = DevicePrefs(context)

    @Provides
    @Singleton
    fun clock(): Clock = OffsetClock(Clock.System) { HouseholdClockOffset.offsetMs }

    @Provides
    @Singleton
    fun strategy(): ScheduleStrategy = CyclicLineupStrategy()

    /** What this device can decode and show, probed once at startup (proposal 10.5). */
    @Provides
    @Singleton
    fun deviceProfile(@ApplicationContext context: Context): DeviceProfile = DeviceProfiles.probe(context)

    @Provides
    @Singleton
    fun identity(prefs: DevicePrefs): ClientIdentity =
        ClientIdentity(prefs.deviceId, runBlocking { prefs.deviceNameNow() }, BuildConfig.VERSION_NAME)

    @Provides
    @Singleton
    fun plexFactory(okHttp: OkHttpClient, identity: ClientIdentity): PlexProviderFactory = PlexProviderFactory(okHttp, identity)

    @Provides
    fun plexAuth(okHttp: OkHttpClient, identity: ClientIdentity): PlexAuth = PlexAuth(okHttp, identity)

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
