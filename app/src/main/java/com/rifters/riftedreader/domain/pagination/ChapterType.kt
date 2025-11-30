package com.rifters.riftedreader.domain.pagination

/**
 * Enumeration of chapter types in an EPUB/book structure.
 *
 * EPUBs typically contain various types of content beyond the main chapters:
 * - Cover pages (often an image-only HTML wrapper)
 * - Navigation documents (nav.xhtml providing TOC)
 * - Front matter (copyright, dedication, title page)
 * - Main content chapters
 * - Non-linear items (notes, glossary, marked with linear="no")
 *
 * This classification enables:
 * - Consistent chapter counting (excluding non-content items from user-facing counts)
 * - Proper windowing (using correct source for pagination calculations)
 * - TTS traversal mapping (skipping nav/cover for speech)
 */
enum class ChapterType {
    /**
     * Cover page - typically an image or minimal HTML wrapper.
     * Usually excluded from user-facing chapter counts.
     */
    COVER,

    /**
     * Navigation document (nav.xhtml in EPUB 3, NCX reference in EPUB 2).
     * Provides table of contents but is not itself a readable chapter.
     * Always excluded from user-facing chapter counts.
     */
    NAV,

    /**
     * Front matter - copyright, dedication, title page, etc.
     * May be included or excluded based on user preference.
     */
    FRONT_MATTER,

    /**
     * Main content chapter - the primary reading content.
     * Always included in chapter counts.
     */
    CONTENT,

    /**
     * Non-linear content - items marked with linear="no" in the spine.
     * Includes footnotes, endnotes, glossary, appendix.
     * Excluded by default but may be included based on user setting.
     */
    NON_LINEAR
}
