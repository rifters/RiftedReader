package com.rifters.riftedreader.domain.parser

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import com.rifters.riftedreader.data.database.entities.BookMeta
import com.rifters.riftedreader.domain.reader.ImageSequenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import android.os.ParcelFileDescriptor

/**
 * Parser for PDF format rendered to image pages for the existing WebView reader stack.
 */
class PdfParser : BookParser {
    
    companion object {
        private val SUPPORTED_EXTENSIONS = listOf("pdf")
    }

    private val imageSequenceEngine = ImageSequenceEngine()
    
    override fun canParse(file: File): Boolean {
        return file.extension.lowercase() in SUPPORTED_EXTENSIONS
    }
    
    override suspend fun extractMetadata(file: File): BookMeta {
        val pageCount = getPageCount(file)
        return BookMeta(
            path = file.absolutePath,
            title = file.nameWithoutExtension.ifBlank { "PDF" },
            format = "PDF",
            size = file.length(),
            totalPages = pageCount,
            description = "Rendered as image pages in the shared reader stack."
        )
    }
    
    override suspend fun getPageContent(file: File, page: Int): PageContent {
        return withContext(Dispatchers.IO) {
            renderPage(file, page) ?: placeholderPage(page)
        }
    }
    
    override suspend fun getPageCount(file: File): Int {
        return withContext(Dispatchers.IO) {
            runCatching {
                openRenderer(file).use { renderer ->
                    renderer.pageCount.coerceAtLeast(1)
                }
            }.getOrDefault(1)
        }
    }
    
    override suspend fun getTableOfContents(file: File): List<TocEntry> {
        return emptyList()
    }

    private fun renderPage(file: File, pageIndex: Int): PageContent? {
        return runCatching {
            openRenderer(file).use { renderer ->
                if (pageIndex !in 0 until renderer.pageCount) {
                    return@use null
                }

                renderer.openPage(pageIndex).use { page ->
                    val (width, height) = imageSequenceEngine.scaledDimensions(page.width, page.height)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val imagePage = imageSequenceEngine.renderPage(pageIndex, bitmap)
                        PageContent(
                            text = "PDF page ${pageIndex + 1}",
                            html = imageSequenceEngine.toHtmlPage(imagePage),
                            title = file.nameWithoutExtension.ifBlank { "PDF" }
                        )
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }.getOrNull()
    }

    private fun openRenderer(file: File): PdfRenderer {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return PdfRenderer(descriptor)
    }

    private fun placeholderPage(page: Int): PageContent {
        return PageContent(
            text = "Could not open PDF",
            html = """
                <div class="image-page image-page-error">
                    <h2>Could not open PDF</h2>
                    <p>This file may be corrupt or password-protected.</p>
                    <p>Page ${page + 1} could not be rendered.</p>
                </div>
            """.trimIndent(),
            title = "PDF"
        )
    }
}
