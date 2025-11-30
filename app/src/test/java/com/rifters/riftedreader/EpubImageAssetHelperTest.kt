package com.rifters.riftedreader

import com.rifters.riftedreader.util.EpubImageAssetHelper
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for EpubImageAssetHelper functionality.
 * 
 * Tests cover:
 * - URL parsing with AssetParts extraction
 * - MIME type guessing
 * - Path safety validation
 * - Path containment checks
 * - Mapping registry operations
 */
class EpubImageAssetHelperTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ================================================================================
    // parseAssetUrl Tests
    // ================================================================================

    @Test
    fun `parseAssetUrl returns null for non-matching URL`() {
        val result = EpubImageAssetHelper.parseAssetUrl("https://example.com/image.jpg")
        assertNull("Non-matching URL should return null", result)
    }

    @Test
    fun `parseAssetUrl returns null for empty relative path`() {
        val result = EpubImageAssetHelper.parseAssetUrl(EpubImageAssetHelper.EPUB_IMAGES_BASE_URL)
        assertNull("Empty relative path should return null", result)
    }

    @Test
    fun `parseAssetUrl returns null for single segment path`() {
        val url = "${EpubImageAssetHelper.EPUB_IMAGES_BASE_URL}image.jpg"
        val result = EpubImageAssetHelper.parseAssetUrl(url)
        assertNull("Single segment path should return null (needs at least book and file)", result)
    }

    @Test
    fun `parseAssetUrl extracts bookName and fileName from two segment path`() {
        val url = "${EpubImageAssetHelper.EPUB_IMAGES_BASE_URL}MyBook/cover.jpg"
        val result = EpubImageAssetHelper.parseAssetUrl(url)
        
        assertNotNull("Two segment path should parse successfully", result)
        assertEquals("MyBook", result?.bookName)
        assertNull("Chapter index should be null for two segment path", result?.chapterIndex)
        assertEquals("cover.jpg", result?.fileName)
    }

    @Test
    fun `parseAssetUrl extracts all parts from three segment path with chapter`() {
        val url = "${EpubImageAssetHelper.EPUB_IMAGES_BASE_URL}MyBook/chapter_5/image.png"
        val result = EpubImageAssetHelper.parseAssetUrl(url)
        
        assertNotNull("Three segment path should parse successfully", result)
        assertEquals("MyBook", result?.bookName)
        assertEquals(5, result?.chapterIndex)
        assertEquals("image.png", result?.fileName)
    }

    @Test
    fun `parseAssetUrl handles chapter index zero`() {
        val url = "${EpubImageAssetHelper.EPUB_IMAGES_BASE_URL}Book/chapter_0/img.gif"
        val result = EpubImageAssetHelper.parseAssetUrl(url)
        
        assertNotNull(result)
        assertEquals(0, result?.chapterIndex)
    }

    @Test
    fun `parseAssetUrl handles non-chapter middle segment`() {
        val url = "${EpubImageAssetHelper.EPUB_IMAGES_BASE_URL}Book/OEBPS_images/file.webp"
        val result = EpubImageAssetHelper.parseAssetUrl(url)
        
        assertNotNull(result)
        assertEquals("Book", result?.bookName)
        assertNull("Non-chapter middle segment should result in null chapterIndex", result?.chapterIndex)
        assertEquals("file.webp", result?.fileName)
    }

    @Test
    fun `parseAssetUrl handles encoded special characters`() {
        // Note: In test environment, Uri.decode returns null so encoding is not decoded.
        // This test validates that the parsing still works with encoded paths.
        val url = "${EpubImageAssetHelper.EPUB_IMAGES_BASE_URL}My%20Book/chapter_1/image%20file.jpg"
        val result = EpubImageAssetHelper.parseAssetUrl(url)
        
        assertNotNull(result)
        // In test environment without proper Uri.decode, we get the encoded values
        assertEquals("My%20Book", result?.bookName)
        assertEquals("image%20file.jpg", result?.fileName)
    }

    // ================================================================================
    // guessMime Tests
    // ================================================================================

    @Test
    fun `guessMime returns correct MIME for jpg`() {
        assertEquals("image/jpeg", EpubImageAssetHelper.guessMime("photo.jpg"))
    }

    @Test
    fun `guessMime returns correct MIME for jpeg`() {
        assertEquals("image/jpeg", EpubImageAssetHelper.guessMime("photo.jpeg"))
    }

    @Test
    fun `guessMime returns correct MIME for png`() {
        assertEquals("image/png", EpubImageAssetHelper.guessMime("image.png"))
    }

    @Test
    fun `guessMime returns correct MIME for gif`() {
        assertEquals("image/gif", EpubImageAssetHelper.guessMime("animation.gif"))
    }

    @Test
    fun `guessMime returns correct MIME for webp`() {
        assertEquals("image/webp", EpubImageAssetHelper.guessMime("modern.webp"))
    }

    @Test
    fun `guessMime returns correct MIME for svg`() {
        assertEquals("image/svg+xml", EpubImageAssetHelper.guessMime("vector.svg"))
    }

    @Test
    fun `guessMime returns correct MIME for bmp`() {
        assertEquals("image/bmp", EpubImageAssetHelper.guessMime("bitmap.bmp"))
    }

    @Test
    fun `guessMime returns correct MIME for ico`() {
        assertEquals("image/x-icon", EpubImageAssetHelper.guessMime("favicon.ico"))
    }

    @Test
    fun `guessMime returns octet-stream for unknown extension`() {
        assertEquals("application/octet-stream", EpubImageAssetHelper.guessMime("file.xyz"))
    }

    @Test
    fun `guessMime returns octet-stream for no extension`() {
        assertEquals("application/octet-stream", EpubImageAssetHelper.guessMime("noextension"))
    }

    @Test
    fun `guessMime is case insensitive`() {
        assertEquals("image/jpeg", EpubImageAssetHelper.guessMime("photo.JPG"))
        assertEquals("image/png", EpubImageAssetHelper.guessMime("IMAGE.PNG"))
    }

    // ================================================================================
    // isPathSafe Tests
    // ================================================================================

    @Test
    fun `isPathSafe returns false for empty path`() {
        assertFalse(EpubImageAssetHelper.isPathSafe(""))
    }

    @Test
    fun `isPathSafe returns false for parent traversal`() {
        assertFalse(EpubImageAssetHelper.isPathSafe("../secret.txt"))
        assertFalse(EpubImageAssetHelper.isPathSafe("book/../../../etc/passwd"))
        assertFalse(EpubImageAssetHelper.isPathSafe("chapter_1/../../secret"))
    }

    @Test
    fun `isPathSafe returns false for dot segment`() {
        assertFalse(EpubImageAssetHelper.isPathSafe("./file.txt"))
        assertFalse(EpubImageAssetHelper.isPathSafe("book/./image.jpg"))
    }

    @Test
    fun `isPathSafe returns true for valid paths`() {
        assertTrue(EpubImageAssetHelper.isPathSafe("book/chapter_1/image.jpg"))
        assertTrue(EpubImageAssetHelper.isPathSafe("MyBook/cover.png"))
        assertTrue(EpubImageAssetHelper.isPathSafe("book"))
    }

    @Test
    fun `isPathSafe handles multiple slashes`() {
        assertTrue(EpubImageAssetHelper.isPathSafe("book//chapter/image.jpg"))
        assertTrue(EpubImageAssetHelper.isPathSafe("/book/image.jpg"))
    }

    // ================================================================================
    // isContainedWithin Tests
    // ================================================================================

    @Test
    fun `isContainedWithin returns true for file within root`() {
        val root = tempFolder.newFolder("cache")
        val subdir = File(root, "book/chapter")
        subdir.mkdirs()
        val file = File(subdir, "image.jpg")
        file.createNewFile()
        
        assertTrue(EpubImageAssetHelper.isContainedWithin(file, root))
    }

    @Test
    fun `isContainedWithin returns false for file outside root`() {
        val root = tempFolder.newFolder("cache")
        val outside = tempFolder.newFile("secret.txt")
        
        assertFalse(EpubImageAssetHelper.isContainedWithin(outside, root))
    }

    @Test
    fun `isContainedWithin returns false for sibling with common prefix`() {
        val root = tempFolder.newFolder("cache")
        val sibling = tempFolder.newFolder("cache-attacker")
        val malicious = File(sibling, "data.txt")
        malicious.createNewFile()
        
        assertFalse(EpubImageAssetHelper.isContainedWithin(malicious, root))
    }

    @Test
    fun `isContainedWithin returns true for root itself`() {
        val root = tempFolder.newFolder("cache")
        assertTrue(EpubImageAssetHelper.isContainedWithin(root, root))
    }

    // ================================================================================
    // Mapping Registry Tests
    // ================================================================================

    @Test
    fun `recordMapping and dumpMappings work correctly`() {
        // Clear any previous state
        EpubImageAssetHelper.clearMappings()
        
        val entry1 = EpubImageAssetHelper.MappingEntry(
            originalSrc = "images/cover.jpg",
            resolvedPath = "OEBPS/images/cover.jpg",
            cacheFile = "/cache/book/chapter_0/cover.jpg",
            assetUrl = "https://appassets.androidplatform.net/epub-images/book/chapter_0/cover.jpg",
            chapterIndex = 0
        )
        
        val entry2 = EpubImageAssetHelper.MappingEntry(
            originalSrc = "../images/photo.png",
            resolvedPath = "images/photo.png",
            cacheFile = "/cache/book/chapter_1/photo.png",
            assetUrl = "https://appassets.androidplatform.net/epub-images/book/chapter_1/photo.png",
            chapterIndex = 1
        )
        
        EpubImageAssetHelper.recordMapping(entry1)
        EpubImageAssetHelper.recordMapping(entry2)
        
        val mappings = EpubImageAssetHelper.dumpMappings()
        assertEquals(2, mappings.size)
        assertEquals(entry1, mappings[0])
        assertEquals(entry2, mappings[1])
    }

    @Test
    fun `clearMappings removes all entries`() {
        EpubImageAssetHelper.recordMapping(
            EpubImageAssetHelper.MappingEntry(
                originalSrc = "test.jpg",
                resolvedPath = "test.jpg",
                cacheFile = "/cache/test.jpg",
                assetUrl = "https://appassets.androidplatform.net/epub-images/test.jpg",
                chapterIndex = 0
            )
        )
        
        EpubImageAssetHelper.clearMappings()
        
        val mappings = EpubImageAssetHelper.dumpMappings()
        assertTrue(mappings.isEmpty())
    }

    @Test
    fun `dumpMappings returns immutable copy`() {
        EpubImageAssetHelper.clearMappings()
        
        val entry = EpubImageAssetHelper.MappingEntry(
            originalSrc = "img.jpg",
            resolvedPath = "img.jpg",
            cacheFile = "/cache/img.jpg",
            assetUrl = "https://appassets.androidplatform.net/epub-images/img.jpg",
            chapterIndex = 0
        )
        EpubImageAssetHelper.recordMapping(entry)
        
        val mappings = EpubImageAssetHelper.dumpMappings()
        
        // Record another entry after dump
        val entry2 = EpubImageAssetHelper.MappingEntry(
            originalSrc = "img2.jpg",
            resolvedPath = "img2.jpg",
            cacheFile = "/cache/img2.jpg",
            assetUrl = "https://appassets.androidplatform.net/epub-images/img2.jpg",
            chapterIndex = 1
        )
        EpubImageAssetHelper.recordMapping(entry2)
        
        // Original dump should still have only 1 entry
        assertEquals(1, mappings.size)
        
        // New dump should have 2 entries
        assertEquals(2, EpubImageAssetHelper.dumpMappings().size)
    }
}
