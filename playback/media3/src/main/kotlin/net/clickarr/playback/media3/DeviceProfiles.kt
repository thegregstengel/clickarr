package net.clickarr.playback.media3

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.view.Display
import net.clickarr.core.common.Log
import net.clickarr.provider.api.DeviceProfile

/**
 * Builds the [DeviceProfile] from what this device can actually decode and show, so a 4K HEVC stick
 * direct plays what a 1080p stick would ask the server to transcode (proposal 10.5). Only hardware
 * decoders count: software HEVC on a TV stick stutters. Falls back to [DeviceProfile.Conservative]
 * if the probe throws, which some vendor builds do.
 */
object DeviceProfiles {
    private const val TAG = "DeviceProfile"
    private const val UHD_WIDTH = 3840
    private const val UHD_HEIGHT = 2160
    private const val UHD_BITRATE_KBPS = 40_000
    private const val HD_BITRATE_KBPS = 20_000

    /** Decoder MIME type -> the codec name Plex uses in its media metadata. */
    private val VIDEO = mapOf(
        "video/avc" to "h264",
        "video/hevc" to "hevc",
        "video/x-vnd.on2.vp9" to "vp9",
        "video/av01" to "av1",
        "video/mp4v-es" to "mpeg4",
        "video/mpeg2" to "mpeg2video",
    )
    private val AUDIO = mapOf(
        "audio/mp4a-latm" to "aac",
        "audio/mpeg" to "mp3",
        "audio/ac3" to "ac3",
        "audio/eac3" to "eac3",
        "audio/eac3-joc" to "eac3",
        "audio/vnd.dts" to "dts",
        "audio/vnd.dts.hd" to "dts",
        "audio/true-hd" to "truehd",
        "audio/flac" to "flac",
        "audio/opus" to "opus",
        "audio/vorbis" to "vorbis",
    )

    fun probe(context: Context): DeviceProfile = runCatching { probeOrThrow(context) }
        .onFailure { Log.w(TAG, it) { "probe failed; using the conservative profile" } }
        .getOrDefault(DeviceProfile.Conservative)

    private fun probeOrThrow(context: Context): DeviceProfile {
        val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        val video = decoders.filter { it.isHardware() }.flatMap { it.supportedTypes.toList() }.mapNotNull { VIDEO[it.lowercase()] }.toSet()
        val audio = decoders.flatMap { it.supportedTypes.toList() }.mapNotNull { AUDIO[it.lowercase()] }.toSet()
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display.mode
        val uhd = mode.physicalWidth >= UHD_WIDTH || mode.physicalHeight >= UHD_HEIGHT
        val hdrTypes = display.hdrCapabilities?.supportedHdrTypes?.toSet().orEmpty()
        val profile = DeviceProfile(
            maxWidth = if (uhd) UHD_WIDTH else DeviceProfile.Conservative.maxWidth,
            maxHeight = if (uhd) UHD_HEIGHT else DeviceProfile.Conservative.maxHeight,
            videoCodecs = (video + "h264") - "hevc",
            audioCodecs = audio + DeviceProfile.Conservative.audioCodecs,
            containers = setOf("mp4", "mkv", "mov") + if ("vp9" in video || "av1" in video) setOf("webm") else emptySet(),
            maxBitrateKbps = if (uhd) UHD_BITRATE_KBPS else HD_BITRATE_KBPS,
            supportsHevc = "hevc" in video,
            supportsHdr10 = Display.HdrCapabilities.HDR_TYPE_HDR10 in hdrTypes,
            supportsDolbyVision = Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in hdrTypes,
        )
        Log.i(TAG) {
            "display ${mode.physicalWidth}x${mode.physicalHeight}, video ${profile.videoCodecs}, hevc ${profile.supportsHevc}, " +
                "audio ${profile.audioCodecs}, hdr10 ${profile.supportsHdr10}, dv ${profile.supportsDolbyVision}"
        }
        return profile
    }

    /** Hardware-backed on API 29+; before that, judge by the vendor-style name. */
    private fun MediaCodecInfo.isHardware(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            isHardwareAccelerated
        } else {
            val n = name.lowercase()
            !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw."))
        }
}
