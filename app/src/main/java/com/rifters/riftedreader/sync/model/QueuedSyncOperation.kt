package com.rifters.riftedreader.sync.model

enum class SyncOperationType {
    UPLOAD_BOOK,
    DOWNLOAD_BOOK,
    DELETE_REMOTE_BOOK,
    SYNC_READING_POSITION,
    SYNC_BOOKMARK
}

enum class SyncOperationStatus {
    PENDING,
    IN_PROGRESS,
    FAILED_RETRYABLE,
    FAILED_PERMANENT,
    COMPLETED
}

/**
 * Represents an operation persisted in the offline synchronization queue
 * to be processed when connectivity is restored or WorkManager triggers.
 */
data class QueuedSyncOperation(
    val id: String,
    val type: SyncOperationType,
    val targetLocalPath: String,
    val targetRemotePath: String,
    val payloadJson: String? = null,
    val providerType: CloudProviderType,
    val status: SyncOperationStatus = SyncOperationStatus.PENDING,
    val retryCount: Int = 0,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val lastAttemptEpochMs: Long? = null,
    val lastErrorMessage: String? = null
)
