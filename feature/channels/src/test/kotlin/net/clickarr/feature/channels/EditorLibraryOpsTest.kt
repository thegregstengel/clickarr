package net.clickarr.feature.channels

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import net.clickarr.core.model.Library
import net.clickarr.core.model.LibraryKind
import net.clickarr.core.model.MediaFilter
import net.clickarr.core.model.MediaRef
import net.clickarr.core.model.NativeItemId
import net.clickarr.core.model.ProgrammingSource
import net.clickarr.core.model.ProviderId
import org.junit.jupiter.api.Test

class EditorLibraryOpsTest {
    private val provider = ProviderId("test")
    private fun ref(id: String) = MediaRef(provider, NativeItemId(id))
    private val movies = Library(ref("movies"), "Movies", LibraryKind.MOVIES)

    private val titles = listOf(
        LibraryTitle(ref("1"), "Goonies", 1985, listOf("Adventure"), contentRating = "PG", studio = "Warner Bros."),
        LibraryTitle(ref("2"), "Fellowship", 2001, listOf("Fantasy"), contentRating = "PG-13", studio = "New Line Cinema"),
        LibraryTitle(ref("3"), "Matrix", 1999, listOf("Sci-Fi"), contentRating = "R", studio = "Warner Bros."),
    )

    private fun state(filter: MediaFilter = MediaFilter()) = EditorStep.PickLibrary(
        library = movies,
        genres = listOf("Adventure", "Fantasy", "Sci-Fi"),
        decades = listOf(1980, 1990, 2000),
        contentRatings = listOf("PG", "PG-13", "R"),
        filter = filter,
        titles = titles,
    )

    @Test
    fun `content rating pane toggles ratings and filters matching titles on Apply`() {
        val opened = EditorLibraryOps.apply(state(), LibraryUiAction.OpenRatings)
        opened.pane shouldBe LibraryPane.RATINGS
        val toggled = EditorLibraryOps.apply(opened, LibraryUiAction.ToggleRating("PG"))
        val applied = EditorLibraryOps.apply(toggled, LibraryUiAction.ApplyFilters)
        applied.pane shouldBe LibraryPane.MAIN
        applied.filter.contentRatings shouldBe setOf("PG")
        EditorLibraryOps.canCreate(applied) shouldBe true
        applied.titles.filter { EditorLibraryOps.titleMatches(it, applied.filter) }.map { it.title } shouldContainExactly
            listOf("Goonies")
        val confirmed = EditorLibraryOps.confirm(applied)!!
        confirmed.first shouldBe ProgrammingSource.Library(movies.ref, MediaFilter(contentRatings = setOf("PG")))
        confirmed.second shouldBe "PG"
    }

    @Test
    fun `studio and network filters are recognized as active filters and match titles`() {
        val studioState = state(MediaFilter(studios = setOf("Warner Bros.")))
        EditorLibraryOps.hasFilter(studioState.filter) shouldBe true
        EditorLibraryOps.canCreate(studioState) shouldBe true
        studioState.titles.filter { EditorLibraryOps.titleMatches(it, studioState.filter) }.map { it.title } shouldContainExactly
            listOf("Goonies", "Matrix")
        EditorLibraryOps.confirm(studioState)!!.second shouldBe "Warner Bros."
    }
}
