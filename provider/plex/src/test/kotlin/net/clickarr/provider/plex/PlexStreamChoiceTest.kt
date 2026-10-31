package net.clickarr.provider.plex

import io.kotest.matchers.shouldBe
import net.clickarr.provider.api.PlaybackChoice
import org.junit.Test

class PlexStreamChoiceTest {
    @Test
    fun `media index resolves by id or falls back to zero`() {
        val media = listOf(
            PlexMedia(id = 10, height = 1080),
            PlexMedia(id = 20, height = 720),
        )
        plexMediaIndex(media, null) shouldBe 0
        plexMediaIndex(media, "20") shouldBe 1
        plexMediaIndex(media, "1") shouldBe 1
        plexMediaIndex(media, "missing") shouldBe 0
    }

    @Test
    fun `audio stream id prefers explicit id then language`() {
        val part = PlexPart(
            streams = listOf(
                PlexStream(id = 1, streamType = 2, languageCode = "eng", displayTitle = "English"),
                PlexStream(id = 2, streamType = 2, languageCode = "spa", displayTitle = "Spanish"),
            ),
        )
        plexAudioStreamId(part, null) shouldBe null
        plexAudioStreamId(part, PlaybackChoice(audioId = "2")) shouldBe 2L
        plexAudioStreamId(part, PlaybackChoice(preferredAudioLanguage = "spa")) shouldBe 2L
    }

    @Test
    fun `video label uses resolution codec and bitrate`() {
        plexVideoLabel(PlexMedia(height = 1080, videoCodec = "h264", bitrate = 8_000, container = "mkv")) shouldBe
            "1080p · h264 · 8.0 Mbps · mkv"
    }
}
