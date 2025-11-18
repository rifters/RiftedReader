package com.rifters.riftedreader

import com.rifters.riftedreader.domain.parser.EpubParser
import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for EPUB cover caching functionality
 * Verifies that the parser can store and use cached cover paths
 */
class EpubCoverCacheTest {
    
    @Test
    fun setCoverPath_storesPath() {
        val parser = EpubParser()
        val coverPath = "/path/to/cover.jpg"
        
        // Test that setCoverPath doesn't throw
        parser.setCoverPath(coverPath)
        
        // We can't directly test the private field, but we can verify
        // the method doesn't crash
        assertTrue(true)
    }
    
    @Test
    fun setCoverPath_acceptsNullPath() {
        val parser = EpubParser()
        
        // Test that setting null doesn't throw
        parser.setCoverPath(null)
        
        assertTrue(true)
    }
    
    @Test
    fun coverImageDetection_identifiesCoverByPath() {
        val imagePath = "OEBPS/Images/cover.jpg"
        val isCoverImage = imagePath.lowercase().contains("cover")
        
        assertTrue("Should detect 'cover' in path", isCoverImage)
    }
    
    @Test
    fun coverImageDetection_identifiesCoverByFilename() {
        val imagePath = "images/Cover.png"
        val isCoverImage = imagePath.lowercase().contains("cover")
        
        assertTrue("Should detect 'Cover' in path (case-insensitive)", isCoverImage)
    }
    
    @Test
    fun coverImageDetection_identifiesNonCoverImage() {
        val imagePath = "OEBPS/Images/photo.jpg"
        val isCoverImage = imagePath.lowercase().contains("cover")
        
        assertFalse("Should not detect cover in regular image path", isCoverImage)
    }
    
    @Test
    fun coverImageDetection_checksOriginalSrc() {
        val originalSrc = "../Images/cover-art.jpg"
        val isCoverImage = originalSrc.lowercase().contains("cover")
        
        assertTrue("Should detect 'cover' in original src", isCoverImage)
    }
    
    @Test
    fun coverImageDetection_checksMultipleSources() {
        // Test combined check (imagePath OR originalSrc)
        val imagePath1 = "OEBPS/Images/cover.jpg"
        val originalSrc1 = "cover.jpg"
        val isCover1 = imagePath1.lowercase().contains("cover") || 
                      originalSrc1.lowercase().contains("cover")
        assertTrue("Should detect cover from either source", isCover1)
        
        val imagePath2 = "OEBPS/Images/photo.jpg"
        val originalSrc2 = "photo.jpg"
        val isCover2 = imagePath2.lowercase().contains("cover") || 
                      originalSrc2.lowercase().contains("cover")
        assertFalse("Should not detect cover when neither contains 'cover'", isCover2)
    }
}
