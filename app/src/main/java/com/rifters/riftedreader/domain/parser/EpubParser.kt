package com.rifters.riftedreader.domain.parser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.rifters.riftedreader.data.database.entities.BookMeta
import com.rifters.riftedreader.util.AppLogger
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
    
    // Store cover path to use when rendering pages
    private var cachedCoverPath: String? = null
    
    /**
     * Set the cover path extracted during metadata extraction
     * This allows reusing the already-extracted cover when rendering pages
     */
    fun setCoverPath(coverPath: String?) {
        cachedCoverPath = coverPath
        AppLogger.d("EpubParser", "[COVER_DEBUG] setCoverPath called with: $coverPath")
        if (coverPath != null) {
            val coverFile = File(coverPath)
            AppLogger.d("EpubParser", "[COVER_DEBUG] Cached cover file exists: ${coverFile.exists()}, canRead: ${coverFile.canRead()}, absolutePath: ${coverFile.absolutePath}")
        }
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
                
                // Remove script and style tags for security, preserve all other HTML
                body.select("script, style").remove()
                
                // Process images: cache them on disk and use file:// URLs
                val contentDir = contentPath.substringBeforeLast('/', "")
                // Match both HTML img tags and SVG image elements
                // Note: We select all 'image' elements because JSoup has issues with xlink:href in CSS selectors
                val images = body.select("img[src], image[href], image")
                
                if (images.isNotEmpty()) {
                    AppLogger.d("EpubParser", "Found ${images.size} images on page $page")
                    
                    // Clean up old chapter caches to prevent disk bloat
                    cleanOldChapterCaches(file, page)
                }
                
                images.forEach { img ->
                    // Get src from either img[src], image[href], or image[xlink:href]
                    val originalSrc = when {
                        img.hasAttr("src") -> img.attr("src")
                        img.hasAttr("href") -> img.attr("href")
                        img.hasAttr("xlink:href") -> img.attr("xlink:href")
                        else -> {
                            AppLogger.w("EpubParser", "Image element has no src/href attribute on page $page")
                            return@forEach
                        }
                    }
                    
                    AppLogger.d("EpubParser", "Processing image on page $page: src='$originalSrc' (tag=${img.tagName()})")
                    
                    try {
                        // Resolve relative path
                        val imagePath = resolveRelativePath(contentDir, originalSrc)
                        AppLogger.d("EpubParser", "Resolved image path: '$imagePath' (from contentDir='$contentDir', originalSrc='$originalSrc')")
                        
                        // Check if this image is likely the cover image
                        val isCoverImage = imagePath.lowercase().contains("cover") || 
                                         originalSrc.lowercase().contains("cover")
                        
                        // If we have a cached cover path and this looks like a cover image, use it as base64 (existing behavior)
                        val coverPath = cachedCoverPath
                        
                        if (isCoverImage && coverPath != null && File(coverPath).exists()) {
                            AppLogger.d("EpubParser", "Using cached cover image (base64) from: $coverPath")
                            try {
                                val coverFile = File(coverPath)
                                val coverBytes = coverFile.readBytes()
                                val base64Cover = Base64.encodeToString(coverBytes, Base64.NO_WRAP)
                                val dataUri = "data:image/jpeg;base64,$base64Cover"
                                
                                // Set appropriate attribute based on element type
                                if (img.tagName() == "img") {
                                    img.attr("src", dataUri)
                                } else {
                                    // For SVG <image> elements, update both href and xlink:href for compatibility
                                    img.attr("href", dataUri)
                                    img.attr("xlink:href", dataUri)
                                }
                                
                                AppLogger.d("EpubParser", "Successfully set cached cover as ${img.tagName()} ${if (img.tagName() == "img") "src" else "href"} for image: $originalSrc")
                                return@forEach // Successfully used cached cover
                            } catch (e: Exception) {
                                AppLogger.e("EpubParser", "Error using cached cover, falling back to normal processing. Error: ${e.message}", e)
                                // Fall through to normal processing
                            }
                        }
                        
                        // For all other images (non-cover), cache to disk and use file:// URL
                        val imageEntry = zip.getEntry(imagePath)
                        if (imageEntry != null) {
                            AppLogger.d("EpubParser", "Found image in ZIP: $imagePath (size: ${imageEntry.size})")
                            val imageBytes = zip.getInputStream(imageEntry).readBytes()
                            
                            // Create a sanitized filename from the original path
                            val sanitizedFileName = imagePath.replace('/', '_').replace('\\', '_')
                            val chapterCacheDir = getChapterImageCacheDir(file, page)
                            val cachedImageFile = File(chapterCacheDir, sanitizedFileName)
                            
                            // Write image to cache file
                            try {
                                FileOutputStream(cachedImageFile).use { output ->
                                    output.write(imageBytes)
                                }
                                
                                // Update appropriate attribute based on element type
                                val fileUrl = "file://${cachedImageFile.absolutePath}"
                                if (img.tagName() == "img") {
                                    img.attr("src", fileUrl)
                                } else {
                                    // For SVG <image> elements, update both href and xlink:href for compatibility
                                    img.attr("href", fileUrl)
                                    img.attr("xlink:href", fileUrl)
                                }
                                
                                AppLogger.d("EpubParser", "Cached image to: ${cachedImageFile.absolutePath}")
                            } catch (e: Exception) {
                                AppLogger.e("EpubParser", "Failed to cache image to disk: ${e.message}", e)
                                // Fall back to base64 if file caching fails
                                val mimeType = when (imagePath.substringAfterLast('.').lowercase()) {
                                    "jpg", "jpeg" -> "image/jpeg"
                                    "png" -> "image/png"
                                    "gif" -> "image/gif"
                                    "webp" -> "image/webp"
                                    "svg" -> "image/svg+xml"
                                    else -> "image/jpeg"
                                }
                                val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                                val dataUri = "data:$mimeType;base64,$base64Image"
                                
                                // Set appropriate attribute based on element type
                                if (img.tagName() == "img") {
                                    img.attr("src", dataUri)
                                } else {
                                    // For SVG <image> elements, update both href and xlink:href for compatibility
                                    img.attr("href", dataUri)
                                    img.attr("xlink:href", dataUri)
                                }
                                
                                AppLogger.w("EpubParser", "Fell back to base64 for image: $originalSrc")
                            }
                        } else {
                            AppLogger.w("EpubParser", "Image not found in EPUB ZIP: $imagePath (originalSrc: $originalSrc)")
                        }
                    } catch (e: Exception) {
                        AppLogger.e("EpubParser", "Error processing image: $originalSrc. Error: ${e.message}", e)
                    }
                }
                
                val html = body.html()
                
                return PageContent(text = text, html = html.takeIf { it.isNotBlank() } ?: "")
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
        val opfDir = if (opfPath.contains('/')) opfPath.substringBeforeLast('/') else ""
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
            AppLogger.d("EpubParser", "[COVER_DEBUG] Starting cover image extraction for: ${bookFile.name}")
            
            // Method 1: Look for cover in metadata
            var coverId = opfDoc.select("metadata > meta[name=cover]").attr("content")
            AppLogger.d("EpubParser", "[COVER_DEBUG] Method 1 (metadata): coverId='$coverId'")
            
            // Method 2: Look for cover-image property in manifest
            if (coverId.isBlank()) {
                coverId = opfDoc.select("manifest > item[properties*=cover-image]").attr("id")
                AppLogger.d("EpubParser", "[COVER_DEBUG] Method 2 (cover-image property): coverId='$coverId'")
            }
            
            // Method 3: Look for common cover file names in manifest
            if (coverId.isBlank()) {
                val coverItem = opfDoc.select("manifest > item").firstOrNull { item ->
                    val href = item.attr("href").lowercase()
                    val id = item.attr("id").lowercase()
                    href.contains("cover") || id.contains("cover")
                }
                coverId = coverItem?.attr("id") ?: ""
                AppLogger.d("EpubParser", "[COVER_DEBUG] Method 3 (name search): coverId='$coverId', coverItem href='${coverItem?.attr("href")}', id='${coverItem?.attr("id")}'")
            }
            
            // Get the image path from manifest
            var imagePath: String? = null
            if (coverId.isNotBlank()) {
                imagePath = opfDoc.select("manifest > item[id=$coverId]").attr("href")
                AppLogger.d("EpubParser", "[COVER_DEBUG] Image path from manifest (coverId='$coverId'): '$imagePath'")
            }
            
            // If we still don't have a cover, try common file names directly
            if (imagePath.isNullOrBlank()) {
                val commonCoverNames = listOf("cover.jpg", "cover.jpeg", "cover.png", "Cover.jpg", "Cover.jpeg", "Cover.png")
                val opfDir = if (opfPath.contains('/')) opfPath.substringBeforeLast('/') else ""
                AppLogger.d("EpubParser", "[COVER_DEBUG] Trying common cover names in opfDir='$opfDir'")
                imagePath = commonCoverNames.firstOrNull { name ->
                    val fullPath = if (opfDir.isNotBlank()) "$opfDir/$name" else name
                    val exists = zip.getEntry(fullPath) != null
                    AppLogger.d("EpubParser", "[COVER_DEBUG] Checking '$fullPath': exists=$exists")
                    exists
                }
                AppLogger.d("EpubParser", "[COVER_DEBUG] Common name search result: '$imagePath'")
            }
            
            if (imagePath.isNullOrBlank()) {
                AppLogger.w("EpubParser", "[COVER_DEBUG] No cover image found for: ${bookFile.name}")
                return null
            }
            
            // Construct full path relative to OPF
            val opfDir = if (opfPath.contains('/')) opfPath.substringBeforeLast('/') else ""
            val fullImagePath = if (opfDir.isNotBlank() && !imagePath.startsWith("/")) {
                "$opfDir/$imagePath"
            } else {
                imagePath.removePrefix("/")
            }
            
            AppLogger.d("EpubParser", "[COVER_DEBUG] Full image path in ZIP: '$fullImagePath'")
            
            // Extract and save the image
            val imageEntry = zip.getEntry(fullImagePath)
            if (imageEntry == null) {
                AppLogger.w("EpubParser", "[COVER_DEBUG] Image entry not found in ZIP: '$fullImagePath'")
                return null
            }
            
            AppLogger.d("EpubParser", "[COVER_DEBUG] Found image entry in ZIP (size: ${imageEntry.size})")
            val imageBytes = zip.getInputStream(imageEntry).readBytes()
            AppLogger.d("EpubParser", "[COVER_DEBUG] Read ${imageBytes.size} bytes from ZIP")
            
            // Decode bitmap to verify it's a valid image
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            if (bitmap == null) {
                AppLogger.w("EpubParser", "[COVER_DEBUG] Failed to decode image as bitmap (invalid image data)")
                return null
            }
            AppLogger.d("EpubParser", "[COVER_DEBUG] Successfully decoded bitmap: ${bitmap.width}x${bitmap.height}")
            
            // Save to cache directory
            val cacheDir = File(bookFile.parentFile, ".covers")
            cacheDir.mkdirs()
            val coverFile = File(cacheDir, "${bookFile.nameWithoutExtension}_cover.jpg")
            
            AppLogger.d("EpubParser", "[COVER_DEBUG] Saving cover to: ${coverFile.absolutePath}")
            
            FileOutputStream(coverFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            
            AppLogger.d("EpubParser", "[COVER_DEBUG] Cover saved successfully: ${coverFile.absolutePath} (size: ${coverFile.length()} bytes)")
            
            return coverFile.absolutePath
        } catch (e: Exception) {
            AppLogger.e("EpubParser", "[COVER_DEBUG] Exception during cover extraction: ${e.message}", e)
            e.printStackTrace()
            return null
        }
    }
    
    /**
     * Build a map from spine item href to page index for TOC linking
     */
    private fun buildHrefToIndexMap(zip: ZipFile): Map<String, Int> {
        val spineItems = getSpineItems(zip)
        val hrefToIndex = mutableMapOf<String, Int>()
        spineItems.forEachIndexed { index, path ->
            val href = path.substringAfterLast('/')
            hrefToIndex[href] = index
            // Also store without anchor for flexibility
            hrefToIndex[href.substringBefore('#')] = index
        }
        return hrefToIndex
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
            val opfDir = if (opfPath.contains('/')) opfPath.substringBeforeLast('/') else ""
            val ncxPath = if (opfDir.isNotBlank()) "$opfDir/$ncxHref" else ncxHref
            
            val ncxEntry = zip.getEntry(ncxPath) ?: return null
            val ncxContent = zip.getInputStream(ncxEntry).bufferedReader().readText()
            val ncxDoc = Jsoup.parse(ncxContent, "", org.jsoup.parser.Parser.xmlParser())
            
            // Build href to index map for spine items
            val hrefToIndex = buildHrefToIndexMap(zip)
            
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
            val opfDir = if (opfPath.contains('/')) opfPath.substringBeforeLast('/') else ""
            val navPath = if (opfDir.isNotBlank()) "$opfDir/$navHref" else navHref
            
            val navEntry = zip.getEntry(navPath) ?: return null
            val navContent = zip.getInputStream(navEntry).bufferedReader().readText()
            val navDoc = Jsoup.parse(navContent)
            
            // Build href to index map for spine items
            val hrefToIndex = buildHrefToIndexMap(zip)
            
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
    
    /**
     * Resolve a relative path from a content file to an image file
     * @param contentDir Directory containing the content file (e.g., "OEBPS/Text")
     * @param imageSrc Image source path from HTML (e.g., "../Images/photo.jpg")
     * @return Resolved path within the EPUB (e.g., "OEBPS/Images/photo.jpg")
     */
    private fun resolveRelativePath(contentDir: String, imageSrc: String): String {
        // Handle absolute paths
        if (imageSrc.startsWith("/")) {
            return imageSrc.removePrefix("/")
        }
        
        // Handle data URIs (already base64)
        if (imageSrc.startsWith("data:")) {
            return imageSrc
        }
        
        // Split paths into parts
        val contentParts = contentDir.split("/").filter { it.isNotEmpty() }
        val imageParts = imageSrc.split("/").filter { it.isNotEmpty() }
        
        // Start with content directory
        val resolvedParts = contentParts.toMutableList()
        
        // Process each part of the image path
        for (part in imageParts) {
            when (part) {
                "." -> continue // Current directory, ignore
                ".." -> {
                    // Parent directory
                    if (resolvedParts.isNotEmpty()) {
                        resolvedParts.removeAt(resolvedParts.lastIndex)
                    }
                }
                else -> resolvedParts.add(part)
            }
        }
        
        return resolvedParts.joinToString("/")
    }
    
    /**
     * Get the image cache directory for a specific chapter
     * @param bookFile The book file
     * @param chapterIndex The chapter/page index
     * @return The cache directory for this chapter, created if needed
     */
    private fun getChapterImageCacheDir(bookFile: File, chapterIndex: Int): File {
        val cacheRoot = File(bookFile.parentFile, ".image_cache")
        val bookCache = File(cacheRoot, bookFile.nameWithoutExtension)
        val chapterCache = File(bookCache, "chapter_$chapterIndex")
        chapterCache.mkdirs()
        return chapterCache
    }
    
    /**
     * Clean up old chapter caches to prevent disk bloat
     * Keeps caches for current chapter +/- keepRange
     * @param bookFile The book file
     * @param currentChapter The current chapter index
     * @param keepRange How many chapters before/after to keep (default: 2)
     */
    private fun cleanOldChapterCaches(bookFile: File, currentChapter: Int, keepRange: Int = 2) {
        val cacheRoot = File(bookFile.parentFile, ".image_cache")
        val bookCache = File(cacheRoot, bookFile.nameWithoutExtension)
        
        if (!bookCache.exists() || !bookCache.isDirectory) {
            return
        }
        
        val minKeep = (currentChapter - keepRange).coerceAtLeast(0)
        val maxKeep = currentChapter + keepRange
        
        bookCache.listFiles()?.forEach { chapterDir ->
            if (chapterDir.isDirectory && chapterDir.name.startsWith("chapter_")) {
                try {
                    val chapterNum = chapterDir.name.removePrefix("chapter_").toIntOrNull()
                    if (chapterNum != null && (chapterNum < minKeep || chapterNum > maxKeep)) {
                        AppLogger.d("EpubParser", "Cleaning up old chapter cache: ${chapterDir.name}")
                        chapterDir.deleteRecursively()
                    }
                } catch (e: Exception) {
                    AppLogger.w("EpubParser", "Failed to clean up chapter cache: ${chapterDir.name}", e)
                }
            }
        }
    }
}
