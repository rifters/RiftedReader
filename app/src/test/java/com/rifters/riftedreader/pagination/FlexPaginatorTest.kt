package com.rifters.riftedreader.pagination

import com.rifters.riftedreader.data.database.entities.BookMeta
import com.rifters.riftedreader.domain.parser.BookParser
import com.rifters.riftedreader.domain.parser.PageContent
import com.rifters.riftedreader.domain.parser.TocEntry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit tests for FlexPaginator.
 * 
 * Tests cover:
 * - Window HTML assembly
 * - Chapter wrapping with data-chapter attributes
 * - Error handling (invalid ranges, missing chapters)
 * - HTML structure validation
 */
class FlexPaginatorTest {
    
    private lateinit var parser: MockBookParser
    private lateinit var bookFile: File
    private lateinit var flexPaginator: FlexPaginator
    
    @Before
    fun setup() {
        parser = MockBookParser()
        bookFile = File("/mock/book.epub")
        flexPaginator = FlexPaginator(parser, bookFile)
    }
    
    @Test
    fun `assembleWindow returns null for invalid chapter range`() = runTest {
        // Test negative firstChapter
        val result1 = flexPaginator.assembleWindow(
            windowIndex = 0,
            firstChapter = -1,
            lastChapter = 2
        )
        assertNull(result1)
        
        // Test firstChapter > lastChapter
        val result2 = flexPaginator.assembleWindow(
            windowIndex = 0,
            firstChapter = 5,
            lastChapter = 2
        )
        assertNull(result2)
    }
    
    @Test
    fun `assembleWindow returns null when no chapters load`() = runTest {
        // Configure mock parser to return null for all chapters
        parser.returnNull = true
        
        val result = flexPaginator.assembleWindow(
            windowIndex = 0,
            firstChapter = 0,
            lastChapter = 2
        )
        
        assertNull(result)
    }
    
    @Test
    fun `assembleWindow creates WindowData with correct metadata`() = runTest {
        // Configure mock parser to return content
        parser.addChapter(0, "Chapter 0 text", "<p>Chapter 0 HTML</p>")
        parser.addChapter(1, "Chapter 1 text", "<p>Chapter 1 HTML</p>")
        parser.addChapter(2, "Chapter 2 text", "<p>Chapter 2 HTML</p>")
        
        val result = flexPaginator.assembleWindow(
            windowIndex = 5,
            firstChapter = 0,
            lastChapter = 2
        )
        
        assertNotNull(result)
        assertEquals(5, result!!.windowIndex)
        assertEquals(0, result.firstChapter)
        assertEquals(2, result.lastChapter)
        assertNull(result.sliceMetadata) // Not sliced yet
        assertFalse(result.isPreSliced)
    }
    
    @Test
    fun `assembleWindow wraps chapters in sections with data-chapter attributes`() = runTest {
        parser.addChapter(10, "Chapter 10", "<p>Content of chapter 10</p>")
        parser.addChapter(11, "Chapter 11", "<p>Content of chapter 11</p>")
        
        val result = flexPaginator.assembleWindow(
            windowIndex = 2,
            firstChapter = 10,
            lastChapter = 11
        )
        
        assertNotNull(result)
        val html = result!!.html
        
        // Check window-root structure
        assertTrue(html.contains("<div id=\"window-root\" data-window-index=\"2\""))
        assertTrue(html.contains("data-first-chapter=\"10\""))
        assertTrue(html.contains("data-last-chapter=\"11\""))
        
        // Check section wrapping
        assertTrue(html.contains("<section data-chapter=\"10\">"))
        assertTrue(html.contains("<section data-chapter=\"11\">"))
        assertTrue(html.contains("<p>Content of chapter 10</p>"))
        assertTrue(html.contains("<p>Content of chapter 11</p>"))
        
        // Check closing tags
        assertTrue(html.contains("</section>"))
        assertTrue(html.contains("</div>"))
    }
    
