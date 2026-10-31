package net.clickarr.provider.jellyfin

import kotlin.time.Duration
import net.clickarr.core.common.ClickarrError
import net.clickarr.core.common.Log
import net.clickarr.core.common.Outcome
import net.clickarr.provider.api.AudioOption
import net.clickarr.provider.api.DeviceProfile
import net.clickarr.provider.api.Page
import net.clickarr.provider.api.PageOf
import net.clickarr.provider.api.PlaybackChoice
import net.clickarr.provider.api.PlaybackOptions
import net.clickarr.provider.api.PlaybackSource
import net.clickarr.provider.api.Subtitle
import net.clickarr.provider.api.VideoOption
import net.clickarr.provider.api.mimeFor
import net.clickarr.provider.api.pageTotalWhenUnknown
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Callbacks the playback decision needs from [JellyfinProvider] without growing its method count. */
internal class JellyfinPlayWire(
    val base: String,
    val token: String,
    val authHeader: String,
    val streamUrl: (mediaSourceId: String, session: String?) -> String,
)

/** Inputs for one [jellyfinDecide] call (keeps Detekt LongParameterList happy). */
internal class JellyfinDecideInput(
    val itemId: String,
    val source: JfMediaSource,
    val session: String?,
    val startAt: Duration,
    val chosen: JfStream?,
    val captionsOn: Boolean,
    val profile: DeviceProfile,
    val choice: PlaybackChoice?,
)

/** Direct play vs HLS decision for one PlaybackInfo media source. */
internal fun jellyfinDecide(input: JellyfinDecideInput, wire: JellyfinPlayWire): Outcome<PlaybackSource> {
    val source = input.source
    val tracks = source.streams.filter { it.type == "Subtitle" }
        .joinToString { "${it.codec}/${it.language}${if (it.isExternal) "/file" else ""}" }
    val audioPick = jellyfinAudioPick(source, input.choice)
    val headers = mapOf("Authorization" to wire.authHeader)
    if (source.supportsDirectPlay || JellyfinProfile.onlyAudioStandsInTheWay(source, input.profile)) {
        Log.i(TAG) { "direct play ${input.itemId}: ${source.container}, audio=$audioPick, subtitles [$tracks]" }
        return Outcome.Success(
            PlaybackSource.DirectPlay(
                url = wire.streamUrl(source.id, input.session),
                headers = headers,
                startAt = input.startAt,
                mimeType = mimeFor(source.container?.substringBefore(',')),
                subtitles = jellyfinSidecars(source, wire.base, wire.token),
            ),
        )
    }
    val transcode = source.transcodingUrl
        ?: return Outcome.Failure(ClickarrError.Unknown("Jellyfin can neither direct play nor transcode ${input.itemId}"))
    val burn = input.chosen?.takeIf { input.captionsOn }
    Log.i(TAG) {
        "transcode ${input.itemId}: ${source.container}, audio=$audioPick, subtitles [$tracks], " +
            "captions ${if (!input.captionsOn) "off" else "on, burning ${burn?.let { "${it.index} ${it.codec}" } ?: "nothing"}"}"
    }
    val hls = jellyfinHlsUrl(wire.base, transcode, burn, audioPick)
    return Outcome.Success(
        PlaybackSource.Hls(
            url = hls.toString(),
            headers = headers,
            startAt = input.startAt,
            sessionId = input.session ?: hls.queryParameter("PlaySessionId").orEmpty(),
        ),
    )
}

internal fun jellyfinOptionsFrom(item: JfItem): PlaybackOptions {
    val sources = item.mediaSources
    val videos = sources.map { s ->
        val video = s.streams.firstOrNull { it.type == "Video" }
        val res = video?.height?.let { "${it}p" }
        val rate = s.bitrate?.let { b ->
            val kbps = (b / 1_000).toInt()
            if (kbps >= 1_000) "${kbps / 1_000}.${(kbps % 1_000) / 100} Mbps" else "$kbps kbps"
        }
        VideoOption(
            id = s.id,
            label = listOfNotNull(res, video?.codec, rate, s.container).joinToString(" · ").ifBlank { s.id },
            duration = s.runTimeTicks?.let { JellyfinMapper.ticks(it) },
        )
    }
    val audios = sources.firstOrNull()?.streams.orEmpty().filter { it.type == "Audio" }.map { s ->
        AudioOption(
            id = s.index.toString(),
            label = s.displayTitle ?: listOfNotNull(s.language, s.codec).joinToString(" · ").ifBlank { "Audio ${s.index}" },
            language = s.language,
        )
    }
    return PlaybackOptions(videos, audios)
}

internal suspend fun <T> jellyfinPage(
    http: JellyfinHttp,
    listUrl: HttpUrl,
    page: Page,
    mapper: (JfItem) -> T,
): Outcome<PageOf<T>> =
    http.get(listUrl, JfItems.serializer()).map { r ->
        val list = r.items.map(mapper)
        PageOf(list, page, r.total ?: pageTotalWhenUnknown(page, list.size))
    }

internal fun jellyfinAudioPick(source: JfMediaSource, choice: PlaybackChoice?): String? {
    choice ?: return null
    choice.audioId?.let { return it }
    val lang = choice.preferredAudioLanguage ?: return null
    return source.streams.firstOrNull { it.type == "Audio" && it.language?.equals(lang, ignoreCase = true) == true }
        ?.index?.toString()
}

internal fun jellyfinHlsUrl(base: String, transcode: String, burn: JfStream?, audioPick: String?): HttpUrl =
    (base.trimEnd('/') + transcode).toHttpUrl().newBuilder()
        .removeAllQueryParameters("SubtitleStreamIndex").removeAllQueryParameters("SubtitleMethod")
        .removeAllQueryParameters("AudioStreamIndex")
        .apply {
            if (burn != null) {
                addQueryParameter("SubtitleStreamIndex", burn.index.toString())
                addQueryParameter("SubtitleMethod", "Encode")
            }
            audioPick?.let { addQueryParameter("AudioStreamIndex", it) }
        }.build()

internal fun jellyfinSidecars(source: JfMediaSource, base: String, token: String): List<Subtitle> =
    source.streams.mapNotNull { s ->
        val delivery = s.deliveryUrl?.takeIf { s.type == "Subtitle" && s.deliveryMethod == "External" } ?: return@mapNotNull null
        val mime = JellyfinProfile.mimeType(s.codec) ?: return@mapNotNull null
        val withKey = (base.trimEnd('/') + delivery).toHttpUrl().newBuilder()
            .apply { if (!jellyfinUrlHasKey(delivery)) addQueryParameter("ApiKey", token) }.build()
        Subtitle(withKey.toString(), mime, s.language, s.displayTitle)
    }

private fun jellyfinUrlHasKey(path: String): Boolean =
    Regex("[?&](ApiKey|api_key)=", RegexOption.IGNORE_CASE).containsMatchIn(path)

private const val TAG = "Jellyfin"
