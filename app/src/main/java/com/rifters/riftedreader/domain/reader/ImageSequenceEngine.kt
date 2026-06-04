package com.rifters.riftedreader.domain.reader

import android.graphics.Bitmap
import android.util.Base64
import com.rifters.riftedreader.util.ReaderConstants.IMAGE_CACHE_SIZE
import com.rifters.riftedreader.util.ReaderConstants.IMAGE_RENDER_DPI
import java.io.ByteArrayOutputStream
import java.util.Collections
import kotlin.math.roundToInt

data class ImagePage(
    val index: Int,
    val width: Int,
    val height: Int,
    val dataUri: String
)

class ImageSequenceEngine(
    private val maxCachePages: Int = IMAGE_CACHE_SIZE,
    private val renderDpi: Int = IMAGE_RENDER_DPI
) {

    private data class CachedPage(
        val width: Int,
        val height: Int,
        val dataUri: String
    )

    private val pageCache = Collections.synchronizedMap(object : LinkedHashMap<Int, CachedPage>(maxCachePages, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, CachedPage>?): Boolean {
            return size > maxCachePages
        }
    })

    @Synchronized
    fun renderPage(bitmap: Bitmap): String = encodeBitmap(bitmap)

    @Synchronized
    fun renderPage(pageIndex: Int, bitmap: Bitmap): ImagePage {
        val cached = pageCache[pageIndex]
        if (cached != null) {
            return ImagePage(
                index = pageIndex,
                width = cached.width,
                height = cached.height,
                dataUri = cached.dataUri
            )
        }

        val dataUri = encodeBitmap(bitmap)
        pageCache[pageIndex] = CachedPage(
            width = bitmap.width,
            height = bitmap.height,
            dataUri = dataUri
        )
        return ImagePage(
            index = pageIndex,
            width = bitmap.width,
            height = bitmap.height,
            dataUri = dataUri
        )
    }

    @Synchronized
    fun cachedPageDataUri(pageIndex: Int): String? = pageCache[pageIndex]?.dataUri

    /**
     * Scale source dimensions using the render DPI.
     *
     * PDF pages are typically authored around 72 DPI, so the default sourceDpi
     * matches that convention and scales the rendered bitmap to the target DPI.
     */
    fun scaledDimensions(sourceWidth: Int, sourceHeight: Int, sourceDpi: Int = 72): Pair<Int, Int> {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }
        val scale = renderDpi.toFloat() / sourceDpi.toFloat()
        return (sourceWidth * scale).roundToInt().coerceAtLeast(1) to
            (sourceHeight * scale).roundToInt().coerceAtLeast(1)
    }

    fun toHtmlPage(page: ImagePage): String {
        return """
            <div class="image-page" data-image-page="${page.index}">
                <img
                    src="${page.dataUri}"
                    alt="Page ${page.index + 1}"
                    width="${page.width}"
                    height="${page.height}"
                    loading="eager"
                    decoding="async"
                />
            </div>
        """.trimIndent()
    }

    private fun encodeBitmap(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        val encoded = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        return "data:image/png;base64,$encoded"
    }
}
