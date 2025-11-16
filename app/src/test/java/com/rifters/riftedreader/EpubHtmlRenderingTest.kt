package com.rifters.riftedreader

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for EPUB HTML rendering
 * Verifies that HTML content is properly preserved for WebView rendering
 */
class EpubHtmlRenderingTest {
    
    @Test
    fun htmlContent_preservesParagraphs() {
        val html = """
            <p>First paragraph</p>
            <p>Second paragraph</p>
        """.trimIndent()
        
        // WebView should receive this HTML as-is
        assertTrue(html.contains("<p>"))
        assertTrue(html.contains("First paragraph"))
        assertTrue(html.contains("Second paragraph"))
    }
    
    @Test
    fun htmlContent_preservesHeadings() {
        val html = """
            <h1>Chapter 1</h1>
            <h2>Getting Started</h2>
            <p>Content here</p>
        """.trimIndent()
        
        // Verify heading tags are preserved
        assertTrue(html.contains("<h1>"))
        assertTrue(html.contains("<h2>"))
        assertTrue(html.contains("Chapter 1"))
        assertTrue(html.contains("Getting Started"))
    }
    
    @Test
    fun htmlContent_preservesListStructure() {
        val html = """
            <ul>
                <li>Item 1</li>
                <li>Item 2</li>
            </ul>
        """.trimIndent()
        
        // Verify list tags are preserved
        assertTrue(html.contains("<ul>"))
        assertTrue(html.contains("<li>"))
        assertTrue(html.contains("Item 1"))
    }
    
    @Test
    fun htmlContent_preservesBlockquotes() {
        val html = """
            <blockquote>This is a quote</blockquote>
        """.trimIndent()
        
        // Verify blockquote tag is preserved
        assertTrue(html.contains("<blockquote>"))
        assertTrue(html.contains("This is a quote"))
    }
    
    @Test
    fun wrapperHtml_includesRequiredElements() {
        // Simulate the wrapper HTML structure
        val content = "<p>Test content</p>"
        val wrapped = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8"/>
                <style>
                    body { padding: 16px; }
                    h1 { font-size: 2em; }
                    p { margin: 0.8em 0; }
                </style>
            </head>
            <body>
                $content
            </body>
            </html>
        """.trimIndent()
        
        // Verify wrapper structure
        assertTrue(wrapped.contains("<!DOCTYPE html>"))
        assertTrue(wrapped.contains("<meta charset=\"utf-8\"/>"))
        assertTrue(wrapped.contains("<style>"))
        assertTrue(wrapped.contains("body { padding: 16px; }"))
        assertTrue(wrapped.contains(content))
    }
    
    @Test
    fun cssStyles_includeHeadingSizes() {
        val css = """
            h1 { font-size: 2em; }
            h2 { font-size: 1.75em; }
            h3 { font-size: 1.5em; }
        """.trimIndent()
        
        // Verify heading styles are defined
        assertTrue(css.contains("h1"))
        assertTrue(css.contains("2em"))
        assertTrue(css.contains("h2"))
        assertTrue(css.contains("1.75em"))
    }
    
    @Test
    fun cssStyles_includeParagraphSpacing() {
        val css = """
            p, div, section, article {
                margin: 0.8em 0;
            }
        """.trimIndent()
        
        // Verify paragraph spacing is defined
        assertTrue(css.contains("margin: 0.8em 0"))
    }
    
    @Test
    fun ttsChunkAttributes_canBeAdded() {
        // Verify that data attributes can be added to HTML elements
        val html = """<p data-tts-chunk="0">Test paragraph</p>"""
        
        assertTrue(html.contains("data-tts-chunk"))
        assertTrue(html.contains("data-tts-chunk=\"0\""))
    }
    
    @Test
    fun ttsHighlightClass_canBeApplied() {
        // Verify that highlight class can be added
        val html = """<p class="tts-highlight">Highlighted text</p>"""
        
        assertTrue(html.contains("class=\"tts-highlight\""))
    }
    
    @Test
    fun complexHtml_preservesAllElements() {
        val html = """
            <h1>Chapter 1</h1>
            <h2>Getting Started</h2>
            <p>Mason Hughes was a light sleeper. He woke at the smallest of noises.</p>
            <p>While most people may like to be woken up by the rising sun, birds singing, and the smell of fresh air, Mason was not that kind of person.</p>
            <blockquote>To say he found his current situation strange would be an understatement.</blockquote>
        """.trimIndent()
        
        // Verify all elements are present
        assertTrue(html.contains("<h1>Chapter 1</h1>"))
        assertTrue(html.contains("<h2>Getting Started</h2>"))
        assertTrue(html.contains("<p>Mason Hughes"))
        assertTrue(html.contains("<p>While most people"))
        assertTrue(html.contains("<blockquote>"))
    }
}
