package com.rifters.riftedreader.domain.opds

import java.net.URL

/**
 * Placeholder OPDS client contract mirroring LibreraReader's OPDS integration
 * (see LIBRERA_ANALYSIS.md §4). The real implementation will parse Atom feeds
 * and expose pagination, search, and download entries.
 */
interface OpdsCatalogClient {
    suspend fun fetchRootCatalog(url: URL): Result<OpdsFeed>

    suspend fun search(url: URL, query: String): Result<OpdsFeed>
}

data class OpdsFeed(
    val id: String,
    val title: String,
    val entries: List<OpdsEntry> = emptyList(),
    val links: List<OpdsLink> = emptyList()
)

data class OpdsEntry(
    val id: String,
    val title: String,
    val summary: String?,
    val downloadLinks: List<OpdsLink>
)

data class OpdsLink(
    val href: String,
    val rel: String?,
    val type: String?
)
