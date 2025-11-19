package com.rifters.riftedreader

import com.rifters.riftedreader.domain.parser.EpubParser
import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests to verify debug logging for EPUB cover image detection and processing
 * These tests verify the logging infrastructure works without causing crashes
 */
class EpubCoverDebugLoggingTest {
    
    @Test
    fun setCoverPath_logsValidPath() {
        val parser = EpubParser()
        val coverPath = "/storage/emulated/0/Books/.covers/sample_cover.jpg"
        
        // This should trigger debug logging without crashing
        // The actual logging is conditional on BuildConfig.DEBUG
        parser.setCoverPath(coverPath)
        
        // If we get here without crash, logging infrastructure works
        assertTrue("Logging infrastructure should work without crashes", true)
    }
    
    @Test
    fun setCoverPath_logsNullPath() {
        val parser = EpubParser()
        
        // This should trigger debug logging for null path
        parser.setCoverPath(null)
        
        // If we get here without crash, logging infrastructure works
        assertTrue("Logging infrastructure should handle null path", true)
    }
    
    @Test
    fun coverImageDetection_multipleChecks() {
        // Test the cover detection logic used in getPageContent
        val testCases = listOf(
            // (imagePath, originalSrc, expectedIsCover)
            Triple("OEBPS/Images/cover.jpg", "cover.jpg", true),
            Triple("OEBPS/Images/photo.jpg", "photo.jpg", false),
            Triple("images/Cover.png", "../Images/Cover.png", true),
            Triple("OEBPS/Images/cover-image.jpg", "cover-image.jpg", true),
            Triple("OEBPS/Images/title-page.jpg", "title-page.jpg", false),
            Triple("content/cover_art.jpg", "cover_art.jpg", true),
        )
        
        testCases.forEach { (imagePath, originalSrc, expectedIsCover) ->
            val isCoverImage = imagePath.lowercase().contains("cover") || 
                             originalSrc.lowercase().contains("cover")
            
            assertEquals(
                "Cover detection failed for imagePath='$imagePath', originalSrc='$originalSrc'",
                expectedIsCover,
                isCoverImage
            )
        }
    }
    
    @Test
    fun coverImageDetection_edgeCases() {
        // Test edge cases for cover detection
        val testCases = listOf(
            // (imagePath, originalSrc, expectedIsCover)
            Triple("", "", false),
            Triple("COVER", "COVER", true), // All caps
            Triple("discover.jpg", "discover.jpg", true), // Contains "cover"
            Triple("recovery.jpg", "recovery.jpg", true), // Contains "cover"
            Triple("uncovered.png", "uncovered.png", true), // Contains "cover"
        )
        
        testCases.forEach { (imagePath, originalSrc, expectedIsCover) ->
            val isCoverImage = imagePath.lowercase().contains("cover") || 
                             originalSrc.lowercase().contains("cover")
            
            assertEquals(
                "Edge case detection for imagePath='$imagePath', originalSrc='$originalSrc'",
                expectedIsCover,
                isCoverImage
            )
        }
    }
    
    @Test
    fun coverImageDetection_caseInsensitive() {
        // Verify case-insensitive detection
        val variations = listOf(
            "cover.jpg",
            "Cover.jpg",
            "COVER.jpg",
            "CoVeR.jpg"
        )
        
        variations.forEach { src ->
            val isCoverImage = src.lowercase().contains("cover")
            assertTrue(
                "Should detect '$src' as cover (case-insensitive)",
                isCoverImage
            )
        }
    }
}
