package net.clickarr.provider.api

import kotlin.time.Duration
import net.clickarr.core.common.Outcome
import net.clickarr.core.model.ArtworkRef
import net.clickarr.core.model.Collection
import net.clickarr.core.model.Library
import net.clickarr.core.model.MediaItem
import net.clickarr.core.model.MediaRef
import net.clickarr.core.model.Movie
import net.clickarr.core.model.Playable
import net.clickarr.core.model.Playlist
import net.clickarr.core.model.ProgrammingSource
import net.clickarr.core.model.ProviderId
import net.clickarr.core.model.ProviderKind
import net.clickarr.core.model.ServerInfo
import net.clickarr.core.model.Show

/**
 * One connected media server, speaking only normalized types. Nothing outside provider:* may
 * see a Plex (or later Jellyfin, Emby) wire object. See ADR 0007 and proposal section 5.
 *
 * Implementations are safe to call from any thread; all calls are suspending and may do I/O.
 */
interface MediaProvider {
    val id: ProviderId
    val kind: ProviderKind
    val server: ServerInfo

    /** Cheap liveness and version check. Used at startup and by the household UI. */
    suspend fun ping(): Outcome<ServerInfo>

    suspend fun libraries(): Outcome<List<Library>>

    suspend fun shows(library: MediaRef, page: Page = Page.first()): Outcome<PageOf<Show>>

    suspend fun movies(library: MediaRef, page: Page = Page.first()): Outcome<PageOf<Movie>>

    /** Every episode of a show across all seasons, in aired order. */
    suspend fun episodes(show: MediaRef): Outcome<List<net.clickarr.core.model.Episode>>

    suspend fun collections(library: MediaRef): Outcome<List<Collection>>

    suspend fun playlists(): Outcome<List<Playlist>>

    /** Batch lookup for cache refresh. Missing items are omitted, not errors. */
    suspend fun items(refs: List<MediaRef>): Outcome<List<MediaItem>>

    /**
     * Expand a programming source into concrete playables with durations, in the provider's natural
     * order (aired order for shows, release order for movies, list order for playlists and explicit picks).
     * This is the only input the scheduler ever sees from a provider (through a LineupSnapshot).
     */
    suspend fun resolve(source: ProgrammingSource): Outcome<List<Playable>>

    /** Decide how this device should play this item right now. Direct play is preferred. */
    suspend fun playbackSource(item: MediaRef, profile: DeviceProfile, startAt: Duration): Outcome<PlaybackSource>

    /** Tell the server a playback session is over so it can release transcoder resources. */
    suspend fun endPlayback(source: PlaybackSource): Outcome<Unit>

    fun artworkUrl(ref: ArtworkRef, size: ArtworkSize): String

    /** Optional. Providers that cannot report progress return success without doing anything. */
    suspend fun reportProgress(item: MediaRef, position: Duration, state: PlaybackState): Outcome<Unit>
}

data class Page(val offset: Int, val size: Int) {
    companion object {
        const val DEFAULT_SIZE = 200
        fun first(size: Int = DEFAULT_SIZE) = Page(0, size)
    }

    fun next() = Page(offset + size, size)
}

data class PageOf<T>(val items: List<T>, val page: Page, val total: Int) {
    val hasMore: Boolean get() = page.offset + items.size < total
}

enum class ArtworkSize(val width: Int, val height: Int) {
    THUMB(320, 180),
    POSTER(300, 450),
    BACKDROP(1920, 1080),
}

enum class PlaybackState { PLAYING, PAUSED, STOPPED }

/** What this device can decode. Built once at startup; never synchronized. Proposal section 10.5. */
data class DeviceProfile(
    val maxWidth: Int,
    val maxHeight: Int,
    val videoCodecs: Set<String>,
    val audioCodecs: Set<String>,
    val containers: Set<String>,
    val maxBitrateKbps: Int? = null,
    val supportsHevc: Boolean = false,
    val supportsHdr10: Boolean = false,
    val supportsDolbyVision: Boolean = false,
) {
    companion object {
        /** A conservative profile most Android TV and Fire TV devices satisfy. Used until the real one is probed. */
        val Conservative = DeviceProfile(
            maxWidth = 1920,
            maxHeight = 1080,
            videoCodecs = setOf("h264"),
            audioCodecs = setOf("aac", "mp3", "ac3", "eac3"),
            containers = setOf("mp4", "mkv"),
            maxBitrateKbps = 20_000,
        )
    }
}

sealed interface PlaybackSource {
    val url: String
    val headers: Map<String, String>

    /** The offset the player must still seek to. Zero when the server applied it (transcodes). */
    val startAt: Duration

    data class DirectPlay(
        override val url: String,
        override val headers: Map<String, String>,
        override val startAt: Duration,
        val mimeType: String?,
    ) : PlaybackSource

    data class Hls(
        override val url: String,
        override val headers: Map<String, String>,
        override val startAt: Duration,
        /** Provider-specific session handle, passed back to [MediaProvider.endPlayback]. */
        val sessionId: String,
    ) : PlaybackSource
}
