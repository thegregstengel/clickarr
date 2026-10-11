package net.clickarr.provider.plex

import io.kotest.matchers.shouldBe
import net.clickarr.core.model.ProviderId
import org.junit.jupiter.api.Test

class PlexMapperArtworkTest {
    private val mapper = PlexMapper(ProviderId("p"))

    @Test
    fun `an episode borrows its show's backdrop and theme music`() {
        val m = PlexMetadata(
            ratingKey = "2011", type = "episode", title = "Pilot",
            grandparentArt = "/library/metadata/201/art/1", grandparentTheme = "/library/metadata/201/theme/1",
        )
        val art = mapper.artwork(m)
        art.backdrop?.path shouldBe "/library/metadata/201/art/1"
        art.theme?.path shouldBe "/library/metadata/201/theme/1"
    }

    @Test
    fun `a show's own art and theme win`() {
        val m = PlexMetadata(ratingKey = "201", type = "show", title = "Show", art = "/a", theme = "/t", grandparentArt = "/x")
        mapper.artwork(m).backdrop?.path shouldBe "/a"
        mapper.artwork(m).theme?.path shouldBe "/t"
    }
}
