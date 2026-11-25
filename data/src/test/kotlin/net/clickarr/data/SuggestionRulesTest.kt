package net.clickarr.data

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.minutes
import net.clickarr.core.model.Collection
import net.clickarr.core.model.EpisodeRuns
import net.clickarr.core.model.Library
import net.clickarr.core.model.LibraryKind
import net.clickarr.core.model.MediaRef
import net.clickarr.core.model.Movie
import net.clickarr.core.model.NativeItemId
import net.clickarr.core.model.OrderingMode
import net.clickarr.core.model.Playlist
import net.clickarr.core.model.ProgrammingSource
import net.clickarr.core.model.ProviderId
import net.clickarr.core.model.Show
import org.junit.jupiter.api.Test

class SuggestionRulesTest {
    private val provider = ProviderId("test")
    private fun ref(id: String) = MediaRef(provider, NativeItemId(id))
    private val tv = Library(ref("tv"), "TV Shows", LibraryKind.SHOWS)
    private val films = Library(ref("films"), "Movies", LibraryKind.MOVIES)

    private fun show(title: String, episodes: Int, vararg genres: String, year: Int = 2010, network: String? = null) =
        Show(ref(title), title, genres = genres.toList(), year = year, studio = network, network = network, episodeCount = episodes)

    private fun movie(title: String, vararg genres: String, year: Int = 2010, studio: String? = null) =
        Movie(ref(title), title, genres = genres.toList(), year = year, studio = studio, runtime = 100.minutes)

    /** Enough titles to count as a normal library, with no genre strong enough to make a channel on its own. */
    private fun ballast(): List<Movie> = (1..40).map { movie("Ballast $it", "Genre$it", year = 1900 + it) }

    private fun scan(
        shows: List<Show> = emptyList(),
        movies: List<Movie> = emptyList(),
        collections: List<Pair<Library, Collection>> = emptyList(),
        playlists: List<Playlist> = emptyList(),
    ) = LibraryScan(mapOf(tv to shows), mapOf(films to movies), collections, playlists)

    @Test
    fun `a show with many episodes becomes a rerun channel, first in the list, in order`() {
        val plan = SuggestionRules.plan(scan(shows = listOf(show("The Office", 201, "Comedy"), show("Pilot Only", 1)), movies = ballast()))
        plan.first().name shouldBe "The Office"
        plan.first().order shouldBe OrderingMode.SEQUENTIAL
        plan.first().source shouldBe ProgrammingSource.Shows(listOf(ref("The Office")))
        plan.first().slotRounding.shouldBeNull()
        plan.map { it.name } shouldNotContain "Pilot Only"
    }

    @Test
    fun `three dramas are not a channel in a normal library, six comedies are Sitcoms with episode runs`() {
        val shows = (1..3).map { show("Drama $it", 10, "Drama") } + (1..6).map { show("Comedy $it", 10, "Comedy") }
        val plan = SuggestionRules.plan(scan(shows = shows, movies = ballast()))
        plan.map { it.name } shouldNotContain "Dramas"
        val sitcoms = plan.first { it.name == "Sitcoms" }
        sitcoms.order shouldBe OrderingMode.SHUFFLE
        sitcoms.runs shouldBe EpisodeRuns(2, 3)
        sitcoms.slotRounding.shouldBeNull()
        sitcoms.reason shouldBe "6 shows, shuffled"
    }

    @Test
    fun `cartoon tags merge into one Cartoons channel`() {
        val shows = (1..3).map { show("Toon $it", 10, "Animation") } + (1..3).map { show("Kid $it", 10, "Kids") }
        val plan = SuggestionRules.plan(scan(shows = shows, movies = ballast()))
        plan.map { it.name } shouldContain "Cartoons"
        plan.map { it.name } shouldNotContain "Animation TV"
    }

    @Test
    fun `a small library relaxes the thresholds instead of suggesting nothing`() {
        val plan = SuggestionRules.plan(
            scan(
                shows = listOf(show("The Office", 4, "Comedy"), show("Parks and Recreation", 2, "Comedy")),
                movies = listOf(movie("Fellowship", "Fantasy", year = 2001), movie("Two Towers", "Fantasy", year = 2002)),
                collections = listOf(films to Collection(ref("lotr"), "The Lord of the Rings", itemCount = 2)),
                playlists = listOf(Playlist(ref("p"), "80s Night", itemCount = 2)),
            ),
        )
        val names = plan.map { it.name }
        names shouldContain "The Office"
        names shouldContain "The Lord of the Rings"
        names shouldContain "Sitcoms"
        names shouldContain "80s Night"
        plan.first { it.name == "The Lord of the Rings" }.reason shouldBe "Your collection: 2 movies, in order"
    }

