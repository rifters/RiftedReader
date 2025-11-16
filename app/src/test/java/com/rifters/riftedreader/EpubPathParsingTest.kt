package com.rifters.riftedreader

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for EPUB path parsing logic
 * Tests the fix for root-level OPF file path parsing
 */
class EpubPathParsingTest {
    
    @Test
    fun substringBeforeLastSlash_withSlash_returnsDirectory() {
        val opfPath = "OEBPS/content.opf"
        val opfDir = if (opfPath.contains('/')) {
            opfPath.substringBeforeLast('/')
        } else {
            ""
        }
        assertEquals("OEBPS", opfDir)
    }
    
    @Test
    fun substringBeforeLastSlash_withoutSlash_returnsEmpty() {
        val opfPath = "content.opf"
        val opfDir = if (opfPath.contains('/')) {
            opfPath.substringBeforeLast('/')
        } else {
            ""
        }
        assertEquals("", opfDir)
    }
    
    @Test
    fun substringBeforeLastSlash_withMultipleSlashes_returnsParentDirectory() {
        val opfPath = "OEBPS/package/content.opf"
        val opfDir = if (opfPath.contains('/')) {
            opfPath.substringBeforeLast('/')
        } else {
            ""
        }
        assertEquals("OEBPS/package", opfDir)
    }
    
    @Test
    fun pathConstruction_withEmptyDir_returnsRelativePath() {
        val opfDir = ""
        val href = "cover.jpg"
        val fullPath = if (opfDir.isNotBlank()) "$opfDir/$href" else href
        assertEquals("cover.jpg", fullPath)
    }
    
    @Test
    fun pathConstruction_withDir_returnsCombinedPath() {
        val opfDir = "OEBPS"
        val href = "cover.jpg"
        val fullPath = if (opfDir.isNotBlank()) "$opfDir/$href" else href
        assertEquals("OEBPS/cover.jpg", fullPath)
    }
    
    @Test
    fun oldBehavior_withoutSlash_returnsFilename() {
        // This demonstrates the bug - old behavior returns the filename itself
        val opfPath = "content.opf"
        val opfDir = opfPath.substringBeforeLast('/')
        // Bug: returns "content.opf" instead of ""
        assertEquals("content.opf", opfDir)
    }
}
