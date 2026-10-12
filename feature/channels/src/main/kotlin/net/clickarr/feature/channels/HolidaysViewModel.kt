package net.clickarr.feature.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import net.clickarr.core.common.AppTime
import net.clickarr.core.common.Clock
import net.clickarr.core.common.Outcome
import net.clickarr.core.common.titleSortKey
import net.clickarr.core.model.Channel
import net.clickarr.core.model.ChannelIcon
import net.clickarr.core.model.Holiday
import net.clickarr.core.model.Library
import net.clickarr.core.model.LibraryKind
import net.clickarr.core.model.MediaFilter
import net.clickarr.core.model.MediaRef
import net.clickarr.core.model.Movie
import net.clickarr.core.model.OrderingMode
import net.clickarr.core.model.ProgrammingSource
import net.clickarr.data.ChannelRepository
import net.clickarr.data.ProviderRegistry
import net.clickarr.provider.api.MediaProvider
import net.clickarr.provider.api.Page
import net.clickarr.provider.api.PageOf

/**
 * Settings, Channels, Holiday channels. Each holiday can be switched on, and is then filled from genres, Plex
 * collections, or hand-picked movies. Switching one on creates a shuffled, back-to-back channel tagged with the
 * holiday, so it only shows in that holiday's window; switching it off deletes the channel.
 */