    @Test
    fun `a one-film collection and a tiny playlist are skipped in a normal library`() {
        val plan = SuggestionRules.plan(
            scan(
                movies = ballast(),
                collections = listOf(films to Collection(ref("solo"), "Just One", itemCount = 1)),
                playlists = listOf(Playlist(ref("p"), "Two Songs", itemCount = 2)),
            ),
        )
        plan.map { it.name } shouldNotContain "Just One"
        plan.map { it.name } shouldNotContain "Two Songs"
    }

    @Test
    fun `a big shows library does not crowd the movie channel out`() {
        val shows = (1..10).map { show("Big Show $it", 100 + it, "Comedy") }
        val movies = (1..30).map { movie("Film $it", "Drama", year = 1980 + it % 10) }
        val plan = SuggestionRules.plan(scan(shows = shows, movies = movies))
        val names = plan.map { it.name }
        names shouldContain "Movie Channel"
        names shouldContain "80s Movies"
        names.count { it.startsWith("Big Show") } shouldBe 4
        (plan.size <= ChannelSuggester.MAX) shouldBe true
    }

    @Test
    fun `channels that already exist are left out, whatever the case`() {
        val plan = SuggestionRules.plan(scan(shows = listOf(show("The Office", 201)), movies = ballast()), existing = setOf("the office"))
        plan.map { it.name } shouldNotContain "The Office"
    }

    @Test
    fun `decades read the way people say them`() {
        SuggestionRules.decadeName(1980) shouldBe "80s"
        SuggestionRules.decadeName(2000) shouldBe "2000s"
        SuggestionRules.decadeName(2010) shouldBe "2010s"
    }

    @Test
    fun `networks and studios with enough titles become shuffled channels`() {
        val shows = (1..5).map { show("NBC Show $it", 8, "Comedy", network = "NBC") } +
            (1..2).map { show("Small Net $it", 8, "Drama", network = "CW") }
        val movies = (1..6).map { movie("A24 Film $it", "Drama", year = 2015, studio = "A24") } +
            (1..6).map { movie("Uni Film $it", "Action", year = 1995, studio = "Universal Pictures") } +
            ballast()
        val plan = SuggestionRules.plan(scan(shows = shows, movies = movies))
        val names = plan.map { it.name }
        names shouldContain "NBC"
        names shouldNotContain "CW"
        names shouldContain "A24 Movies"
        names shouldContain "Universal Pictures"

        val nbc = plan.first { it.name == "NBC" }
        nbc.order shouldBe OrderingMode.SHUFFLE
        nbc.runs shouldBe EpisodeRuns(2, 3)
        nbc.reason shouldBe "5 shows, shuffled"

        val a24 = plan.first { it.name == "A24 Movies" }
        a24.order shouldBe OrderingMode.SHUFFLE
        a24.runs.shouldBeNull()
        a24.reason shouldBe "6 movies, shuffled"
    }

    @Test
    fun `a large diverse library produces more than twelve suggestions up to the cap`() {
        val shows = (1..5).map { show("Long Show $it", 100 + it, "Comedy", year = 1990, network = "NBC") } +
            (1..5).map { show("SciFi Show $it", 10, "Sci-Fi", year = 2000, network = "HBO") } +
            (1..5).map { show("Crime Show $it", 10, "Crime", year = 2010, network = "FX") } +
            (1..5).map { show("Toon Show $it", 10, "Animation", year = 2010, network = "Cartoon Network") }
        val movies = (1..15).map { movie("Comedy $it", "Comedy", year = 1985, studio = "A24") } +
            (1..15).map { movie("Action $it", "Action", year = 1995, studio = "Universal Pictures") } +
            (1..15).map { movie("Horror $it", "Horror", year = 2005, studio = "Blumhouse") }
        val collections = (1..4).map { films to Collection(ref("c-$it"), "Collection $it", itemCount = 4) }
        val playlists = (1..2).map { Playlist(ref("p-$it"), "Playlist $it", itemCount = 5) }

        val plan = SuggestionRules.plan(scan(shows, movies, collections, playlists))
        (plan.size > 12) shouldBe true
        (plan.size <= ChannelSuggester.MAX) shouldBe true
        plan.map { it.name } shouldContain "NBC"
        plan.map { it.name } shouldContain "A24 Movies"
        plan.map { it.name } shouldContain "Universal Pictures"
    }
}
