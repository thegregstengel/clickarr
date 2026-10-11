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
import net.clickarr.core.common.AppTime
import net.clickarr.core.common.Clock
import net.clickarr.core.common.OffsetClock
import net.clickarr.data.HouseholdClockOffset
import net.clickarr.core.database.ClickarrDatabase
import net.clickarr.core.scheduling.CyclicLineupStrategy
import net.clickarr.core.scheduling.ScheduleStrategy
import net.clickarr.core.secrets.SecretStore
import net.clickarr.data.DevicePrefs
import net.clickarr.data.GoogleOAuthConfig
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
    fun googleOAuth(): GoogleOAuthConfig = GoogleOAuthConfig(BuildConfig.GOOGLE_CLIENT_ID, BuildConfig.GOOGLE_CLIENT_SECRET)

    @Provides
    @Singleton
    fun clock(): Clock = OffsetClock(Clock.System) {
        val household = HouseholdClockOffset.offsetMs
        if (household != 0L) household else AppTime.networkOffsetMs
    }

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
    fun plexFactory(okHttp: OkHttpClient, identity: ClientIdentity): PlexProviderFactory =
        // The Plex client never follows a redirect: X-Plex-Token travels as a query parameter, and a
        // redirect from a server on cleartext would otherwise carry it to whatever host answered.
        PlexProviderFactory(okHttp.newBuilder().followRedirects(false).followSslRedirects(false).build(), identity)

    @Provides
    fun plexAuth(okHttp: OkHttpClient, identity: ClientIdentity): PlexAuth = PlexAuth(okHttp, identity)

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
