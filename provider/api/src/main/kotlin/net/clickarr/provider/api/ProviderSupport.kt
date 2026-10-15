package net.clickarr.provider.api

import net.clickarr.core.common.Outcome
import net.clickarr.core.model.MediaFilter
import net.clickarr.core.model.MediaItem
import net.clickarr.core.model.Movie
import net.clickarr.core.model.Show
import net.clickarr.core.model.decadeSet

/** Helpers every provider needs: paging to the end, gathering per-item results, and evaluating a [MediaFilter]. */

/** Every page of a paged listing, stopping at the first failure. */
suspend fun <T> allPages(fetch: suspend (Page) -> Outcome<PageOf<T>>): Outcome<List<T>> {
    val out = ArrayList<T>()
    var page = Page.first()
    while (true) {
        val p = when (val result = fetch(page)) {
            is Outcome.Success -> result.value
            is Outcome.Failure -> return result
        }
        out += p.items
        if (!p.hasMore || p.items.isEmpty()) return Outcome.Success(out)
        page = page.next()
    }
}

/** [fetch] for each input in order, concatenated, stopping at the first failure. */
suspend fun <I, T> collectAll(inputs: List<I>, fetch: suspend (I) -> Outcome<List<T>>): Outcome<List<T>> {
    val out = ArrayList<T>()
    for (i in inputs) {
        when (val r = fetch(i)) {
            is Outcome.Success -> out += r.value
            is Outcome.Failure -> return r
        }
    }
    return Outcome.Success(out)
}

/** The MIME type a player expects for a container name as servers report it, or null to let the player sniff. */
fun mimeFor(container: String?): String? = when (container?.lowercase()) {
    "mp4", "m4v" -> "video/mp4"
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    "ts", "mpegts" -> "video/mp2t"
    else -> null
}

/** Client-side evaluation of a MediaFilter against normalized items (proposal 6.2: no tag-id round trips). */
fun MediaFilter.matches(item: MediaItem): Boolean = predicates().all { it(item) }

private fun MediaFilter.predicates(): List<(MediaItem) -> Boolean> = buildList {
    if (genres.isNotEmpty()) add { it.genres.any { g -> genres.any { wanted -> wanted.equals(g, ignoreCase = true) } } }
    val decades = decadeSet()
    if (decades.isNotEmpty()) {
        add { item ->
            val year = item.year ?: -1
            decades.any { d -> year in d until d + 10 }
        }
    }
    yearFrom?.let { from -> add { (it.year ?: Int.MIN_VALUE) >= from } }
    yearTo?.let { to -> add { (it.year ?: Int.MAX_VALUE) <= to } }
    if (studios.isNotEmpty()) add { it.studioOrNull() in studios }
    if (networks.isNotEmpty()) add { (it as? Show)?.network in networks }
}

private fun MediaItem.studioOrNull(): String? = (this as? Movie)?.studio ?: (this as? Show)?.studio
