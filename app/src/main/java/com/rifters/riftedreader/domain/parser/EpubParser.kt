package com.rifters.riftedreader.domain.parser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.rifters.riftedreader.data.database.entities.BookMeta
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File
import java.io.FileOutputStream
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
        var coverPath: String? = null
        
        ZipFile(file).use { zip ->
            // Parse metadata from OPF file
            val opfPath = getOpfPath(zip)
            val pageCount = getSpineItems(zip).size
            
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
                    
                    // Extract and save cover image
                    coverPath = extractCoverImage(file, zip, doc, opfPath)
                }
            }
            
            return BookMeta(
                path = file.absolutePath,
                title = title,
                author = author,
                publisher = publisher,
                format = "EPUB",
                size = file.length(),
                totalPages = pageCount,
                coverPath = coverPath,
                dateAdded = System.currentTimeMillis()
            )
        }
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
                val text = body.text()
                
                // Extract HTML with better structure preservation
                // Clean the HTML but preserve important formatting elements
                val cleanedHtml = cleanHtmlForDisplay(body)
                
                return PageContent(text = text, html = cleanedHtml)
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
        ZipFile(file).use { zip ->
            val opfPath = getOpfPath(zip) ?: return emptyList()
            
            // Try to find NCX file (EPUB 2) or nav document (EPUB 3)
            val tocEntries = parseNcxToc(zip, opfPath) ?: parseNavToc(zip, opfPath)
            
            if (tocEntries != null && tocEntries.isNotEmpty()) {
                return tocEntries
            }
            
            // Fallback: use spine items with file names
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
    
    /**
     * Extract cover image from EPUB and save to cache directory
     */
    private fun extractCoverImage(bookFile: File, zip: ZipFile, opfDoc: Document, opfPath: String): String? {
        try {
            // Method 1: Look for cover in metadata
            var coverId = opfDoc.select("metadata > meta[name=cover]").attr("content")
            
            // Method 2: Look for cover-image property in manifest
            if (coverId.isBlank()) {
                coverId = opfDoc.select("manifest > item[properties*=cover-image]").attr("id")
            }
            
            // Method 3: Look for common cover file names in manifest
            if (coverId.isBlank()) {
                val coverItem = opfDoc.select("manifest > item").firstOrNull { item ->
                    val href = item.attr("href").lowercase()
                    val id = item.attr("id").lowercase()
                    href.contains("cover") || id.contains("cover")
                }
                coverId = coverItem?.attr("id") ?: ""
            }
            
            // Get the image path from manifest
            var imagePath: String? = null
            if (coverId.isNotBlank()) {
                imagePath = opfDoc.select("manifest > item[id=$coverId]").attr("href")
            }
            
            // If we still don't have a cover, try common file names directly
            if (imagePath.isNullOrBlank()) {
                val commonCoverNames = listOf("cover.jpg", "cover.jpeg", "cover.png", "Cover.jpg", "Cover.jpeg", "Cover.png")
                val opfDir = opfPath.substringBeforeLast('/')
                imagePath = commonCoverNames.firstOrNull { name ->
                    val fullPath = if (opfDir.isNotBlank()) "$opfDir/$name" else name
                    zip.getEntry(fullPath) != null
                }
            }
            
            if (imagePath.isNullOrBlank()) {
                return null
            }
            
            // Construct full path relative to OPF
            val opfDir = opfPath.substringBeforeLast('/')
            val fullImagePath = if (opfDir.isNotBlank() && !imagePath.startsWith("/")) {
                "$opfDir/$imagePath"
            } else {
                imagePath.removePrefix("/")
            }
            
            // Extract and save the image
            val imageEntry = zip.getEntry(fullImagePath) ?: return null
            val imageBytes = zip.getInputStream(imageEntry).readBytes()
            
            // Decode bitmap to verify it's a valid image
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null
            
            // Save to cache directory
            val cacheDir = File(bookFile.parentFile, ".covers")
            cacheDir.mkdirs()
            val coverFile = File(cacheDir, "${bookFile.nameWithoutExtension}_cover.jpg")
            
            FileOutputStream(coverFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            
            return coverFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
    
    /**
     * Clean HTML for display while preserving important formatting
     */
    private fun cleanHtmlForDisplay(body: Element): String {
        // Remove script and style tags
        body.select("script, style").remove()
        
        // Get the HTML content
        val html = body.html()
        
        // If HTML is empty or trivial, return null to use plain text
        if (html.isBlank() || !html.contains("<")) {
            return ""
        }
        
        return html
    }
    
    /**
     * Parse NCX file for TOC (EPUB 2)
     */
    private fun parseNcxToc(zip: ZipFile, opfPath: String): List<TocEntry>? {
        try {
            // Find NCX file from OPF
            val opfEntry = zip.getEntry(opfPath) ?: return null
            val opfContent = zip.getInputStream(opfEntry).bufferedReader().readText()
            val opfDoc = Jsoup.parse(opfContent, "", org.jsoup.parser.Parser.xmlParser())
            
            // Get NCX file path from manifest
            val ncxId = opfDoc.select("spine").attr("toc")
            val ncxHref = opfDoc.select("manifest > item[id=$ncxId]").attr("href")
            
            if (ncxHref.isBlank()) {
                return null
            }
            
            // Construct full NCX path
            val opfDir = opfPath.substringBeforeLast('/')
            val ncxPath = if (opfDir.isNotBlank()) "$opfDir/$ncxHref" else ncxHref
            
            val ncxEntry = zip.getEntry(ncxPath) ?: return null
            val ncxContent = zip.getInputStream(ncxEntry).bufferedReader().readText()
            val ncxDoc = Jsoup.parse(ncxContent, "", org.jsoup.parser.Parser.xmlParser())
            
            // Build spine href to index map
            val spineItems = getSpineItems(zip)
            val hrefToIndex = mutableMapOf<String, Int>()
            spineItems.forEachIndexed { index, path ->
                val href = path.substringAfterLast('/')
                hrefToIndex[href] = index
                // Also store without anchor
                hrefToIndex[href.substringBefore('#')] = index
            }
            
            // Parse nav points
            val entries = mutableListOf<TocEntry>()
            parseNavPoints(ncxDoc.select("navMap > navPoint"), entries, 0, hrefToIndex, opfDir)
            
            return if (entries.isNotEmpty()) entries else null
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
    
    /**
     * Recursively parse NCX navigation points
     */
    private fun parseNavPoints(
        navPoints: org.jsoup.select.Elements,
        entries: MutableList<TocEntry>,
        level: Int,
        hrefToIndex: Map<String, Int>,
        baseDir: String
    ) {
        navPoints.forEach { navPoint ->
            val label = navPoint.select("navLabel > text").text()
            val src = navPoint.select("content").attr("src")
            
            if (label.isNotBlank() && src.isNotBlank()) {
                // Extract file name and anchor
                val srcFile = src.substringBefore('#')
                val fileName = srcFile.substringAfterLast('/')
                
                // Find page number from spine
                val pageNumber = hrefToIndex[fileName] ?: hrefToIndex[srcFile] ?: 0
                
                entries.add(TocEntry(
                    title = label,
                    pageNumber = pageNumber,
                    level = level
                ))
            }
            
            // Recursively parse child nav points
            val childNavPoints = navPoint.select("> navPoint")
            if (childNavPoints.isNotEmpty()) {
                parseNavPoints(childNavPoints, entries, level + 1, hrefToIndex, baseDir)
            }
        }
    }
    
    /**
     * Parse nav.xhtml for TOC (EPUB 3)
     */
    private fun parseNavToc(zip: ZipFile, opfPath: String): List<TocEntry>? {
        try {
            // Find nav document from OPF
            val opfEntry = zip.getEntry(opfPath) ?: return null
            val opfContent = zip.getInputStream(opfEntry).bufferedReader().readText()
            val opfDoc = Jsoup.parse(opfContent, "", org.jsoup.parser.Parser.xmlParser())
            
            // Get nav file path from manifest (properties="nav")
            val navHref = opfDoc.select("manifest > item[properties*=nav]").attr("href")
            
            if (navHref.isBlank()) {
                return null
            }
            
            // Construct full nav path
            val opfDir = opfPath.substringBeforeLast('/')
            val navPath = if (opfDir.isNotBlank()) "$opfDir/$navHref" else navHref
            
            val navEntry = zip.getEntry(navPath) ?: return null
            val navContent = zip.getInputStream(navEntry).bufferedReader().readText()
            val navDoc = Jsoup.parse(navContent)
            
            // Build spine href to index map
            val spineItems = getSpineItems(zip)
            val hrefToIndex = mutableMapOf<String, Int>()
            spineItems.forEachIndexed { index, path ->
                val href = path.substringAfterLast('/')
                hrefToIndex[href] = index
                hrefToIndex[href.substringBefore('#')] = index
            }
            
            // Parse nav element with toc type
            val tocNav = navDoc.select("nav[*|type=toc], nav#toc").firstOrNull() ?: return null
            
            // Parse list items
            val entries = mutableListOf<TocEntry>()
            parseNavList(tocNav.select("> ol > li"), entries, 0, hrefToIndex)
            
            return if (entries.isNotEmpty()) entries else null
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
    
    /**
     * Recursively parse EPUB 3 nav list items
     */
    private fun parseNavList(
        listItems: org.jsoup.select.Elements,
        entries: MutableList<TocEntry>,
        level: Int,
        hrefToIndex: Map<String, Int>
    ) {
        listItems.forEach { li ->
            val link = li.select("> a, > span > a").firstOrNull()
            if (link != null) {
                val label = link.text()
                val href = link.attr("href")
                
                if (label.isNotBlank() && href.isNotBlank()) {
                    // Extract file name
                    val srcFile = href.substringBefore('#')
                    val fileName = srcFile.substringAfterLast('/')
                    
                    // Find page number from spine
                    val pageNumber = hrefToIndex[fileName] ?: hrefToIndex[srcFile] ?: 0
                    
                    entries.add(TocEntry(
                        title = label,
                        pageNumber = pageNumber,
                        level = level
                    ))
                }
            }
            
            // Recursively parse nested lists
            val nestedList = li.select("> ol > li")
            if (nestedList.isNotEmpty()) {
                parseNavList(nestedList, entries, level + 1, hrefToIndex)
            }
        }
    }
}
