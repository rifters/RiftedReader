package com.rifters.riftedreader.ui.library

import com.rifters.riftedreader.data.database.entities.BookMeta

internal fun extractUniqueTagsWithPreferredCasing(allBooks: List<BookMeta>): List<String> {
    val tagCasings = linkedMapOf<String, LinkedHashMap<String, Int>>()

    allBooks.asSequence()
        .flatMap { it.tags.asSequence() }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { tag ->
            val normalized = tag.lowercase()
            val casings = tagCasings.getOrPut(normalized) { linkedMapOf() }
            casings[tag] = (casings[tag] ?: 0) + 1
        }

    return tagCasings.entries
        .sortedBy { it.key }
        .map { (normalizedTag, casings) ->
            casings.maxByOrNull { it.value }?.key
                ?: error("Internal error: no casing found for '$normalizedTag'")
        }
}
