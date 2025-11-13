package com.rifters.riftedreader

import com.rifters.riftedreader.domain.parser.TxtParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit test for TxtParser thread safety with concurrent access
 */
class TxtParserConcurrencyTest {
    
    @get:Rule
    val tempFolder = TemporaryFolder()
    
    private lateinit var parser: TxtParser
    private lateinit var testFile: File
    
    @Before
    fun setup() {
        parser = TxtParser()
        testFile = tempFolder.newFile("test.txt")
        testFile.writeText("This is a test file.\nIt has multiple lines.\nUsed for testing concurrent access.")
    }
    
    @Test
    fun testConcurrentMetadataExtraction() = runBlocking {
        // Launch multiple coroutines concurrently to extract metadata
        val jobs = List(10) {
            launch(Dispatchers.IO) {
                val metadata = parser.extractMetadata(testFile)
                assertEquals("test", metadata.title)
                assertEquals("TXT", metadata.format)
                assertTrue(metadata.size > 0)
            }
        }
        
        // Wait for all jobs to complete
        jobs.forEach { it.join() }
    }
    
    @Test
    fun testConcurrentPageContentAccess() = runBlocking {
        // Launch multiple coroutines concurrently to read page content
        val jobs = List(10) {
            launch(Dispatchers.IO) {
                val content = parser.getPageContent(testFile, 0)
                assertTrue(content.isNotEmpty())
                assertTrue(content.contains("This is a test file"))
            }
        }
        
        // Wait for all jobs to complete
        jobs.forEach { it.join() }
    }
    
    @Test
    fun testConcurrentPageCountCalculation() = runBlocking {
        // Launch multiple coroutines concurrently to get page count
        val jobs = List(10) {
            launch(Dispatchers.IO) {
                val pageCount = parser.getPageCount(testFile)
                assertTrue(pageCount > 0)
            }
        }
        
        // Wait for all jobs to complete
        jobs.forEach { it.join() }
    }
    
    @Test
    fun testConcurrentAccessWithMultipleFiles() = runBlocking {
        // Create multiple test files
        val files = List(5) { index ->
            tempFolder.newFile("test$index.txt").apply {
                writeText("File $index content\n".repeat(50))
            }
        }
        
        // Launch concurrent operations on different files
        val jobs = files.flatMap { file ->
            List(5) {
                launch(Dispatchers.IO) {
                    val metadata = parser.extractMetadata(file)
                    val pageCount = parser.getPageCount(file)
                    val content = parser.getPageContent(file, 0)
                    
                    assertTrue(metadata.title.startsWith("test"))
                    assertTrue(pageCount > 0)
                    assertTrue(content.isNotEmpty())
                }
            }
        }
        
        // Wait for all jobs to complete
        jobs.forEach { it.join() }
    }
}
