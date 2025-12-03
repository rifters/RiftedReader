package com.rifters.riftedreader.domain.parser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.rifters.riftedreader.data.database.entities.BookMeta
import com.rifters.riftedreader.util.AppLogger
import com.rifters.riftedreader.util.DiagnosticLogger
import com.rifters.riftedreader.util.EpubImageAssetHelper
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Extension function to iterate over ZipFile entries as a Sequence.
 * Provides a more Kotlin-idiomatic way to iterate ZIP entries.
 */
private fun ZipFile.entriesSequence(): Sequence<ZipEntry> = sequence {
    val entries = entries()
    while (entries.hasMoreElements()) {
        yield(entries.nextElement())
    }
}

/**
 * Data class for image processing diagnostics.
 * 
 * Captures detailed information about each image encountered during EPUB parsing.
 * This provides deterministic, parser-level diagnostics for debugging layout issues.
 * 
 * Pattern inspired by LibreraReader's approach of capturing image metadata during
 * content extraction (see foobnix/LibreraReader Fb2Extractor.java and EpubExtractor.java).
 */
data class ImageDiagnostics(
    /** Original src/href attribute value from the HTML */
    val originalSrc: String,
    /** Resolved path within the EPUB ZIP archive */
    val resolvedPath: String,
    /** Final URL/URI set on the element (asset URL, data URI, or error placeholder) */
    val finalSrc: String,
    /** Original width attribute if present, null otherwise */
    val originalWidth: String?,
    /** Original height attribute if present, null otherwise */
    val originalHeight: String?,
    /** Actual image dimensions if successfully decoded, null otherwise */
    val actualWidth: Int?,
    /** Actual image dimensions if successfully decoded, null otherwise */
    val actualHeight: Int?,
    /** Whether explicit width/height were injected for layout stabilization */
    val dimensionsInjected: Boolean,
    /** Processing result status */
    val status: ImageStatus,
    /** Error message if status is ERROR */
    val errorMessage: String? = null,
    /** Image size in bytes (if successfully read from archive) */
    val imageBytes: Int? = null,
    /** Cache file path (if cached to disk) */
    val cacheFile: String? = null,
    /** Whether this is a cover image */
    val isCoverImage: Boolean = false,
    /** Whether secondary lookup was used to find the image */
    val usedSecondaryLookup: Boolean = false
) {
    enum class ImageStatus {
        /** Successfully processed and cached/embedded */
        SUCCESS,
        /** Image found but could not be decoded (corrupt or unsupported format) */
        DECODE_FAILED,
        /** Image not found in EPUB archive */
        NOT_FOUND,
        /** Error during processing */
        ERROR
    }
    
    override fun toString(): String {
        return buildString {
            append("ImageDiagnostics(src='$originalSrc', ")
            append("resolved='$resolvedPath', ")
            append("status=$status")
            if (originalWidth != null || originalHeight != null) {
                append(", originalDim=${originalWidth}x${originalHeight}")
            }
            if (actualWidth != null && actualHeight != null) {
                append(", actualDim=${actualWidth}x${actualHeight}")
            }
            if (dimensionsInjected) {
                append(", DIMS_INJECTED")
            }
            if (isCoverImage) {
                append(", COVER")
            }
            if (usedSecondaryLookup) {
                append(", SECONDARY_LOOKUP")
            }
            if (imageBytes != null) {
                append(", bytes=$imageBytes")
            }
            if (errorMessage != null) {
                append(", error='$errorMessage'")
            }
            append(")")
        }
    }
}

/**
 * Parser for EPUB format
 */
class EpubParser : BookParser {
    
