package com.rifters.riftedreader.opds.model

data class OpdsLink(
    val rel: String,
    val href: String,
    val type: String? = null,
    val title: String? = null
)

data class OpdsEntry(
    val id: String,
    val title: String,
    val author: String? = null,
    val summary: String? = null,
    val updatedEpochMs: Long? = null,
    val coverUrl: String? = null,
    val acquisitionLinks: List<OpdsLink> = emptyList(),
    val navigationLinks: List<OpdsLink> = emptyList()
)

data class OpdsFeed(
    val title: String,
    val id: String,
    val updatedEpochMs: Long? = null,
    val iconUrl: String? = null,
    val entries: List<OpdsEntry> = emptyList(),
    val nextFeedUrl: String? = null,
    val searchUrl: String? = null
)