    @Test
    fun `assembleWindow handles text-only content`() = runTest {
        parser.addChapter(0, "Plain text content\n\nSecond paragraph", null)
        
        val result = flexPaginator.assembleWindow(
            windowIndex = 0,
            firstChapter = 0,
            lastChapter = 0
        )
        
        assertNotNull("Result should not be null", result)
        val html = result!!.html
        
        // Should wrap text in <p> tags and contain the content
        assertTrue("HTML should contain <p> tags", html.contains("<p>"))
        assertTrue("HTML should contain section tag", html.contains("<section"))
        // The actual text might be HTML-encoded, so just check it's not empty
        assertTrue("HTML should not be blank", html.isNotBlank())
    }
    
    @Test
    fun `assembleWindow skips empty chapters but includes others`() = runTest {
        parser.addChapter(0, "Chapter 0", "<p>Chapter 0 content</p>")
        parser.addChapter(1, "", "")  // Empty chapter
        parser.addChapter(2, "Chapter 2", "<p>Chapter 2 content</p>")
        
        val result = flexPaginator.assembleWindow(
            windowIndex = 0,
            firstChapter = 0,
            lastChapter = 2
        )
        
        assertNotNull(result)
        val html = result!!.html
        
        // Should include chapters 0 and 2, skip empty chapter 1
        assertTrue(html.contains("data-chapter=\"0\""))
        assertFalse(html.contains("data-chapter=\"1\"")) // Empty chapter skipped
        assertTrue(html.contains("data-chapter=\"2\""))
    }
    
    @Test
    fun `assembleWindow creates valid HTML structure`() = runTest {
        parser.addChapter(0, "Test", "<p>Test content</p>")
        
        val result = flexPaginator.assembleWindow(
            windowIndex = 0,
            firstChapter = 0,
            lastChapter = 0
        )
        
        assertNotNull(result)
        val html = result!!.html
        
        // Should have proper nesting: window-root > section > content
        val windowRootStart = html.indexOf("<div id=\"window-root\"")
        val sectionStart = html.indexOf("<section data-chapter=\"0\">")
        val contentStart = html.indexOf("<p>Test content</p>")
        val sectionEnd = html.indexOf("</section>")
        val windowRootEnd = html.indexOf("</div>")
        
        assertTrue(windowRootStart >= 0)
        assertTrue(sectionStart > windowRootStart)
        assertTrue(contentStart > sectionStart)
        assertTrue(sectionEnd > contentStart)
        assertTrue(windowRootEnd > sectionEnd)
    }
    
    @Test
    fun `assembleWindow handles special HTML characters in text`() = runTest {
        parser.addChapter(0, "Text with <special> &characters&", null)
        
        val result = flexPaginator.assembleWindow(
            windowIndex = 0,
            firstChapter = 0,
            lastChapter = 0
        )
        
        // Should successfully create HTML even with special characters
        assertNotNull("Result should not be null", result)
        val html = result!!.html
        
        // Should contain some content (the special characters will be encoded)
        assertTrue("HTML should not be empty", html.isNotBlank())
        assertTrue("HTML should contain section tag", html.contains("<section"))
    }

    @Test
    fun `assembleWindow preserves image tags and attributes for image-heavy chapters`() = runTest {
        val imageChapterHtml = """
            <div class="illustration">
                <img src="images/cover.jpg" alt="Cover Art" width="600" height="800" style="height: 800px;" />
                <p class="caption">Cover Illustration</p>
                <img src="images/map.png" alt="World Map" />
            </div>
        """.trimIndent()
        parser.addChapter(5, "Cover Illustration", imageChapterHtml)

        val result = flexPaginator.assembleWindow(
            windowIndex = 1,
            firstChapter = 5,
            lastChapter = 5
        )

        assertNotNull(result)
        val html = result!!.html
        assertTrue(html.contains("<section data-chapter=\"5\">"))
        assertTrue(html.contains("<img src=\"images/cover.jpg\""))
        assertTrue(html.contains("alt=\"Cover Art\""))
        assertTrue(html.contains("width=\"600\""))
        assertTrue(html.contains("height=\"800\""))
        assertTrue(html.contains("<img src=\"images/map.png\""))
    }

