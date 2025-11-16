package com.rifters.riftedreader.domain.parser

import com.rifters.riftedreader.data.database.entities.BookMeta
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.util.zip.ZipFile

/**
 * Parser for EPUB format
 */
class EpubParser : BookParser {
    
    companion object {
        private val SUPPORTED_EXTENSIONS = listOf("epub")
        private const val CONTAINER_PATH = "META-INF/container.xml"
    }
    
    override fun canParse(file: File): Boolean {
        return file.extension.lowercase() in SUPPORTED_EXTENSIONS
    }
    
    override suspend fun extractMetadata(file: File): BookMeta {
        var title = file.nameWithoutExtension
        var author: String? = null
        var publisher: String? = null
        var pageCount = 0
        
        ZipFile(file).use { zip ->
            // Parse metadata from OPF file
            val opfPath = getOpfPath(zip)
            if (opfPath != null) {
                val opfEntry = zip.getEntry(opfPath)
                if (opfEntry != null) {
                    val opfContent = zip.getInputStream(opfEntry).bufferedReader().readText()
                    val doc = Jsoup.parse(opfContent, "", org.jsoup.parser.Parser.xmlParser())
                    
                    // Extract title
                    doc.select("metadata > title, dc|title").firstOrNull()?.text()?.let {
                        if (it.isNotBlank()) title = it
                    }
                    
                    // Extract author
                    doc.select("metadata > creator, dc|creator").firstOrNull()?.text()?.let {
                        if (it.isNotBlank()) author = it
                    }
                    
                    // Extract publisher
                    doc.select("metadata > publisher, dc|publisher").firstOrNull()?.text()?.let {
                        if (it.isNotBlank()) publisher = it
                    }
                }
            }
            
            // Get page count from the already-open ZipFile to avoid redundant I/O
            pageCount = getSpineItems(zip).size
        }
        
        return BookMeta(
            path = file.absolutePath,
            title = title,
            author = author,
            publisher = publisher,
            format = "EPUB",
            size = file.length(),
            totalPages = pageCount,
            dateAdded = System.currentTimeMillis()
        )
    }
    
    override suspend fun getPageContent(file: File, page: Int): PageContent {
        ZipFile(file).use { zip ->
            val spine = getSpineItems(zip)
            if (page < 0 || page >= spine.size) {
                return PageContent.EMPTY
            }
            
            val contentPath = spine[page]
            val entry = zip.getEntry(contentPath)
            if (entry != null) {
                val content = zip.getInputStream(entry).bufferedReader().readText()
                val doc = Jsoup.parse(content)
                val body = doc.body()
                
                // Extract plain text for TTS and search
                val text = body?.text().orEmpty()
                
                // Extract HTML with better structure preservation
                // Use outerHtml() to get full body content with all tags preserved
                val bodyHtml = body?.html()
                
                // Only return HTML if it has actual content beyond just text
                val html = if (!bodyHtml.isNullOrBlank() && bodyHtml.contains("<")) {
                    bodyHtml
                } else {
                    null
                }
                
                return PageContent(text = text, html = html)
            }
        }
        return PageContent.EMPTY
    }
    
    override suspend fun getPageCount(file: File): Int {
        ZipFile(file).use { zip ->
            return getSpineItems(zip).size
        }
    }
    
    override suspend fun getTableOfContents(file: File): List<TocEntry> {
        // Simplified TOC - just return spine items
        ZipFile(file).use { zip ->
            val spine = getSpineItems(zip)
            return spine.mapIndexed { index, path ->
                TocEntry(
                    title = path.substringAfterLast('/').substringBeforeLast('.'),
                    pageNumber = index
                )
            }
        }
    }
    
    private fun getOpfPath(zip: ZipFile): String? {
        val containerEntry = zip.getEntry(CONTAINER_PATH) ?: return null
        val containerContent = zip.getInputStream(containerEntry).bufferedReader().readText()
        val doc = Jsoup.parse(containerContent, "", org.jsoup.parser.Parser.xmlParser())
        return doc.select("rootfile").attr("full-path")
    }
    
    private fun getSpineItems(zip: ZipFile): List<String> {
        val items = mutableListOf<String>()
        val opfPath = getOpfPath(zip) ?: return items
        val opfEntry = zip.getEntry(opfPath) ?: return items
        val opfContent = zip.getInputStream(opfEntry).bufferedReader().readText()
        val doc = Jsoup.parse(opfContent, "", org.jsoup.parser.Parser.xmlParser())
        
        // Build manifest map (id -> href)
        val manifest = mutableMapOf<String, String>()
        doc.select("manifest > item").forEach { item ->
            val id = item.attr("id")
            val href = item.attr("href")
            if (id.isNotBlank() && href.isNotBlank()) {
                manifest[id] = href
            }
        }
        
        // Get spine order
        val opfDir = opfPath.substringBeforeLast('/')
        doc.select("spine > itemref").forEach { itemref ->
            val idref = itemref.attr("idref")
            manifest[idref]?.let { href ->
                val fullPath = if (opfDir.isNotBlank()) "$opfDir/$href" else href
                items.add(fullPath)
            }
        }
        
        return items
    }
}
