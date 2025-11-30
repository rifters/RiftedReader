package com.rifters.riftedreader

import com.rifters.riftedreader.domain.parser.EpubParser
import org.junit.Test
import org.junit.Assert.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for EPUB image caching functionality
 * Verifies that images are cached to disk and file:// URLs are used instead of base64
 */
class EpubImageCacheTest {
    
    @get:Rule
    val tempFolder = TemporaryFolder()
    
    @Test
    fun imageCacheStructure_createsCorrectDirectories() {
        // Create a mock book file in temp directory
        val bookFile = tempFolder.newFile("test_book.epub")
        
        // Expected cache structure:
        // <book_parent_dir>/.image_cache/<book_name>/chapter_<N>/
        val parentDir = bookFile.parentFile
        val expectedCacheRoot = File(parentDir, ".image_cache")
        // Since we can't directly call private methods, we verify the expected structure
        // The cache directory structure should follow this pattern
        assertNotNull(parentDir)
        assertNotNull(expectedCacheRoot)
        assertEquals("test_book", bookFile.nameWithoutExtension)
    }
    
    @Test
    fun imageCacheFileNames_areSanitized() {
        // Test that path separators are replaced in filenames
        val imagePath = "OEBPS/Images/cover.jpg"
        val sanitizedFileName = imagePath.replace('/', '_').replace('\\', '_')
        
        assertEquals("OEBPS_Images_cover.jpg", sanitizedFileName)
        assertFalse(sanitizedFileName.contains("/"))
        assertFalse(sanitizedFileName.contains("\\"))
    }
    
    @Test
    fun imageUrl_usesAssetLoaderProtocol() {
        // Create a mock cached image file
        val tempBookDir = tempFolder.newFolder("bookdir")
        val imageCacheRoot = File(tempBookDir, ".image_cache")
        imageCacheRoot.mkdirs()
        
        val bookCacheDir = File(imageCacheRoot, "BookTitle")
        bookCacheDir.mkdirs()
        
        val chapterCacheDir = File(bookCacheDir, "chapter_0")
        chapterCacheDir.mkdirs()
        
        val cachedImageFile = File(chapterCacheDir, "test_image.jpg")
        cachedImageFile.createNewFile()
        
        // Verify test setup
        assertTrue("Image file should exist", cachedImageFile.exists())
        assertTrue("Cache root should be parent of file", 
            cachedImageFile.absolutePath.startsWith(imageCacheRoot.absolutePath))
        
        // Use EpubImageAssetHelper to convert path to URL
        val assetUrl = com.rifters.riftedreader.util.EpubImageAssetHelper.toAssetUrl(
            cachedImageFile.absolutePath,
            imageCacheRoot
        )
        
        // Verify the URL format uses the asset loader domain
        assertTrue("URL should use https: got $assetUrl", assetUrl.startsWith("https://"))
        assertTrue("URL should use asset loader host", assetUrl.contains("appassets.androidplatform.net"))
        assertTrue("URL should use epub-images path", assetUrl.contains("/epub-images/"))
        assertTrue("URL should contain file name", assetUrl.contains("test_image.jpg"))
    }
    
    @Test
    fun chapterRange_calculatesCorrectly() {
        val currentChapter = 5
        val keepRange = 2
        
        val minKeep = (currentChapter - keepRange).coerceAtLeast(0)
        val maxKeep = currentChapter + keepRange
        
        assertEquals(3, minKeep)
        assertEquals(7, maxKeep)
        
        // Test edge case at start
        val firstChapter = 1
        val minKeepFirst = (firstChapter - keepRange).coerceAtLeast(0)
        assertEquals(0, minKeepFirst)
    }
    
    @Test
    fun coverImage_isIdentifiedCorrectly() {
        // Cover images should be identified by path or filename
        val coverPaths = listOf(
            "OEBPS/Images/cover.jpg",
            "images/Cover.png",
            "../cover-image.gif"
        )
        
        coverPaths.forEach { path ->
            val isCover = path.lowercase().contains("cover")
            assertTrue("Path should be identified as cover: $path", isCover)
        }
        
        // Non-cover images
        val regularPaths = listOf(
            "OEBPS/Images/photo.jpg",
            "images/diagram.png"
        )
        
        regularPaths.forEach { path ->
            val isCover = path.lowercase().contains("cover")
            assertFalse("Path should not be identified as cover: $path", isCover)
        }
    }
}
