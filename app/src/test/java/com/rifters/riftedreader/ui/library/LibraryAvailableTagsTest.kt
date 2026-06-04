package com.rifters.riftedreader.ui.library

import com.rifters.riftedreader.data.database.entities.BookMeta
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryAvailableTagsTest {

    @Test
    fun extractAvailableTags_emptyBooks_returnsEmptyList() {
        assertEquals(emptyList<String>(), extractUniqueTagsWithPreferredCasing(emptyList()))
    }

    @Test
    fun extractAvailableTags_filtersWhitespaceAndUsesMostCommonCasing() {
        val books = listOf(
            book("book1", listOf("Fiction", " ", "\t")),
            book("book2", listOf("fiction", "fiction")),
            book("book3", listOf("Mystery", "mystery", "Mystery"))
        )

        assertEquals(listOf("fiction", "Mystery"), extractUniqueTagsWithPreferredCasing(books))
    }

    @Test
    fun extractAvailableTags_sameCounts_usesFirstEncounteredCasing() {
        val books = listOf(
            book("book1", listOf("Fiction")),
            book("book2", listOf("fiction"))
        )

        assertEquals(listOf("Fiction"), extractUniqueTagsWithPreferredCasing(books))
    }

    @Test
    fun extractAvailableTags_sortsByNormalizedTag() {
        val books = listOf(
            book("book1", listOf("Zoo")),
            book("book2", listOf("apple", "Apple", "apple")),
            book("book3", listOf("Banana", "banana"))
        )

        assertEquals(listOf("apple", "Banana", "Zoo"), extractUniqueTagsWithPreferredCasing(books))
    }

    private fun book(id: String, tags: List<String>) = BookMeta(
        id = id,
        path = "/tmp/$id.epub",
        title = id,
        format = "epub",
        size = 1L,
        tags = tags
    )
}
