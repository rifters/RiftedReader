package com.rifters.riftedreader.domain.parser

import java.util.Locale

/**
 * Describes how well RiftedReader currently supports a given ebook format.
 *
 * The structure mirrors LibreraReader's extractor catalogue found under
 * `foobnix/ext/` (see LIBRERA_ANALYSIS.md §1) but keeps the implementation
 * Kotlin-first. Each descriptor captures the primary file extensions,
 * MIME-types, and an implementation status so downstream features can
 * surface a consistent capability matrix (library filters, import dialogs,
 * documentation tables, etc.).
 */
object FormatCatalog {

    private val descriptors: List<FormatDescriptor> = listOf(
        FormatDescriptor(
            id = "txt",
            displayName = "Plain text",
            extensions = setOf("txt", "text"),
            mimeTypes = setOf("text/plain"),
            status = FormatSupportStatus.SUPPORTED,
            notes = "Baseline implementation using streaming reader, inspired by foobnix/ext/TxtExtract.java"
        ),
        FormatDescriptor(
            id = "epub",
            displayName = "EPUB",
            extensions = setOf("epub", "epub3"),
            mimeTypes = setOf("application/epub+zip"),
            status = FormatSupportStatus.SUPPORTED,
            notes = "Zip-based extractor similar to Librera's EpubExtractor"
        ),
        FormatDescriptor(
            id = "pdf",
            displayName = "PDF",
            extensions = setOf("pdf"),
            mimeTypes = setOf("application/pdf"),
            status = FormatSupportStatus.SUPPORTED,
            notes = "Backed by AndroidPdfViewer wrapper as described in LIBRERA_ANALYSIS.md"
        ),
        // Stage 6 roadmap entries -----------------------------------------------------------
        FormatDescriptor(
            id = "mobi",
            displayName = "MOBI",
            extensions = setOf("mobi", "azw", "azw3"),
            mimeTypes = setOf(
                "application/x-mobipocket-ebook",
                "application/octet-stream"
            ),
            status = FormatSupportStatus.IN_PROGRESS,
            notes = "Planned converter using libmobi parity (foobnix/ext/MobiExtract.java)"
        ),
        FormatDescriptor(
            id = "fb2",
            displayName = "FB2",
            extensions = setOf("fb2", "fb2.zip"),
            mimeTypes = setOf("application/xml", "application/zip"),
            status = FormatSupportStatus.IN_PROGRESS,
            notes = "XML-based parsing similar to Librera's Fb2Extractor"
        ),
        FormatDescriptor(
            id = "cbz",
            displayName = "CBZ",
            extensions = setOf("cbz"),
            mimeTypes = setOf("application/x-cbz", "application/zip"),
            status = FormatSupportStatus.IN_PROGRESS,
            notes = "Zip image archive parsing (foobnix/ext/CbzCbrExtractor.java)"
        ),
        FormatDescriptor(
            id = "cbr",
            displayName = "CBR",
            extensions = setOf("cbr"),
            mimeTypes = setOf("application/x-cbr", "application/octet-stream"),
            status = FormatSupportStatus.PLANNED,
            notes = "RAR archive extraction using commons-compress or junrar"
        ),
        FormatDescriptor(
            id = "rtf",
            displayName = "RTF",
            extensions = setOf("rtf"),
            mimeTypes = setOf("application/rtf", "text/rtf"),
            status = FormatSupportStatus.PLANNED,
            notes = "Conversion parity with Librera's RtfExtract"
        ),
        FormatDescriptor(
            id = "html",
            displayName = "HTML",
            extensions = setOf("html", "htm", "xhtml"),
            mimeTypes = setOf("text/html", "application/xhtml+xml"),
            status = FormatSupportStatus.SUPPORTED,
            notes = "Jsoup-based parser similar to Librera's HtmlExtractor"
        ),
        FormatDescriptor(
            id = "docx",
            displayName = "DOCX",
            extensions = setOf("docx"),
            mimeTypes = setOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            status = FormatSupportStatus.PLANNED,
            notes = "Planned Mammoth-based converter akin to Librera's DocxExtractor"
        )
    )

    fun descriptors(): List<FormatDescriptor> = descriptors

    fun descriptorByExtension(extension: String): FormatDescriptor? {
        val normalized = extension.lowercase(Locale.getDefault())
        return descriptors.firstOrNull { normalized in it.extensions }
    }

    fun supportedDescriptors(): List<FormatDescriptor> = descriptors.filter { it.status == FormatSupportStatus.SUPPORTED }

    fun supportedExtensions(): Set<String> = supportedDescriptors().flatMapTo(linkedSetOf()) { it.extensions }

    fun importMimeTypes(includePreview: Boolean = false): Set<String> {
        val statuses = if (includePreview) {
            setOf(FormatSupportStatus.SUPPORTED, FormatSupportStatus.IN_PROGRESS)
        } else {
            setOf(FormatSupportStatus.SUPPORTED)
        }
        return descriptors.filter { it.status in statuses }
            .flatMapTo(linkedSetOf()) { it.mimeTypes }
    }

    fun inProgressDescriptors(): List<FormatDescriptor> = descriptors.filter { it.status == FormatSupportStatus.IN_PROGRESS }

    fun plannedDescriptors(): List<FormatDescriptor> = descriptors.filter { it.status == FormatSupportStatus.PLANNED }

    fun previewParsers(): List<PreviewParser> = inProgressDescriptors().map { PreviewParser(it) }
}

enum class FormatSupportStatus {
    SUPPORTED,
    IN_PROGRESS,
    PLANNED
}

data class FormatDescriptor(
    val id: String,
    val displayName: String,
    val extensions: Set<String>,
    val mimeTypes: Set<String>,
    val status: FormatSupportStatus,
    val notes: String
)
