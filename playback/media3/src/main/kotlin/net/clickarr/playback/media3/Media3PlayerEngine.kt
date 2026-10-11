package net.clickarr.playback.media3

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import net.clickarr.core.common.Log
import net.clickarr.core.common.Redact
import net.clickarr.playback.core.PlayerEngine
import net.clickarr.playback.core.PlayerEvent
import net.clickarr.provider.api.PlaybackSource
import okhttp3.OkHttpClient

/**
 * ExoPlayer behind the [PlayerEngine] interface. All methods must be called on the main thread,
 * which is where the TuneController's scope runs in the app. One instance lives for the whole
 * player screen so codecs are not re-initialized on every channel change (proposal 10.1).
 */
@OptIn(UnstableApi::class)
class Media3PlayerEngine(context: Context, okHttp: OkHttpClient, userAgent: String) : PlayerEngine {
    private val dataSourceFactory = OkHttpDataSource.Factory(okHttp).setUserAgent(userAgent)
    private val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory))
        .build()

    /** For PlayerView. Exposed as the common Player type so UI modules need no ExoPlayer dependency. */
    val player: Player get() = exo

    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<PlayerEvent> = _events

    init {
        player.addListener(
            object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    _events.tryEmit(PlayerEvent.FirstFrame)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) _events.tryEmit(PlayerEvent.Ended)
                }

                override fun onTracksChanged(tracks: Tracks) = pickTextTrackIfNeeded(tracks)

                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, error) { "ExoPlayer error ${error.errorCodeName}" }
                    _events.tryEmit(PlayerEvent.Error(error.errorCodeName, recoverable = error.errorCode in RECOVERABLE))
                }
            },
        )
    }

    private var loaded: PlaybackSource? = null
    private var captionsOn = false

    override fun load(source: PlaybackSource) {
        dataSourceFactory.setDefaultRequestProperties(source.headers)
        loaded = source
        val item = MediaItem.Builder()
            .setUri(source.url)
            .apply {
                when (source) {
                    is PlaybackSource.Hls -> setMimeType(MimeTypes.APPLICATION_M3U8)
                    is PlaybackSource.DirectPlay -> {
                        source.mimeType?.let(::setMimeType)
                        setSubtitleConfigurations(
                            source.subtitles.map { s ->
                                MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(s.url))
                                    .setMimeType(s.mimeType)
                                    .setLanguage(s.language)
                                    .setLabel(s.label)
                                    .build()
                            },
                        )
                    }
                }
            }
            .build()
        Log.d(TAG) { "load ${Redact.apply(source.url)} at ${source.startAt}" }
        player.setMediaItem(item, source.startAt.inWholeMilliseconds)
        player.prepare()
    }

    override fun play() {
        player.playWhenReady = true
    }

    override fun pause() {
        player.playWhenReady = false
    }

    override fun stop() {
        player.stop()
        player.clearMediaItems()
    }

    override fun seekTo(position: Duration) = player.seekTo(position.inWholeMilliseconds)

    override fun setCaptions(enabled: Boolean, language: String?): Boolean {
        captionsOn = enabled
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
            .setPreferredTextLanguage(language)
            .setSelectUndeterminedTextLanguage(true)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()
        if (enabled) pickTextTrackIfNeeded(player.currentTracks)
        // Burned-in captions are part of the picture; only a fresh transcode can add or remove them.
        return loaded !is PlaybackSource.Hls
    }

    /** ExoPlayer leaves text off when no track matches the language; with captions on, any track beats none. */
    private fun pickTextTrackIfNeeded(tracks: Tracks) {
        if (!captionsOn) return
        val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
        if (text.isEmpty() || text.any { it.isSelected }) return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(text.first().mediaTrackGroup, 0))
            .build()
    }

    override fun position(): Duration? = if (player.mediaItemCount == 0) null else player.currentPosition.milliseconds

    override val isPlaying: Boolean get() = player.isPlaying

    fun release() = player.release()

    /** 0 to 1; the promo's theme music sits under the picture rather than at full volume. */
    fun setVolume(volume: Float) {
        player.volume = volume.coerceIn(0f, 1f)
    }

    companion object {
        private const val TAG = "Media3"
        private val RECOVERABLE = setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
        )
    }
}
