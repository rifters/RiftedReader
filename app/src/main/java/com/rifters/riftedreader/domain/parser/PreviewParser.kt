package com.rifters.riftedreader.domain.parser

import com.rifters.riftedreader.data.database.entities.BookMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lightweight placeholder parser for formats that are part of the Stage 6 roadmap
 * but do not yet have full rendering support. Inspired by LibreraReader's
 * incremental extractor rollout, this class allows us to store reasonable
 * metadata prior to implementing full content pipelines.
 */
class PreviewParser(
    val descriptor: FormatDescriptor
) : BookParser {

    override fun canParse(file: File): Boolean {
        val extension = file.extension.lowercase()
        return extension.isNotEmpty() && extension in descriptor.extensions
    }

    override suspend fun extractMetadata(file: File): BookMeta = withContext(Dispatchers.IO) {
        val title = file.nameWithoutExtension.ifBlank { descriptor.displayName }
        BookMeta(
            path = file.absolutePath,
            format = descriptor.displayName,
            size = file.length(),
            title = title,
            description = "Preview metadata extracted for ${descriptor.displayName}. Content rendering pending Stage 6 implementation."
        )
    }

    override suspend fun getPageContent(file: File, page: Int): PageContent {
        return PageContent(
            text = buildString {
                append(descriptor.displayName)
                append(" preview support is not fully implemented yet.\n\n")
                append("This placeholder matches the Stage 6 roadmap and allows metadata indexing. ")
                append("Check the Stage 6-8 TODO summary for remaining tasks.")
            }
        )
    }

    override suspend fun getPageCount(file: File): Int = 1

    override suspend fun getTableOfContents(file: File): List<TocEntry> = emptyList()
}