@HiltViewModel
class HolidaysViewModel @Inject constructor(
    private val registry: ProviderRegistry,
    private val repository: ChannelRepository,
    private val clock: Clock,
) : ViewModel() {
    enum class Mode(val label: String, val hint: String) {
        GENRES("Genres", "Every movie in the genres you pick. Grows as you add movies."),
        COLLECTIONS("Collections", "The collections you pick from your server."),
        SELECTION("Selection", "Exactly the movies you pick."),
    }

    /** One choice in a picker: a genre name, a collection, or a movie. [key] is unique within the list. */
    data class Option(val key: String, val label: String, val detail: String?)

    sealed interface Step {
        data object List : Step
        data class ChooseMode(val holiday: Holiday) : Step
        data class Loading(val holiday: Holiday) : Step
        data class Pick(
            val holiday: Holiday,
            val mode: Mode,
            val options: kotlin.collections.List<Option>,
            val picked: Set<String>,
            /** The channel number; holiday channels for different holidays can share one. */
            val number: Int,
            /** Why [number] cannot be used, or null. */
            val numberProblem: String? = null,
        ) : Step
        data class Working(val holiday: Holiday) : Step
        data class Failed(val message: String) : Step
    }

    private val _step = MutableStateFlow<Step>(Step.List)
    val step: StateFlow<Step> = _step.asStateFlow()

    /** The holiday channels that exist, by holiday. */
    val channels: StateFlow<Map<Holiday, Channel>> = repository.channels
        .map { list -> list.mapNotNull { ch -> ch.holiday?.let { it to ch } }.toMap() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun today(): LocalDate = AppTime.local(clock.now()).date

    fun open(holiday: Holiday) {
        _step.value = Step.ChooseMode(holiday)
    }

    fun back() {
        _step.update { s -> if (s is Step.Pick) Step.ChooseMode(s.holiday) else Step.List }
    }

    fun turnOff(holiday: Holiday) {
        val ch = channels.value[holiday] ?: return
        _step.value = Step.Working(holiday)
        viewModelScope.launch {
            runCatching { repository.delete(ch.id) }
            _step.value = Step.List
        }
    }

    fun choose(holiday: Holiday, mode: Mode) {
        _step.value = Step.Loading(holiday)
        viewModelScope.launch {
            _step.value = when (val r = library()) {
                is Outcome.Failure -> Step.Failed(r.error.message)
                is Outcome.Success -> {
                    val number = channels.value[holiday]?.number ?: DEFAULT_NUMBER
                    checkNumber(Step.Pick(holiday, mode, options(r.value, mode), emptySet(), number))
                }
            }
        }
    }

    fun changeNumber(delta: Int) {
        val s = _step.value as? Step.Pick ?: return
        _step.value = s.copy(number = (s.number + delta).coerceIn(1, MAX_NUMBER))
        viewModelScope.launch { (_step.value as? Step.Pick)?.let { _step.value = checkNumber(it) } }
    }

    /** A regular channel on the number blocks it; another holiday's channel does not. */
    private suspend fun checkNumber(s: Step.Pick): Step.Pick {
        val existing = channels.value[s.holiday]
        val taken = repository.numberTaken(s.number, existing?.id, s.holiday)
        return s.copy(numberProblem = if (taken) "Channel ${s.number} is taken by a regular channel." else null)
    }

    fun toggle(key: String) {
        _step.update { s -> if (s is Step.Pick) s.copy(picked = if (key in s.picked) s.picked - key else s.picked + key) else s }
    }

    fun create() {
        val s = _step.value as? Step.Pick ?: return
        if (s.picked.isEmpty() || s.numberProblem != null) return
        val scan = cached ?: return
        _step.value = Step.Working(s.holiday)
        viewModelScope.launch {
            val existing = channels.value[s.holiday]
            existing?.let { runCatching { repository.delete(it.id) } }
            val number = s.number
            val r = repository.create(
                number, s.holiday.label, sourceFor(scan, s.mode, s.picked), OrderingMode.SHUFFLE, null,
                ChannelIcon.Glyph(GLYPH.getValue(s.holiday)), null, s.holiday,
            )
            _step.value = when (r) {
                is Outcome.Success -> Step.List
                is Outcome.Failure -> Step.Failed(r.error.message)
            }
        }
    }

    // ---- The library, read once per visit ----

    private class Scan(
        val movies: Map<Library, kotlin.collections.List<Movie>>,
        val collections: kotlin.collections.List<Pair<Library, net.clickarr.core.model.Collection>>,
    )

    private var cached: Scan? = null

    private suspend fun library(): Outcome<Scan> {
        cached?.let { return Outcome.Success(it) }
        val provider = registry.primary
            ?: return Outcome.Failure(net.clickarr.core.common.ClickarrError.Invalid("No server connected"))
        val libraries = when (val r = provider.libraries()) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> return r
        }
        val movies = LinkedHashMap<Library, kotlin.collections.List<Movie>>()
        val collections = ArrayList<Pair<Library, net.clickarr.core.model.Collection>>()
        for (lib in libraries) {
            if (lib.kind == LibraryKind.MOVIES) movies[lib] = allMovies(provider, lib)
            (provider.collections(lib.ref) as? Outcome.Success)?.value?.forEach { collections += lib to it }
        }
        return Outcome.Success(Scan(movies, collections).also { cached = it })
    }

    private suspend fun allMovies(provider: MediaProvider, lib: Library): kotlin.collections.List<Movie> {
        val all = ArrayList<Movie>()
        var page = Page.first()
        while (true) {
            val r: PageOf<Movie> = (provider.movies(lib.ref, page) as? Outcome.Success)?.value ?: return all
            all += r.items
            if (!r.hasMore || all.size >= MAX_MOVIES) return all
            page = page.next()
        }
    }

    private fun options(scan: Scan, mode: Mode): kotlin.collections.List<Option> = when (mode) {
        Mode.GENRES -> scan.movies.values.flatten().flatMap { it.genres }.groupingBy { it }.eachCount().entries
            .sortedBy { titleSortKey(it.key) }
            .map { (genre, n) -> Option(genre, genre, "$n movies") }
        Mode.COLLECTIONS -> scan.collections.sortedBy { titleSortKey(it.second.name) }.map { (lib, c) ->
            Option(key(c.ref), c.name, listOfNotNull(c.itemCount?.let { "$it items" }, lib.name).joinToString(" · "))
        }
        Mode.SELECTION -> scan.movies.values.flatten().sortedBy { titleSortKey(it.title) }
            .map { m -> Option(key(m.ref), m.title, m.year?.toString()) }
    }

    /** Genres become one library filter per movie library; several collections or movies become one channel. */
    private fun sourceFor(scan: Scan, mode: Mode, picked: Set<String>): ProgrammingSource {
        val parts: kotlin.collections.List<ProgrammingSource> = when (mode) {
            Mode.GENRES -> scan.movies.entries
                .filter { (_, movies) -> movies.any { m -> m.genres.any { it in picked } } }
                .map { (lib, _) -> ProgrammingSource.Library(lib.ref, MediaFilter(genres = picked)) }
            Mode.COLLECTIONS -> scan.collections.filter { (_, c) -> key(c.ref) in picked }
                .map { (_, c) -> ProgrammingSource.Collection(c.ref) }
            Mode.SELECTION -> listOf(
                ProgrammingSource.Explicit(scan.movies.values.flatten().filter { key(it.ref) in picked }.map { it.ref }),
            )
        }
        return parts.singleOrNull() ?: ProgrammingSource.Union(parts)
    }

    private fun key(ref: MediaRef) = "${ref.provider.value}/${ref.id.value}"

    private companion object {
        const val MAX_MOVIES = 5_000
        const val DEFAULT_NUMBER = 100
        const val MAX_NUMBER = 9_999
        val GLYPH = mapOf(
            Holiday.VALENTINES to "heart",
            Holiday.EASTER to "egg",
            Holiday.HALLOWEEN to "ghost",
            Holiday.THANKSGIVING to "drumstick",
            Holiday.CHRISTMAS to "tree-pine",
        )
    }
}