    companion object {
        private const val TAG = "EpubParser"
        private val SUPPORTED_EXTENSIONS = listOf("epub")
        private const val CONTAINER_PATH = "META-INF/container.xml"
        
        /** Maximum length for displaying source URLs in logs/diagnostics */
        private const val MAX_SRC_DISPLAY_LENGTH = 50
        
        /** Placeholder dimensions for missing images to maintain layout stability */
        private const val PLACEHOLDER_WIDTH = 100
        private const val PLACEHOLDER_HEIGHT = 100
        
        /** CSS for placeholder images */
        private const val PLACEHOLDER_CSS = "background-color: #e0e0e0; border: 1px dashed #999;"
        
        /** Alt text prefix for missing images */
        private const val MISSING_IMAGE_ALT_PREFIX = "[Image not found: "
        
        /**
         * Build responsive CSS styling for an image with given dimensions.
         * Uses Kotlin string interpolation for clarity.
         */
        private fun buildResponsiveCss(width: Int, height: Int): String =
            "aspect-ratio:$width/$height; max-width:100%; height:auto;"
        
        /**
         * Helper function to truncate text for display in logs/diagnostics.
         * Appends "..." if the text exceeds MAX_SRC_DISPLAY_LENGTH.
         */
        private fun truncateForDisplay(text: String): String {
            return if (text.length > MAX_SRC_DISPLAY_LENGTH) {
                text.take(MAX_SRC_DISPLAY_LENGTH) + "..."
            } else {
                text
            }
        }
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
                
                // Process images with deterministic layout stabilization
                // This approach is inspired by LibreraReader's pattern of processing images
                // during content extraction to ensure stable layout before WebView rendering
                val contentDir = contentPath.substringBeforeLast('/', "")
                val images = body.select("img[src], image[href], image")
                
                // Collect diagnostics for all images in this chapter
                val imageDiagnostics = mutableListOf<ImageDiagnostics>()
                
                if (images.isNotEmpty()) {
                    DiagnosticLogger.d(
                        DiagnosticLogger.Category.PARSER, 
                        "Processing ${images.size} images on page $page, contentDir='$contentDir'"
                    )
                    
                    // Clean up old chapter caches to prevent disk bloat
                    // Use keepRange=30 to accommodate 5-window sliding buffer:
                    // - 5 windows (2 before + active + 2 after) × 5 chapters/window = 25 chapters
                    // - From any chapter N, need roughly [N-12, N+12] range for steady state
                    // - keepRange=30 provides safety margin for pre-cache and TOC jumps
                    cleanOldChapterCaches(file, page, keepRange = 30)
                }
                
                images.forEach { img ->
                    val diagnostic = processImageWithLayoutStabilization(
                        img = img,
                        zip = zip,
                        contentDir = contentDir,
                        bookFile = file,
                        page = page
                    )
                    imageDiagnostics.add(diagnostic)
                }
                
                // Log comprehensive image diagnostics
                logImageDiagnostics(page, imageDiagnostics)
                
                val html = body.html()
                
                // Log the generated HTML for debugging pagination
                val imageStats = buildImageStatsMetadata(imageDiagnostics)
                com.rifters.riftedreader.util.HtmlDebugLogger.logChapterHtml(
                    bookId = file.absolutePath,
                    chapterIndex = page,
                    html = html,
                    metadata = mapOf(
                        "format" to "EPUB",
                        "contentPath" to contentPath,
                        "textLength" to text.length.toString(),
                        "htmlLength" to html.length.toString(),
                        "imageCount" to imageDiagnostics.size.toString(),
                        "imagesWithDimensions" to imageDiagnostics.count { it.dimensionsInjected }.toString()
                    ) + imageStats
                )
                
                return PageContent(text = text, html = html.takeIf { it.isNotBlank() } ?: "")
            }
        }
        return PageContent.EMPTY
    }
    
    /**
     * Process a single image element with deterministic layout stabilization.
     * 
     * This method implements a parser-level, deterministic image handling step that:
     * 1. Extracts original image attributes (src, width, height, alt)
     * 2. Resolves the image path within the EPUB archive
     * 3. Attempts secondary lookup if primary path fails (normalized basename)
     * 4. Decodes the image to determine actual dimensions
     * 5. Injects explicit width/height attributes AND responsive CSS for layout stabilization
     * 6. Adds data-epub-* diagnostic attributes for debugging
     * 7. Caches the image and updates the src attribute
     * 8. Returns comprehensive diagnostics
     * 
     * Pattern inspired by LibreraReader's approach in Fb2Extractor.java and EpubExtractor.java
     * where images are processed during content extraction to ensure stable rendering.
     * 
     * @param img The JSoup Element representing the image
     * @param zip The ZipFile containing the EPUB
     * @param contentDir The directory containing the current content file
     * @param bookFile The book file for cache path generation
     * @param page The current page/chapter index
     * @return ImageDiagnostics with complete processing information
     */
    private fun processImageWithLayoutStabilization(
        img: Element,
        zip: ZipFile,
        contentDir: String,
        bookFile: File,
        page: Int
    ): ImageDiagnostics {
        // Step 1: Extract original attributes
        val originalSrc = when {
            img.hasAttr("src") -> img.attr("src")
            img.hasAttr("href") -> img.attr("href")
            img.hasAttr("xlink:href") -> img.attr("xlink:href")
            else -> ""
        }
        
        if (originalSrc.isBlank()) {
            AppLogger.w("EpubParser", "Image element has no src/href attribute on page $page")
            val diagnostic = ImageDiagnostics(
                originalSrc = "",
                resolvedPath = "",
                finalSrc = "",
                originalWidth = null,
                originalHeight = null,
                actualWidth = null,
                actualHeight = null,
                dimensionsInjected = false,
                status = ImageDiagnostics.ImageStatus.ERROR,
                errorMessage = "No src/href attribute found"
            )
            logImageMutation(page, diagnostic)
            return diagnostic
        }
        
        // Always set data-epub-src for diagnostics
        img.attr("data-epub-src", originalSrc)
        
        // Capture original dimensions if present
        val originalWidth = img.attr("width").takeIf { it.isNotBlank() }
        val originalHeight = img.attr("height").takeIf { it.isNotBlank() }
        // Inject dimensions if EITHER is missing (not just if both are absent)
        val needsDimensionInjection = originalWidth == null || originalHeight == null
        
        DiagnosticLogger.d(
            DiagnosticLogger.Category.PARSER,
            "Processing image: src='$originalSrc', tag=${img.tagName()}, " +
            "originalDim=${originalWidth}x${originalHeight}"
        )
        
        try {
            // Step 2: Resolve relative path
            val imagePath = resolveRelativePath(contentDir, originalSrc)
            img.attr("data-epub-resolved", imagePath)
            
            // Skip data URIs (already embedded)
            if (originalSrc.startsWith("data:")) {
                img.attr("data-epub-final", truncateForDisplay(originalSrc))
                val diagnostic = ImageDiagnostics(
                    originalSrc = truncateForDisplay(originalSrc),
                    resolvedPath = "data-uri",
                    finalSrc = truncateForDisplay(originalSrc),
                    originalWidth = originalWidth,
                    originalHeight = originalHeight,
                    actualWidth = null,
                    actualHeight = null,
                    dimensionsInjected = false,
                    status = ImageDiagnostics.ImageStatus.SUCCESS,
                    errorMessage = null
                )
                logImageMutation(page, diagnostic)
                return diagnostic
            }
            
            // Step 3: Check if this is a cover image (special handling)
            val isCoverImage = imagePath.lowercase().contains("cover") || 
                             originalSrc.lowercase().contains("cover")
            val coverPath = cachedCoverPath
            
            if (isCoverImage) {
                img.attr("data-epub-cover", "true")
            }
            
            if (isCoverImage && coverPath != null && File(coverPath).exists()) {
                return processAsCoverImage(img, coverPath, originalSrc, imagePath, originalWidth, originalHeight, page)
            }
            
            // Step 4: Load image from EPUB and process
            // Primary lookup
            var imageEntry = zip.getEntry(imagePath)
            var usedSecondaryLookup = false
            var finalResolvedPath = imagePath
            
            // Secondary lookup: try to find image by normalized basename
            if (imageEntry == null) {
                AppLogger.d("EpubParser", "Primary lookup failed for: $imagePath, trying secondary lookup...")
                val secondaryResult = retryFindImageEntry(zip, originalSrc)
                if (secondaryResult != null) {
                    imageEntry = secondaryResult.first
                    finalResolvedPath = secondaryResult.second
                    usedSecondaryLookup = true
                    img.attr("data-epub-resolved", finalResolvedPath)
                    AppLogger.d("EpubParser", "Secondary lookup succeeded: $finalResolvedPath")
                }
            }
            
            if (imageEntry == null) {
                AppLogger.w("EpubParser", "Image not found in EPUB ZIP: $imagePath (originalSrc: $originalSrc) - both primary and secondary lookup failed")
                // Set a placeholder style for missing images to maintain layout
                applyMissingImagePlaceholder(img, originalSrc)
                img.attr("data-epub-missing", "true")
                val diagnostic = ImageDiagnostics(
                    originalSrc = originalSrc,
                    resolvedPath = imagePath,
                    finalSrc = img.attr("src"),
                    originalWidth = originalWidth,
                    originalHeight = originalHeight,
                    actualWidth = null,
                    actualHeight = null,
                    dimensionsInjected = false,
                    status = ImageDiagnostics.ImageStatus.NOT_FOUND,
                    errorMessage = "Image not found in EPUB archive after secondary lookup"
                )
                logImageMutation(page, diagnostic)
                return diagnostic
            }
            
            val imageBytes = zip.getInputStream(imageEntry).readBytes()
            val imageBytesSize = imageBytes.size
            img.attr("data-epub-bytes", imageBytesSize.toString())
            
            // Step 5: Decode image to get actual dimensions for layout stabilization
            val (actualWidth, actualHeight) = decodeImageDimensions(imageBytes)
            
            if (actualWidth == null || actualHeight == null) {
                AppLogger.w("EpubParser", "Failed to decode image dimensions: $finalResolvedPath")
                // Keep original src for decode failures, add error attribute
                img.attr("data-epub-error", "decode_failed")
                img.attr("data-epub-final", originalSrc)
                val diagnostic = ImageDiagnostics(
                    originalSrc = originalSrc,
                    resolvedPath = finalResolvedPath,
                    finalSrc = originalSrc,
                    originalWidth = originalWidth,
                    originalHeight = originalHeight,
                    actualWidth = null,
                    actualHeight = null,
                    dimensionsInjected = false,
                    status = ImageDiagnostics.ImageStatus.DECODE_FAILED,
                    errorMessage = "Could not decode image dimensions",
                    imageBytes = imageBytesSize,
                    usedSecondaryLookup = usedSecondaryLookup
                )
                logImageMutation(page, diagnostic)
                return diagnostic
            }
            
            // Set intrinsic dimensions attribute
            img.attr("data-epub-intrinsic", "${actualWidth}x${actualHeight}")
            
            // Step 6: Inject dimensions for layout stabilization
            // Inject if EITHER dimension is missing to prevent distortions from partial data
            // Also inject responsive CSS (aspect-ratio, max-width, height:auto)
            val dimensionsInjected = if (needsDimensionInjection) {
                injectImageDimensions(img, actualWidth, actualHeight)
                true
            } else {
                // Still add responsive CSS even if both dimensions were present
                injectResponsiveCss(img, actualWidth, actualHeight)
                false
            }
            
            // Step 7: Cache image and update src
            val cacheResult = cacheImageAndGetUrl(
                imageBytes = imageBytes,
                imagePath = finalResolvedPath,
                bookFile = bookFile,
                page = page,
                img = img,
                originalSrc = originalSrc,
                isCoverImage = isCoverImage,
                actualWidth = actualWidth,
                actualHeight = actualHeight
            )
            val finalSrc = cacheResult.assetUrl
            val cacheFilePath = cacheResult.cacheFile
            
            img.attr("data-epub-final", finalSrc)
            if (cacheFilePath != null) {
                img.attr("data-epub-cache", cacheFilePath)
            }
            
            DiagnosticLogger.d(
                DiagnosticLogger.Category.PARSER,
                "Image processed: $originalSrc -> actualDim=${actualWidth}x${actualHeight}, " +
                "dimensionsInjected=$dimensionsInjected"
            )
            
            val diagnostic = ImageDiagnostics(
                originalSrc = originalSrc,
                resolvedPath = finalResolvedPath,
                finalSrc = finalSrc,
                originalWidth = originalWidth,
                originalHeight = originalHeight,
                actualWidth = actualWidth,
                actualHeight = actualHeight,
                dimensionsInjected = dimensionsInjected,
                status = ImageDiagnostics.ImageStatus.SUCCESS,
                errorMessage = null,
                imageBytes = imageBytesSize,
                cacheFile = cacheFilePath,
                isCoverImage = isCoverImage,
                usedSecondaryLookup = usedSecondaryLookup
            )
            logImageMutation(page, diagnostic)
            return diagnostic
            
        } catch (e: Exception) {
            AppLogger.e("EpubParser", "Error processing image: $originalSrc", e)
            img.attr("data-epub-error", e.message ?: "unknown_error")
            val diagnostic = ImageDiagnostics(
                originalSrc = originalSrc,
                resolvedPath = "",
                finalSrc = originalSrc,
                originalWidth = originalWidth,
                originalHeight = originalHeight,
                actualWidth = null,
                actualHeight = null,
                dimensionsInjected = false,
                status = ImageDiagnostics.ImageStatus.ERROR,
                errorMessage = e.message
            )
            logImageMutation(page, diagnostic)
            return diagnostic
        }
    }
    
    /**
     * Process an image as a cover image using the cached cover file.
     */
    private fun processAsCoverImage(
        img: Element,
        coverPath: String,
        originalSrc: String,
        resolvedPath: String,
        originalWidth: String?,
        originalHeight: String?,
        page: Int
    ): ImageDiagnostics {
        try {
            AppLogger.d("EpubParser", "Using cached cover image (base64) from: $coverPath")
            val coverFile = File(coverPath)
            val coverBytes = coverFile.readBytes()
            val coverBytesSize = coverBytes.size
            
            // Set diagnostic attributes
            img.attr("data-epub-src", originalSrc)
            img.attr("data-epub-resolved", resolvedPath)
            img.attr("data-epub-cover", "true")
            img.attr("data-epub-bytes", coverBytesSize.toString())
            img.attr("data-epub-cache", coverPath)
            
            // Decode dimensions from cover file
            val (actualWidth, actualHeight) = decodeImageDimensions(coverBytes)
            
            val base64Cover = Base64.encodeToString(coverBytes, Base64.NO_WRAP)
            val dataUri = "data:image/jpeg;base64,$base64Cover"
            
            // Set intrinsic dimensions attribute if decoded successfully
            if (actualWidth != null && actualHeight != null) {
                img.attr("data-epub-intrinsic", "${actualWidth}x${actualHeight}")
            }
            
            // Inject dimensions if EITHER is missing (not just if both are absent)
            val needsDimensionInjection = originalWidth == null || originalHeight == null
            val dimensionsInjected = if (needsDimensionInjection && 
                                        actualWidth != null && actualHeight != null) {
                injectImageDimensions(img, actualWidth, actualHeight)
                true
            } else if (actualWidth != null && actualHeight != null) {
                // Still add responsive CSS even if both dimensions were present
                injectResponsiveCss(img, actualWidth, actualHeight)
                false
            } else {
                false
            }
            
            // Set appropriate attribute based on element type
            if (img.tagName() == "img") {
                img.attr("src", dataUri)
            } else {
                img.attr("href", dataUri)
                img.attr("xlink:href", dataUri)
            }
            
            val finalSrcDisplay = "data:image/jpeg;base64,[cached-cover]"
            img.attr("data-epub-final", finalSrcDisplay)
            
            val diagnostic = ImageDiagnostics(
                originalSrc = originalSrc,
                resolvedPath = resolvedPath,
                finalSrc = finalSrcDisplay,
                originalWidth = originalWidth,
                originalHeight = originalHeight,
                actualWidth = actualWidth,
                actualHeight = actualHeight,
                dimensionsInjected = dimensionsInjected,
                status = ImageDiagnostics.ImageStatus.SUCCESS,
                errorMessage = null,
                imageBytes = coverBytesSize,
                cacheFile = coverPath,
                isCoverImage = true
            )
            logImageMutation(page, diagnostic)
            return diagnostic
        } catch (e: Exception) {
            AppLogger.e("EpubParser", "Error using cached cover, returning error diagnostic", e)
            img.attr("data-epub-error", e.message ?: "cover_load_failed")
            val diagnostic = ImageDiagnostics(
                originalSrc = originalSrc,
                resolvedPath = resolvedPath,
                finalSrc = originalSrc,
                originalWidth = originalWidth,
                originalHeight = originalHeight,
                actualWidth = null,
                actualHeight = null,
                dimensionsInjected = false,
                status = ImageDiagnostics.ImageStatus.ERROR,
                errorMessage = "Failed to load cached cover: ${e.message}",
                isCoverImage = true
            )
            logImageMutation(page, diagnostic)
            return diagnostic
        }
    }
    
    /**
     * Decode image dimensions without loading the full bitmap into memory.
     * Uses BitmapFactory.Options.inJustDecodeBounds for efficiency.
     * 
     * @param imageBytes The raw image bytes
     * @return Pair of (width, height) or (null, null) if decoding fails
     */
    private fun decodeImageDimensions(imageBytes: ByteArray): Pair<Int?, Int?> {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
            
            if (options.outWidth > 0 && options.outHeight > 0) {
                Pair(options.outWidth, options.outHeight)
            } else {
                Pair(null, null)
            }
        } catch (e: Exception) {
            AppLogger.w("EpubParser", "Failed to decode image dimensions: ${e.message}")
            Pair(null, null)
        }
    }
    
    /**
     * Secondary lookup for images by normalized basename.
     * When the primary path lookup fails, this function searches all ZIP entries
     * for a file matching the basename (filename without path) of the original src.
     * 
     * This handles cases where images exist in different folders than expected
     * due to manifest/spine parity issues or non-standard EPUB structure.
     * 
     * Pattern inspired by how KOReader and LibreraReader handle path resolution
     * for non-standard EPUB archives.
     * 
     * @param zip The ZipFile to search
     * @param originalSrc The original src attribute from the image element
     * @return Pair of (ZipEntry, resolvedPath) if found, null otherwise
     */
    private fun retryFindImageEntry(zip: ZipFile, originalSrc: String): Pair<ZipEntry, String>? {
        // Extract and normalize the basename (filename without path)
        val basename = originalSrc
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .lowercase()
        
        if (basename.isBlank()) {
            AppLogger.d("EpubParser", "[SECONDARY_LOOKUP] Empty basename for: $originalSrc")
            return null
        }
        
        AppLogger.d("EpubParser", "[SECONDARY_LOOKUP] Searching for basename: $basename")
        
        // Search all ZIP entries for matching basename
        for (entry in zip.entriesSequence()) {
            if (entry.isDirectory) continue
            
            val entryBasename = entry.name
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .lowercase()
            
            if (entryBasename == basename) {
                AppLogger.d("EpubParser", "[SECONDARY_LOOKUP] Found match: ${entry.name} for basename: $basename")
                return Pair(entry, entry.name)
            }
        }
        
        AppLogger.d("EpubParser", "[SECONDARY_LOOKUP] No match found for basename: $basename")
        return null
    }
    
    /**
     * Log an [IMAGE_MUTATION] entry for diagnostics.
     * Emits a structured log line with key image processing details.
     * 
     * @param page The current page/chapter index
     * @param diagnostic The ImageDiagnostics for this image
     */
    private fun logImageMutation(page: Int, diagnostic: ImageDiagnostics) {
        val intrinsicSize = if (diagnostic.actualWidth != null && diagnostic.actualHeight != null) {
            "${diagnostic.actualWidth}x${diagnostic.actualHeight}"
        } else {
            "unknown"
        }
        
        AppLogger.d(
            TAG,
            "[IMAGE_MUTATION] page=$page, status=${diagnostic.status}, " +
            "src=${truncateForDisplay(diagnostic.originalSrc)}, " +
            "resolved=${truncateForDisplay(diagnostic.resolvedPath)}, " +
            "final=${truncateForDisplay(diagnostic.finalSrc)}, " +
            "injected=${diagnostic.dimensionsInjected}, " +
            "intrinsic=$intrinsicSize" +
            (if (diagnostic.isCoverImage) ", cover=true" else "") +
            (if (diagnostic.usedSecondaryLookup) ", secondaryLookup=true" else "") +
            (diagnostic.errorMessage?.let { ", error=$it" } ?: "")
        )
    }
    
    /**
     * Inject width and height attributes into an image element for layout stabilization.
     * Also injects responsive CSS (aspect-ratio, max-width, height:auto).
     * 
     * This is the key step for deterministic layout - by setting explicit dimensions
     * before the WebView loads the HTML, we prevent layout shifts when images load.
     * 
     * Following LibreraReader's pattern from BookCSS.java where image scaling is handled
     * at the parser level rather than leaving it to runtime CSS.
     * 
     * @param img The image element to modify
     * @param width The actual image width
     * @param height The actual image height
     */
    private fun injectImageDimensions(img: Element, width: Int, height: Int) {
        // Set both width and height attributes
        img.attr("width", width.toString())
        img.attr("height", height.toString())
        
        // Also add a data attribute for diagnostics
        img.attr("data-original-dimensions", "${width}x${height}")
        img.attr("data-dimensions-injected", "true")
        
        // Inject responsive CSS with aspect-ratio to maintain proportions
        injectResponsiveCss(img, width, height)
        
        DiagnosticLogger.d(
            DiagnosticLogger.Category.PARSER,
            "Injected dimensions: ${width}x${height} for ${truncateForDisplay(img.attr("src") ?: "")}"
        )
    }
    
    /**
     * Inject responsive CSS styling for an image element.
     * Adds aspect-ratio, max-width:100%, height:auto while preserving existing styles.
     * 
     * @param img The image element to modify
     * @param width The actual image width
     * @param height The actual image height
     */
    private fun injectResponsiveCss(img: Element, width: Int, height: Int) {
        val responsiveStyle = buildResponsiveCss(width, height)
        val existingStyle = img.attr("style")
        val combinedStyle = if (existingStyle.isBlank()) {
            responsiveStyle
        } else {
            // Ensure proper CSS concatenation by adding semicolon separator
            val trimmedStyle = existingStyle.trimEnd()
            if (trimmedStyle.endsWith(";")) {
                "$trimmedStyle $responsiveStyle"
            } else {
                "$trimmedStyle; $responsiveStyle"
            }
        }
        img.attr("style", combinedStyle)
    }
    
    /**
     * Apply placeholder styling for missing images to maintain layout stability.
     * Only called after secondary lookup fails - used for truly missing images.
     * 
     * @param img The image element to modify
     * @param originalSrc The original src attribute for alt text
     */
    private fun applyMissingImagePlaceholder(img: Element, originalSrc: String) {
        // Set a minimum size placeholder to prevent layout collapse
        img.attr("width", PLACEHOLDER_WIDTH.toString())
        img.attr("height", PLACEHOLDER_HEIGHT.toString())
        img.attr("alt", "$MISSING_IMAGE_ALT_PREFIX${truncateForDisplay(originalSrc)}]")
        
        // Add inline style for visual indication (gray placeholder)
        val existingStyle = img.attr("style")
        val combinedStyle = if (existingStyle.isBlank()) {
            PLACEHOLDER_CSS
        } else {
            // Ensure proper CSS concatenation by adding semicolon separator
            val trimmedStyle = existingStyle.trimEnd()
            if (trimmedStyle.endsWith(";")) {
                "$trimmedStyle $PLACEHOLDER_CSS"
            } else {
                "$trimmedStyle; $PLACEHOLDER_CSS"
            }
        }
        img.attr("style", combinedStyle)
    }
    
    /**
     * Result from cacheImageAndGetUrl containing the asset URL and optional cache file path.
     */
    private data class CacheResult(
        val assetUrl: String,
        val cacheFile: String?
    )
    
    /**
     * Cache image to disk and return the asset URL for WebView.
     * Falls back to base64 data URI if caching fails.
     * 
     * @param imageBytes The raw image data
     * @param imagePath The resolved path within the EPUB
     * @param bookFile The book file for cache path generation
     * @param page The current page/chapter index
     * @param img The image element to update
     * @param originalSrc The original src attribute for diagnostics
     * @param isCoverImage Whether this is a cover image
     * @param actualWidth Decoded image width (for registry)
     * @param actualHeight Decoded image height (for registry)
     * @return CacheResult with asset URL and cache file path
     */
    @Suppress("UNUSED_PARAMETER") // Parameters reserved for recordMapping after asset handler PR merges
    private fun cacheImageAndGetUrl(
        imageBytes: ByteArray,
        imagePath: String,
        bookFile: File,
        page: Int,
        img: Element,
        originalSrc: String,
        isCoverImage: Boolean,
        actualWidth: Int,
        actualHeight: Int
    ): CacheResult {
        val sanitizedFileName = imagePath.replace('/', '_').replace('\\', '_')
        val chapterCacheDir = getChapterImageCacheDir(bookFile, page)
        val cachedImageFile = File(chapterCacheDir, sanitizedFileName)
        
        DiagnosticLogger.d(
            DiagnosticLogger.Category.PARSER,
            "[IMAGE_CACHE] Attempting to cache: chapter=$page, file=$sanitizedFileName, " +
            "dir=${chapterCacheDir.absolutePath}, dirExists=${chapterCacheDir.exists()}"
        )
        
        return try {
            // Ensure directory exists
            if (!chapterCacheDir.exists()) {
                val dirCreated = chapterCacheDir.mkdirs()
                DiagnosticLogger.d(
                    DiagnosticLogger.Category.PARSER,
                    "[IMAGE_CACHE] Created cache directory: success=$dirCreated, path=${chapterCacheDir.absolutePath}"
                )
            }
            
            FileOutputStream(cachedImageFile).use { output ->
                output.write(imageBytes)
            }
            
            val fileExists = cachedImageFile.exists()
            val fileSize = if (fileExists) cachedImageFile.length() else 0
            
            DiagnosticLogger.d(
                DiagnosticLogger.Category.PARSER,
                "[IMAGE_CACHE] Wrote image: path=${cachedImageFile.absolutePath}, " +
                "exists=$fileExists, size=$fileSize bytes"
            )
            
            val imageCacheRoot = EpubImageAssetHelper.getImageCacheRoot(bookFile)
            val assetUrl = EpubImageAssetHelper.toAssetUrl(cachedImageFile.absolutePath, imageCacheRoot)
            
            DiagnosticLogger.d(
                DiagnosticLogger.Category.PARSER,
                "[IMAGE_CACHE] Generated asset URL: $assetUrl"
            )
            
            if (img.tagName() == "img") {
                img.attr("src", assetUrl)
            } else {
                img.attr("href", assetUrl)
                img.attr("xlink:href", assetUrl)
            }
            
            // TODO(#208): Enable EpubImageAssetHelper.recordMapping after asset handler PR merges
            
            CacheResult(assetUrl, cachedImageFile.absolutePath)
        } catch (e: Exception) {
            AppLogger.w("EpubParser", "Failed to cache image, falling back to base64: ${e.message}")
            
            // Fall back to base64
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
            
            if (img.tagName() == "img") {
                img.attr("src", dataUri)
            } else {
                img.attr("href", dataUri)
                img.attr("xlink:href", dataUri)
            }
            
            // TODO(#208): Enable EpubImageAssetHelper.recordMapping for base64 fallback after asset handler PR merges
            
            CacheResult(truncateForDisplay(dataUri), null)
        }
    }
    
    /**
     * Log comprehensive image diagnostics for the chapter.
     */
    private fun logImageDiagnostics(page: Int, diagnostics: List<ImageDiagnostics>) {
        if (diagnostics.isEmpty()) return
        
        val successCount = diagnostics.count { it.status == ImageDiagnostics.ImageStatus.SUCCESS }
        val failedCount = diagnostics.size - successCount
        val dimensionsInjectedCount = diagnostics.count { it.dimensionsInjected }
        
        DiagnosticLogger.i(
            DiagnosticLogger.Category.PARSER,
            "Image processing complete for page $page: " +
            "total=${diagnostics.size}, success=$successCount, failed=$failedCount, " +
            "dimensionsInjected=$dimensionsInjectedCount"
        )
        
        // Log individual failures for debugging
        diagnostics.filter { it.status != ImageDiagnostics.ImageStatus.SUCCESS }.forEach { diag ->
            DiagnosticLogger.w(
                DiagnosticLogger.Category.PARSER,
                "Image issue: ${truncateForDisplay(diag.originalSrc)} - status=${diag.status}, " +
                "error=${diag.errorMessage ?: "none"}"
            )
        }
    }
    
    /**
     * Build metadata map with image statistics for HTML debug logging.
     */
    private fun buildImageStatsMetadata(diagnostics: List<ImageDiagnostics>): Map<String, String> {
        if (diagnostics.isEmpty()) return emptyMap()
        
        return mapOf(
            "imageSuccessCount" to diagnostics.count { it.status == ImageDiagnostics.ImageStatus.SUCCESS }.toString(),
            "imageNotFoundCount" to diagnostics.count { it.status == ImageDiagnostics.ImageStatus.NOT_FOUND }.toString(),
            "imageDecodeFailCount" to diagnostics.count { it.status == ImageDiagnostics.ImageStatus.DECODE_FAILED }.toString(),
            "imageErrorCount" to diagnostics.count { it.status == ImageDiagnostics.ImageStatus.ERROR }.toString(),
            "imageDetails" to diagnostics.joinToString("; ") { 
                "${truncateForDisplay(it.originalSrc)}:${it.status}:${it.actualWidth}x${it.actualHeight}:injected=${it.dimensionsInjected}"
            }
        )
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
                        AppLogger.d(TAG, "Cleaning up old chapter cache: ${chapterDir.name}")
                        chapterDir.deleteRecursively()
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed to clean up chapter cache: ${chapterDir.name}", e)
                }
            }
        }
    }
}
