package com.rifters.riftedreader.util

import android.text.Html
import com.rifters.riftedreader.domain.parser.PageContent

/**
 * Utility for extracting preview text from page content for bookmarks.
 */
object BookmarkPreviewExtractor {
    
    private const val MAX_PREVIEW_LENGTH = 100
    private const val PREVIEW_CONTEXT_CHARS = 50
    
    /**
     * Extract preview text from page content at a given character offset.
     * Returns up to MAX_PREVIEW_LENGTH characters centered around the offset.
     * 
     * @param content The page content to extract from
     * @param characterOffset The character position in the content (0-based)
     * @return Preview text, or null if content is empty
     */
    fun extractPreview(content: PageContent, characterOffset: Int): String? {
        val plainText = getPlainText(content)
        if (plainText.isBlank()) return null
        
        val safeOffset = characterOffset.coerceIn(0, plainText.length - 1)
        
        // Calculate start and end positions for preview
        val start = (safeOffset - PREVIEW_CONTEXT_CHARS).coerceAtLeast(0)
        val end = (safeOffset + PREVIEW_CONTEXT_CHARS).coerceAtMost(plainText.length)
        
        // Extract substring and clean up whitespace
        val preview = plainText.substring(start, end)
            .replace(Regex("\\s+"), " ")
            .trim()
        
        // Add ellipsis if we're not at the start/end
        val prefix = if (start > 0) "..." else ""
        val suffix = if (end < plainText.length) "..." else ""
        
        return (prefix + preview + suffix).take(MAX_PREVIEW_LENGTH + 6) // +6 for ellipsis
    }
    
    /**
     * Extract preview text from the beginning of the content.
     * Useful when there's no specific character offset.
     * 
     * @param content The page content to extract from
     * @return Preview text, or null if content is empty
     */
    fun extractPreviewFromStart(content: PageContent): String? {
        val plainText = getPlainText(content)
        if (plainText.isBlank()) return null
        
        val preview = plainText.take(MAX_PREVIEW_LENGTH)
            .replace(Regex("\\s+"), " ")
            .trim()
        
        val suffix = if (plainText.length > MAX_PREVIEW_LENGTH) "..." else ""
        return preview + suffix
    }
    
    /**
     * Get plain text from PageContent, handling both text and HTML content.
     */
    private fun getPlainText(content: PageContent): String {
        // If we have plain text, use it
        if (content.text.isNotBlank()) {
            return content.text
        }
        
        // Otherwise, extract text from HTML
        return content.html?.let { html ->
            try {
                @Suppress("DEPRECATION")
                Html.fromHtml(html).toString().trim()
            } catch (e: Exception) {
                // Fallback: strip HTML tags manually if Html.fromHtml fails
                html.replace(Regex("<[^>]*>"), "").trim()
            }
        } ?: ""
    }
}