    @Test
    fun `assembleWindow enforces distinct section boundaries across multi-chapter windows`() = runTest {
        parser.addChapter(1, "Chapter 1 Title", "<h1>Chapter 1</h1><p>First chapter body text.</p>")
        parser.addChapter(2, "Chapter 2 Title", "<h1>Chapter 2</h1><p>Second chapter body text.</p>")
        parser.addChapter(3, "Chapter 3 Title", "<h1>Chapter 3</h1><p>Third chapter body text.</p>")

        val result = flexPaginator.assembleWindow(
            windowIndex = 0,
            firstChapter = 1,
            lastChapter = 3
        )

        assertNotNull(result)
        val html = result!!.html
        // Verify each chapter is enclosed in its own distinct section
        val sec1 = "<section data-chapter=\"1\">"
        val sec2 = "<section data-chapter=\"2\">"
        val sec3 = "<section data-chapter=\"3\">"
        val idx1 = html.indexOf(sec1)
        val idx2 = html.indexOf(sec2)
        val idx3 = html.indexOf(sec3)

        assertTrue("Section 1 exists", idx1 >= 0)
        assertTrue("Section 2 exists after Section 1", idx2 > idx1)
        assertTrue("Section 3 exists after Section 2", idx3 > idx2)

        // Ensure closing section tags exist between chapters
        val closeSec1 = html.indexOf("</section>", idx1)
        val closeSec2 = html.indexOf("</section>", idx2)
        assertTrue(closeSec1 < idx2)
        assertTrue(closeSec2 < idx3)
    }

    @Test
    fun `SliceMetadata isValid correctly validates totalPages and slice count`() {
        val validSlices = listOf(
            PageSlice(page = 0, chapter = 1, startChar = 0, endChar = 250, heightPx = 600),
            PageSlice(page = 1, chapter = 1, startChar = 250, endChar = 500, heightPx = 600)
        )
        val validMeta = SliceMetadata(windowIndex = 0, totalPages = 2, slices = validSlices)
        assertTrue(validMeta.isValid())

        val invalidCountMeta = SliceMetadata(windowIndex = 0, totalPages = 3, slices = validSlices)
        assertFalse(invalidCountMeta.isValid())

        val zeroPageMeta = SliceMetadata(windowIndex = 0, totalPages = 0, slices = emptyList())
        assertFalse(zeroPageMeta.isValid())
    }

    @Test
    fun `FlexSlicingConfig setDefaultViewportSize updates default values`() {
        try {
            FlexSlicingConfig.setDefaultViewportSize(720, 1280)
            val config = FlexSlicingConfig()
            assertEquals(720, config.viewportWidthPx)
            assertEquals(1280, config.viewportHeightPx)
        } finally {
            FlexSlicingConfig.setDefaultViewportSize(
                FlexSlicingConfig.DEFAULT_VIEWPORT_WIDTH_PX,
                FlexSlicingConfig.DEFAULT_VIEWPORT_HEIGHT_PX
            )
        }
    }
    
    /**
     * Mock BookParser for testing.
     */
    private class MockBookParser : BookParser {
        var returnNull = false
        private val chapters = mutableMapOf<Int, PageContent>()
        
        fun addChapter(chapterIndex: Int, text: String, html: String?) {
            chapters[chapterIndex] = PageContent(text = text, html = html)
        }
        
        override fun canParse(file: File): Boolean = true
        
        override suspend fun getPageContent(file: File, page: Int): PageContent {
            if (returnNull) return PageContent.EMPTY
            return chapters[page] ?: PageContent.EMPTY
        }
        
        override suspend fun getPageCount(file: File): Int = chapters.size
        
        override suspend fun getTableOfContents(file: File): List<TocEntry> = emptyList()
        
        override suspend fun extractMetadata(file: File): BookMeta {
            return BookMeta(
                id = "mock-book-id",
                title = "Mock Book",
                author = "Mock Author",
                path = file.absolutePath,
                format = "EPUB",
                size = 1000L
            )
        }
    }
}
