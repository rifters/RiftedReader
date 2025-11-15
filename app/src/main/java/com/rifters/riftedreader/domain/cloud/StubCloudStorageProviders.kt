package com.rifters.riftedreader.domain.cloud

import kotlinx.coroutines.delay

/**
 * Stage 8 scaffolding: simple in-memory stubs that make it easy to wire UI
 * while the real SDK integrations are developed.
 */
object StubCloudStorageProviders {

    fun googleDrive(): CloudStorageProvider = object : CloudStorageProvider {
        override val id: CloudProviderId = CloudProviderId.GOOGLE_DRIVE
        override val displayName: String = "Google Drive (stub)"

        override suspend fun listBooks(): Result<List<CloudBookEntry>> {
            delay(200)
            return Result.success(emptyList())
        }

        override suspend fun download(entry: CloudBookEntry): Result<ByteArray> {
            delay(200)
            return Result.failure(UnsupportedOperationException("Download not implemented in stub"))
        }

        override suspend fun uploadProgress(progress: CloudReadingProgress): Result<Unit> {
            delay(100)
            return Result.failure(UnsupportedOperationException("Upload not implemented in stub"))
        }
    }
}
