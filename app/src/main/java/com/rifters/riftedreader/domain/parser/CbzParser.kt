package com.rifters.riftedreader.domain.parser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.rifters.riftedreader.data.database.entities.BookMeta
import com.rifters.riftedreader.domain.reader.ImageSequenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.FileHeader
import java.io.File

class CbzParser : BookParser {

    companion object {
        private val SUPPORTED_EXTENSIONS = setOf("cbz")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
    }

    private val imageSequenceEngine = ImageSequenceEngine()
    private val imageEntryCache = mutableMapOf<String, List<String>>()

    override fun canParse(file: File): Boolean = file.extension.lowercase() in SUPPORTED_EXTENSIONS

    override suspend fun extractMetadata(file: File): BookMeta = withContext(Dispatchers.IO) {
        BookMeta(
            path = file.absolutePath,
            title = file.nameWithoutExtension.ifBlank { "CBZ" },
            format = "CBZ",
            size = file.length(),
            totalPages = getPageCount(file),
            description = "Comic archive rendered as individual image pages."
        )
    }

    override suspend fun getPageContent(file: File, page: Int): PageContent = withContext(Dispatchers.IO) {
        val imageEntryName = getImageEntryNames(file).getOrNull(page)
            ?: return@withContext placeholderPage(page, "Could not open CBZ")

        val bitmap = decodeBitmap(file, imageEntryName)
            ?: return@withContext placeholderPage(page, "Could not decode CBZ image")

        try {
            val imagePage = imageSequenceEngine.renderPage(page, bitmap)
            PageContent(
                text = "CBZ page ${page + 1}",
                html = imageSequenceEngine.toHtmlPage(imagePage),
                title = imageEntryName.substringAfterLast('/')
            )
        } finally {
            bitmap.recycle()
        }
    }

    override suspend fun getPageCount(file: File): Int = withContext(Dispatchers.IO) {
        val count = getImageEntryNames(file).size
        if (count > 0) count else 1
    }

    override suspend fun getTableOfContents(file: File): List<TocEntry> = emptyList()

    private fun getImageEntryNames(file: File): List<String> {
        val cacheKey = cacheKey(file)
        imageEntryCache[cacheKey]?.let { return it }

        return runCatching {
            ZipFile(file).use { zipFile ->
                zipFile.fileHeaders
                    .asSequence()
                    .filter { !it.isDirectory }
                    .filter { isImageFile(it.fileName) }
                    .sortedBy { it.fileName.lowercase() }
                    .map { it.fileName }
                    .toList()
            }
        }.getOrDefault(emptyList()).also { imageEntryCache[cacheKey] = it }
    }

    private fun decodeBitmap(file: File, entryName: String): Bitmap? {
        return runCatching {
            ZipFile(file).use { zipFile ->
                val header = zipFile.getFileHeader(entryName) ?: return@use null
                zipFile.getInputStream(header).use { inputStream ->
                    BitmapFactory.decodeStream(inputStream)
                }
            }
        }.getOrNull()
    }

    private fun cacheKey(file: File): String {
        return "${file.absolutePath}:${file.lastModified()}:${file.length()}"
    }

    private fun placeholderPage(page: Int, message: String): PageContent {
        return PageContent(
            text = message,
            html = """
                <div class="image-page image-page-error">
                    <h2>$message</h2>
                    <p>Page ${page + 1} could not be displayed.</p>
                </div>
            """.trimIndent(),
            title = "CBZ"
        )
    }

    private fun isImageFile(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in IMAGE_EXTENSIONS
    }
}
