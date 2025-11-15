package com.rifters.riftedreader.domain.library

import java.util.UUID

/**
 * Represents a user defined saved search within the library screen. Each saved search captures the
 * library filters so that they can be restored quickly on the next visit.
 */
data class SavedLibrarySearch(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val filters: LibrarySearchFilters,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
)
