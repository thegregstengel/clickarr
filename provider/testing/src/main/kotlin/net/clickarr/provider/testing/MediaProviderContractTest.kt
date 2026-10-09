package net.clickarr.provider.testing

import io.kotest.matchers.collections.shouldBeSortedWith
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty as shouldNotBeEmptyString
import io.kotest.matchers.string.shouldStartWith
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import net.clickarr.core.common.Outcome
import net.clickarr.core.model.LibraryKind
import net.clickarr.core.model.MediaItem
import net.clickarr.core.model.ProgrammingSource
import net.clickarr.provider.api.ArtworkSize
import net.clickarr.provider.api.DeviceProfile
import net.clickarr.provider.api.MediaProvider
import net.clickarr.provider.api.PlaybackSource
import org.junit.jupiter.api.Test

/**
 * Behavior every provider must satisfy so the scheduler, channel editor, and player can treat them
 * alike. Provider modules extend this from their own test source set and supply a provider backed by
 * recorded fixtures. ADR 0019 requires the fake and the Plex client to both pass it.
 */
abstract class MediaProviderContractTest {
    abstract fun provider(): MediaProvider

    protected fun <T> Outcome<T>.value(): T = when (this) {
        is Outcome.Success -> value
        is Outcome.Failure -> error("expected success, got $error")
    }

    @Test
    fun `ping returns the same server identity the provider was created with`() = runTest {
        val p = provider()
        p.ping().value().serverIdentity shouldBe p.server.serverIdentity
    }

    @Test
    fun `libraries have kinds and every show library yields shows with episodes in aired order`() = runTest {
        val p = provider()
        val libs = p.libraries().value()
        libs.shouldNotBeEmpty()
        val showLib = libs.first { it.kind == LibraryKind.SHOWS }
        val shows = p.shows(showLib.ref).value()
        shows.items.shouldNotBeEmpty()
        shows.total shouldBe shows.total.coerceAtLeast(shows.items.size)
        val eps = p.episodes(shows.items.first().ref).value()
        eps.shouldNotBeEmpty()
        eps.shouldBeSortedWith(compareBy({ it.seasonIndex }, { it.episodeIndex }))
        eps.forEach {
            (it.runtime > 0.minutes) shouldBe true
            it.ref.provider shouldBe p.id
            it.show shouldBe shows.items.first().ref
        }
    }

    @Test
    fun `movies carry positive runtimes and at least one media version`() = runTest {
        val p = provider()
        val movieLib = p.libraries().value().first { it.kind == LibraryKind.MOVIES }
        val movies = p.movies(movieLib.ref).value().items
        movies.shouldNotBeEmpty()
        movies.forEach {
            (it.runtime > 0.minutes) shouldBe true
            it.media.shouldNotBeEmpty()
        }
    }

    @Test
    fun `resolving a show yields its episodes in order and resolving a library yields everything in it`() = runTest {
        val p = provider()
        val libs = p.libraries().value()
        val showLib = libs.first { it.kind == LibraryKind.SHOWS }
        val show = p.shows(showLib.ref).value().items.first()
        val fromShow = p.resolve(ProgrammingSource.Shows(listOf(show.ref))).value()
        fromShow.map { it.ref } shouldBe p.episodes(show.ref).value().map { it.ref }
        val fromLib = p.resolve(ProgrammingSource.Library(showLib.ref)).value()
        (fromLib.size >= fromShow.size) shouldBe true
    }

    @Test
    fun `collections and playlists resolve to playables`() = runTest {
        val p = provider()
        val movieLib = p.libraries().value().first { it.kind == LibraryKind.MOVIES }
        p.collections(movieLib.ref).value().forEach { c ->
            p.resolve(ProgrammingSource.Collection(c.ref)).value().shouldNotBeEmpty()
        }
        p.playlists().value().forEach { pl ->
            p.resolve(ProgrammingSource.Playlist(pl.ref)).value().shouldNotBeEmpty()
        }
    }

    @Test
    fun `explicit picks keep their order and union removes duplicates`() = runTest {
        val p = provider()
        val movieLib = p.libraries().value().first { it.kind == LibraryKind.MOVIES }
        val movies = p.movies(movieLib.ref).value().items.take(2).map { it.ref }
        val explicit = p.resolve(ProgrammingSource.Explicit(movies.reversed())).value()
        explicit.map { it.ref } shouldBe movies.reversed()
        val twice = ProgrammingSource.Union(listOf(ProgrammingSource.Explicit(movies), ProgrammingSource.Explicit(movies)))
        val union = p.resolve(twice).value()
        union.map { it.ref } shouldBe movies
    }

    @Test
    fun `items batch lookup returns normalized items for known refs`() = runTest {
        val p = provider()
        val movieLib = p.libraries().value().first { it.kind == LibraryKind.MOVIES }
        val refs = p.movies(movieLib.ref).value().items.map { it.ref }
        val items: List<MediaItem> = p.items(refs).value()
        items.map { it.ref } shouldBe refs
    }

    @Test
    fun `playback source for a movie is a URL with the requested start offset accounted for`() = runTest {
        val p = provider()
        val movieLib = p.libraries().value().first { it.kind == LibraryKind.MOVIES }
        val movie = p.movies(movieLib.ref).value().items.first()
        val source = p.playbackSource(movie.ref, DeviceProfile.Conservative, 17.minutes).value()
        source.url shouldStartWith "http"
        when (source) {
            is PlaybackSource.DirectPlay -> source.startAt shouldBe 17.minutes
            is PlaybackSource.Hls -> source.sessionId.shouldNotBeEmptyString()
        }
        p.endPlayback(source).value()
    }

    @Test
    fun `artwork refs resolve to absolute URLs`() = runTest {
        val p = provider()
        val showLib = p.libraries().value().first { it.kind == LibraryKind.SHOWS }
        val show = p.shows(showLib.ref).value().items.first()
        val ref = show.artwork.poster ?: show.artwork.thumb
        if (ref != null) p.artworkUrl(ref, ArtworkSize.POSTER) shouldStartWith "http"
    }
}
