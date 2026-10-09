package net.clickarr.provider.plex

import net.clickarr.core.common.Outcome
import net.clickarr.provider.api.Page
import net.clickarr.provider.api.PageOf
import okhttp3.HttpUrl

/** Paging and aggregation helpers for PlexProvider, kept out of the class to keep it readable. */

internal suspend fun <T> PlexHttp.pageOf(listUrl: HttpUrl, page: Page, mapper: (PlexMetadata) -> T): Outcome<PageOf<T>> =
    get(
        listUrl,
        PlexResponse.serializer(),
        mapOf("X-Plex-Container-Start" to page.offset.toString(), "X-Plex-Container-Size" to page.size.toString()),
    ).map { r ->
        val items = r.container.metadata.map(mapper)
        PageOf(items, page, r.container.totalSize ?: (page.offset + items.size))
    }

internal suspend fun <T> allPages(fetch: suspend (Page) -> Outcome<PageOf<T>>): Outcome<List<T>> {
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

internal suspend fun <I, T> collectAll(inputs: List<I>, fetch: suspend (I) -> Outcome<List<T>>): Outcome<List<T>> {
    val out = ArrayList<T>()
    for (i in inputs) {
        when (val r = fetch(i)) {
            is Outcome.Success -> out += r.value
            is Outcome.Failure -> return r
        }
    }
    return Outcome.Success(out)
}

internal fun mimeFor(container: String?): String? = when (container?.lowercase()) {
    "mp4", "m4v" -> "video/mp4"
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    "ts" -> "video/mp2t"
    else -> null
}
