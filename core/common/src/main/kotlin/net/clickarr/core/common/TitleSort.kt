package net.clickarr.core.common

/**
 * The key lists sort by: case-insensitive, ignoring a leading "The", "A", or "An", the way a library shelf (and
 * Plex) does, so "The Office" files under O. Every picker and list sorts with it, except the guide.
 */
fun titleSortKey(title: String): String {
    val t = title.trim().lowercase()
    val article = ARTICLES.firstOrNull { t.startsWith(it) && t.length > it.length }
    return if (article != null) t.substring(article.length).trimStart() else t
}

private val ARTICLES = listOf("the ", "a ", "an ")
