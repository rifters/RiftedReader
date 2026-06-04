package com.rifters.riftedreader.domain.parser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import com.rifters.riftedreader.data.database.entities.BookMeta
import com.rifters.riftedreader.domain.reader.ImageSequenceEngine
import com.rifters.riftedreader.domain.reader.SharedImageSequenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class CbrParser : BookParser {

    companion object {
        private val SUPPORTED_EXTENSIONS = setOf("cbr")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
    }

    private val imageSequenceEngine = SharedImageSequenceEngine.instance
    private val imageEntryCache = ConcurrentHashMap<String, List<String>>()

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
        return imageEntryCache[cacheKey] ?: Archive(file).use { archive ->
            archive.fileHeaders
                .asSequence()
                .filter { !it.isDirectory }
                .map(::fileName)
                .filter(::isImageFile)
                .sortedBy { it.lowercase() }
                .toList()
        }.also { imageEntryCache[cacheKey] = it }
    }

    private fun decodeBitmap(file: File, entryName: String): Bitmap? {
        Archive(file).use { archive ->
            val header = archive.fileHeaders.firstOrNull { !it.isDirectory && fileName(it) == entryName } ?: return null
            val bytes = archive.getInputStream(header).use { inputStream ->
                inputStream.readBytes()
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
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

    private fun fileName(header: FileHeader): String = header.fileName
}
