package com.rifters.riftedreader

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Security tests for path traversal prevention in EpubImagePathHandler.
 * 
 * These tests verify that the path traversal check properly validates
 * directory boundaries to prevent access to files outside the cache root.
 */
class EpubImagePathHandlerSecurityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * Helper function that mimics the security check logic from EpubImagePathHandler.
     * We test this logic directly since EpubImagePathHandler depends on Android classes.
     */
    private fun isPathWithinCacheRoot(canonicalImagePath: String, canonicalCacheRoot: String): Boolean {
        val cacheRootWithSeparator = if (canonicalCacheRoot.endsWith(File.separator)) {
            canonicalCacheRoot
        } else {
            canonicalCacheRoot + File.separator
        }
        return canonicalImagePath.startsWith(cacheRootWithSeparator) || canonicalImagePath == canonicalCacheRoot
    }

    @Test
    fun `valid path within cache root should be allowed`() {
        val cacheRoot = File(tempFolder.root, ".image_cache")
        cacheRoot.mkdirs()
        
        val imageFile = File(cacheRoot, "book/chapter_1/image.jpg")
        imageFile.parentFile?.mkdirs()
        imageFile.createNewFile()
        
        val result = isPathWithinCacheRoot(imageFile.canonicalPath, cacheRoot.canonicalPath)
        assertTrue("Valid path within cache root should be allowed", result)
    }

    @Test
    fun `path with parent traversal should be blocked`() {
        val cacheRoot = File(tempFolder.root, ".image_cache")
        cacheRoot.mkdirs()
        
        // Create a file outside the cache root
        val outsideFile = File(tempFolder.root, "secret.txt")
        outsideFile.createNewFile()
        
        // Try to access it via path traversal
        val traversalPath = File(cacheRoot, "../secret.txt")
        
        val result = isPathWithinCacheRoot(traversalPath.canonicalPath, cacheRoot.canonicalPath)
        assertFalse("Path with parent traversal should be blocked", result)
    }

    @Test
    fun `sibling directory with common prefix should be blocked`() {
        // This is the specific vulnerability described in the issue:
        // If cacheRoot is /storage/.../Download/.image_cache, 
        // a path like /storage/.../Download/.image_cache-attacker/secret.txt
        // would pass the old startsWith check but should be blocked.
        
        val cacheRoot = File(tempFolder.root, ".image_cache")
        cacheRoot.mkdirs()
        
        // Create a sibling directory with a common prefix
        val siblingDir = File(tempFolder.root, ".image_cache-attacker")
        siblingDir.mkdirs()
        
        val maliciousFile = File(siblingDir, "secret.txt")
        maliciousFile.createNewFile()
        
        val result = isPathWithinCacheRoot(maliciousFile.canonicalPath, cacheRoot.canonicalPath)
        assertFalse("Sibling directory with common prefix should be blocked", result)
    }

    @Test
    fun `sibling directory with suffix should be blocked`() {
        val cacheRoot = File(tempFolder.root, ".image_cache")
        cacheRoot.mkdirs()
        
        // Create a sibling directory that shares the same prefix
        val siblingDir = File(tempFolder.root, ".image_cache_backup")
        siblingDir.mkdirs()
        
        val maliciousFile = File(siblingDir, "data.txt")
        maliciousFile.createNewFile()
        
        val result = isPathWithinCacheRoot(maliciousFile.canonicalPath, cacheRoot.canonicalPath)
        assertFalse("Sibling directory with suffix should be blocked", result)
    }

    @Test
    fun `cache root itself should be allowed`() {
        val cacheRoot = File(tempFolder.root, ".image_cache")
        cacheRoot.mkdirs()
        
        val result = isPathWithinCacheRoot(cacheRoot.canonicalPath, cacheRoot.canonicalPath)
        assertTrue("Cache root itself should be allowed", result)
    }

    @Test
    fun `nested directory within cache root should be allowed`() {
        val cacheRoot = File(tempFolder.root, ".image_cache")
        cacheRoot.mkdirs()
        
        val nestedDir = File(cacheRoot, "book/subdir/images")
        nestedDir.mkdirs()
        
        val imageFile = File(nestedDir, "photo.png")
        imageFile.createNewFile()
        
        val result = isPathWithinCacheRoot(imageFile.canonicalPath, cacheRoot.canonicalPath)
        assertTrue("Nested directory within cache root should be allowed", result)
    }

    @Test
    fun `old startsWith check would have allowed sibling directory attack`() {
        // This test demonstrates that the old check was vulnerable
        val cacheRoot = File(tempFolder.root, ".image_cache")
        cacheRoot.mkdirs()
        
        val siblingDir = File(tempFolder.root, ".image_cache-attacker")
        siblingDir.mkdirs()
        
        val maliciousFile = File(siblingDir, "secret.txt")
        maliciousFile.createNewFile()
        
        val canonicalImagePath = maliciousFile.canonicalPath
        val canonicalCacheRoot = cacheRoot.canonicalPath
        
        // Old vulnerable check - this would return true (allowing the attack)
        val oldCheckResult = canonicalImagePath.startsWith(canonicalCacheRoot)
        assertTrue("Old check was vulnerable to sibling directory attack", oldCheckResult)
        
        // New secure check - this correctly returns false
        val newCheckResult = isPathWithinCacheRoot(canonicalImagePath, canonicalCacheRoot)
        assertFalse("New check correctly blocks sibling directory attack", newCheckResult)
    }
}
