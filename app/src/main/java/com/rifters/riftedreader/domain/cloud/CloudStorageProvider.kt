package com.rifters.riftedreader.domain.cloud

import java.time.Instant

/**
 * Abstraction for cloud storage integrations outlined in Stage 8 and
 * LIBRERA_ANALYSIS.md §4. Implementation classes will wrap Google Drive,
 * Dropbox, or WebDAV SDKs while surfacing a unified API to the rest of the
 * app.
 */
interface CloudStorageProvider {
    val id: CloudProviderId
    val displayName: String

    suspend fun listBooks(): Result<List<CloudBookEntry>>

    suspend fun download(entry: CloudBookEntry): Result<ByteArray>

    suspend fun uploadProgress(progress: CloudReadingProgress): Result<Unit>
}

enum class CloudProviderId {
    GOOGLE_DRIVE,
    DROPBOX,
    WEBDAV
}

data class CloudBookEntry(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val lastModified: Instant,
    val downloadUrl: String? = null
)

data class CloudReadingProgress(
    val bookId: String,
    val currentPage: Int,
    val totalPages: Int,
    val updatedAt: Instant = Instant.now()
)
