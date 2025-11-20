package com.rifters.riftedreader

import com.rifters.riftedreader.domain.parser.PageContent
import com.rifters.riftedreader.util.BookmarkPreviewExtractor
import org.junit.Test
import org.junit.Assert.*

/**
 * Tests for BookmarkPreviewExtractor utility
 */
class BookmarkPreviewExtractorTest {

    @Test
    fun extractPreview_fromMiddleOfText() {
        val content = PageContent(
            text = "This is the beginning of a long text. " +
                   "Here is some middle content that we want to see. " +
                   "And this is the end of the text."
        )
        
        // Character offset roughly in the middle section
        val offset = 50
        val preview = BookmarkPreviewExtractor.extractPreview(content, offset)
        
        assertNotNull(preview)
        assertTrue(preview!!.contains("middle content"))
    }
    
    @Test
    fun extractPreview_fromBeginning() {
        val content = PageContent(text = "Start of the text with some content that follows.")
        
        val offset = 5
        val preview = BookmarkPreviewExtractor.extractPreview(content, offset)
        
        assertNotNull(preview)
        assertFalse(preview!!.startsWith("..."))
        assertTrue(preview.contains("Start"))
    }
    
    @Test
    fun extractPreview_fromEnd() {
        val content = PageContent(text = "Some text content leading up to the final words at the end.")
        
        val offset = content.text.length - 10
        val preview = BookmarkPreviewExtractor.extractPreview(content, offset)
        
        assertNotNull(preview)
        assertFalse(preview!!.endsWith("..."))
        assertTrue(preview.contains("end"))
    }
    
    @Test
    fun extractPreview_withHtmlContent() {
        val content = PageContent(
            text = "",
            html = "<p>This is <strong>HTML</strong> content with <em>formatting</em>.</p>"
        )
        
        val offset = 10
        val preview = BookmarkPreviewExtractor.extractPreview(content, offset)
        
        assertNotNull(preview)
        // Should extract plain text from HTML (or fallback to stripped HTML)
        assertTrue(preview!!.isNotBlank())
        // HTML tags should be removed
        assertFalse(preview.contains("<strong>"))
        assertFalse(preview.contains("<p>"))
    }
    
    @Test
    fun extractPreview_emptyContent() {
        val content = PageContent(text = "")
        
        val preview = BookmarkPreviewExtractor.extractPreview(content, 0)
        
        assertNull(preview)
    }
    
    @Test
    fun extractPreview_whitespaceCleaning() {
        val content = PageContent(
            text = "Text with    multiple    spaces\n\nand\nnewlines."
        )
        
        val offset = 10
        val preview = BookmarkPreviewExtractor.extractPreview(content, offset)
        
        assertNotNull(preview)
        // Should normalize whitespace to single spaces
        assertFalse(preview!!.contains("    "))
        assertFalse(preview.contains("\n"))
    }
    
    @Test
    fun extractPreview_maxLength() {
        val longText = "a".repeat(500)
        val content = PageContent(text = longText)
        
        val preview = BookmarkPreviewExtractor.extractPreview(content, 250)
        
        assertNotNull(preview)
        // Should be limited to approximately 100 chars + ellipsis
        assertTrue(preview!!.length <= 110)
    }
    
    @Test
    fun extractPreviewFromStart_shortText() {
        val content = PageContent(text = "Short text")
        
        val preview = BookmarkPreviewExtractor.extractPreviewFromStart(content)
        
        assertNotNull(preview)
        assertEquals("Short text", preview)
    }
    
    @Test
    fun extractPreviewFromStart_longText() {
        val longText = "a".repeat(200)
        val content = PageContent(text = longText)
        
        val preview = BookmarkPreviewExtractor.extractPreviewFromStart(content)
        
        assertNotNull(preview)
        // Should be limited and have ellipsis
        assertTrue(preview!!.endsWith("..."))
        assertTrue(preview.length <= 110)
    }
    
    @Test
    fun extractPreview_offsetBeyondLength() {
        val content = PageContent(text = "Short text")
        
        // Offset beyond the text length should be clamped
        val preview = BookmarkPreviewExtractor.extractPreview(content, 1000)
        
        assertNotNull(preview)
        assertTrue(preview!!.contains("text"))
    }
    
    @Test
    fun extractPreview_negativeOffset() {
        val content = PageContent(text = "Some text content")
        
        // Negative offset should be clamped to 0
        val preview = BookmarkPreviewExtractor.extractPreview(content, -10)
        
        assertNotNull(preview)
        assertTrue(preview!!.contains("Some"))
    }
}
