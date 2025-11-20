package com.rifters.riftedreader.domain.pagination

/**
 * Pagination mode for the reader.
 */
enum class PaginationMode {
    /**
     * Chapter-based pagination (original behavior).
     * Each chapter is treated as a separate page in the ViewPager.
     */
    CHAPTER_BASED,
    
    /**
     * Continuous pagination with sliding window.
     * Book is presented as a single continuous array of pages.
     * Only a window of chapters is kept in memory.
     */
    CONTINUOUS
}
