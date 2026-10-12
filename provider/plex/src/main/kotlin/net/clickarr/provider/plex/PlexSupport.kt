package net.clickarr.provider.plex

import net.clickarr.core.common.Outcome
import net.clickarr.provider.api.Page
import net.clickarr.provider.api.PageOf
import okhttp3.HttpUrl

/** Plex's paging, kept out of PlexProvider to keep it readable. Shared helpers live in provider:api. */

internal suspend fun <T> PlexHttp.pageOf(listUrl: HttpUrl, page: Page, mapper: (PlexMetadata) -> T): Outcome<PageOf<T>> =
    get(
        listUrl,
        PlexResponse.serializer(),
        mapOf("X-Plex-Container-Start" to page.offset.toString(), "X-Plex-Container-Size" to page.size.toString()),
    ).map { r ->
        val items = r.container.metadata.map(mapper)
        PageOf(items, page, r.container.totalSize ?: (page.offset + items.size))
    }
