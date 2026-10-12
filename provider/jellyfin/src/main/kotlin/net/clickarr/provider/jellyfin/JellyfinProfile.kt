package net.clickarr.provider.jellyfin

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.clickarr.provider.api.CaptionRequest
import net.clickarr.provider.api.DeviceProfile

/**
 * Clickarr's [DeviceProfile] in the shape Jellyfin's playback decision reads, so the server direct-plays what the TV
 * can decode and transcodes the rest to HLS. Text subtitles go to the player as separate files; image subtitles
 * (PGS, DVD) cannot, so the server burns them in.
 */
internal object JellyfinProfile {
    private const val DEFAULT_MAX_BITRATE = 120_000_000
    private const val BITS_PER_KBIT = 1_000

    fun maxBitrate(profile: DeviceProfile): Int = profile.maxBitrateKbps?.let { it * BITS_PER_KBIT } ?: DEFAULT_MAX_BITRATE

    fun of(profile: DeviceProfile): JsonObject = buildJsonObject {
        put("Name", "Clickarr")
        put("MaxStreamingBitrate", maxBitrate(profile))
        putJsonArray("DirectPlayProfiles") {
            addJsonObject {
                put("Type", "Video")
                put("Container", containers(profile).joinToString(","))
                put("VideoCodec", videoCodecs(profile).joinToString(","))
                put("AudioCodec", profile.audioCodecs.joinToString(","))
            }
        }
        putJsonArray("TranscodingProfiles") {
            addJsonObject {
                put("Type", "Video")
                put("Container", "ts")
                put("Protocol", "hls")
                put("Context", "Streaming")
                put("VideoCodec", if (profile.supportsHevc) "hevc,h264" else "h264")
                put("AudioCodec", "aac,ac3,eac3".split(',').filter { it in profile.audioCodecs || it == "aac" }.joinToString(","))
                put("BreakOnNonKeyFrames", true)
            }
        }
        putJsonArray("CodecProfiles") {
            addJsonObject {
                put("Type", "Video")
                putJsonArray("Conditions") {
                    add(condition("Width", profile.maxWidth))
                    add(condition("Height", profile.maxHeight))
                }
            }
        }
        putJsonArray("SubtitleProfiles") {
            TEXT.forEach { f -> add(subtitle(f, "External")) }
            IMAGE.forEach { f -> add(subtitle(f, "Encode")) }
        }
    }

    /** The body of `POST /Items/{id}/PlaybackInfo`. -1 asks for no subtitles, so a server default is not burned in. */
    fun playbackInfoBody(userId: String, profile: DeviceProfile, startTicks: Long, subtitleIndex: Int?): JsonObject = buildJsonObject {
        put("UserId", userId)
        put("StartTimeTicks", startTicks)
        put("MaxStreamingBitrate", maxBitrate(profile))
        put("EnableDirectPlay", true)
        put("EnableDirectStream", false)
        put("EnableTranscoding", true)
        put("AutoOpenLiveStream", false)
        put("SubtitleStreamIndex", subtitleIndex ?: -1)
        putJsonObject("DeviceProfile") { of(profile).forEach { (k, v) -> put(k, v) } }
    }

    /** The subtitle stream to show for [captions]: the TV's language first, then any, text before image. */
    fun choose(streams: List<JfStream>, captions: CaptionRequest?): JfStream? {
        captions ?: return null
        val subs = streams.filter { it.type == "Subtitle" }
        val lang = captions.language?.lowercase()
        val inLanguage = subs.filter { lang != null && it.language?.lowercase() == lang }
        return (inLanguage.ifEmpty { subs }).sortedBy { if (it.isText) 0 else 1 }.firstOrNull()
    }

    /**
     * Jellyfin judges direct play on the file's default audio track only. When that track is the sole reason to
     * transcode (TrueHD or DTS on a TV without it) and another track is one the TV decodes, the player picks that
     * track itself, so the file plays as it is instead of being re-encoded.
     */
    fun onlyAudioStandsInTheWay(source: JfMediaSource, profile: DeviceProfile): Boolean {
        val reasons = source.transcodingUrl?.let { Regex("[?&]TranscodeReasons=([^&]*)").find(it)?.groupValues?.get(1) }
            ?.split(",", "%2C")?.filter { it.isNotBlank() } ?: return false
        val playableAudio = source.streams.any { it.type == "Audio" && it.codec?.lowercase() in profile.audioCodecs }
        return reasons == listOf("AudioCodecNotSupported") && playableAudio
    }

    fun mimeType(codec: String?): String? = when (codec?.lowercase()) {
        "srt", "subrip" -> "application/x-subrip"
        "vtt", "webvtt" -> "text/vtt"
        "ass", "ssa" -> "text/x-ssa"
        else -> null
    }

    private fun subtitle(format: String, method: String) = buildJsonObject {
        put("Format", format)
        put("Method", method)
    }

    private fun condition(property: String, max: Int) = buildJsonObject {
        put("Condition", "LessThanEqual")
        put("Property", property)
        put("Value", JsonPrimitive(max.toString()))
        put("IsRequired", false)
    }

    /** Jellyfin's names: `ts` for MPEG-TS, `dts` rather than Plex's `dca`. */
    private fun containers(p: DeviceProfile): List<String> =
        p.containers.flatMap { if (it == "mpegts") listOf("ts", "mpegts") else listOf(it) }.distinct()

    private fun videoCodecs(p: DeviceProfile): List<String> =
        (p.videoCodecs + if (p.supportsHevc) setOf("hevc") else emptySet()).toList()

    private val TEXT = listOf("srt", "subrip", "vtt", "webvtt", "ass", "ssa")
    private val IMAGE = listOf("pgssub", "pgs", "dvdsub", "dvbsub", "sup")
}
