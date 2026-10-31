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
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import net.clickarr.core.common.Log
import net.clickarr.core.common.Redact
import net.clickarr.playback.core.EngineAudioTrack
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
                    // The status and path say which side failed (a 404 on a segment is a transcode the server ended).
                    val http = generateSequence<Throwable>(error) { it.cause }
                        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                    val detail = http?.let { " HTTP ${it.responseCode} on ${it.dataSpec.uri.path}" }.orEmpty()
                    Log.w(TAG, error) { "ExoPlayer error ${error.errorCodeName}$detail" }
                    val recoverable = error.errorCode in IO_ERRORS || error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
                    _events.tryEmit(PlayerEvent.Error(error.errorCodeName, recoverable = recoverable))
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

    override fun audioTracks(): List<EngineAudioTrack> {
        if (loaded !is PlaybackSource.DirectPlay) return emptyList()
        return player.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
            .mapIndexed { i, g ->
                val f = g.mediaTrackGroup.getFormat(0)
                val id = g.mediaTrackGroup.id ?: "a$i"
                EngineAudioTrack(
                    id = id,
                    language = f.language,
                    label = f.label ?: f.language ?: "Audio ${i + 1}",
                )
            }
    }

    override fun selectAudio(trackId: String?, language: String?): Boolean {
        if (loaded is PlaybackSource.Hls) return false
        val builder = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setPreferredAudioLanguage(language)
        if (trackId != null) {
            val group = player.currentTracks.groups.firstOrNull {
                it.type == C.TRACK_TYPE_AUDIO && it.isSupported && (it.mediaTrackGroup.id ?: "") == trackId
            }
            if (group != null) builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
        }
        player.trackSelectionParameters = builder.build()
        return true
    }

    /** ExoPlayer leaves text off when no track matches the language; with captions on, any track beats none. */
    private fun pickTextTrackIfNeeded(tracks: Tracks) {
        if (!captionsOn) return
        val all = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (all.isNotEmpty()) {
            Log.i(TAG) {
                "text tracks: " + all.joinToString { g ->
                    val f = g.mediaTrackGroup.getFormat(0)
                    "${f.sampleMimeType}/${f.language}${if (g.isSupported) "" else " (unsupported)"}${if (g.isSelected) " *" else ""}"
                }
            }
        }
        val text = all.filter { it.isSupported }
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
        /** Media3's input and output codes (network, HTTP status, missing segment); worth reconnecting for. */
        private val IO_ERRORS = 2000..2999
    }
}
