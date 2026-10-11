package net.clickarr.provider.plex

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PlexSubtitlesTest {
    private fun sub(id: Long, lang: String?, forced: Boolean = false, selected: Boolean = false) =
        PlexStream(id = id, streamType = 3, codec = "srt", languageCode = lang, forced = forced, selected = selected)

    @Test
    fun `the track chosen in Plex wins`() {
        PlexSubtitles.choose(listOf(sub(1, "eng"), sub(2, "fra", selected = true)), "eng")?.id shouldBe 2
    }

    @Test
    fun `full subtitles in the viewer's language beat forced-only ones`() {
        PlexSubtitles.choose(listOf(sub(1, "fra"), sub(2, "eng", forced = true), sub(3, "eng")), "eng")?.id shouldBe 3
    }

    @Test
    fun `forced-only is better than another language`() {
        PlexSubtitles.choose(listOf(sub(1, "fra"), sub(2, "eng", forced = true)), "eng")?.id shouldBe 2
    }

    @Test
    fun `no match falls back to the first full track, and nothing to choose from gives nothing`() {
        PlexSubtitles.choose(listOf(sub(1, "fra", forced = true), sub(2, "deu")), "eng")?.id shouldBe 2
        PlexSubtitles.choose(emptyList(), "eng").shouldBeNull()
    }

    @Test
    fun `only text formats are offered as files`() {
        PlexSubtitles.mimeType("srt") shouldBe "application/x-subrip"
        PlexSubtitles.mimeType("ASS") shouldBe "text/x-ssa"
        PlexSubtitles.mimeType("pgs").shouldBeNull()
    }
}
