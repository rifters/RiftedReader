package com.rifters.riftedreader.sync.model

enum class CloudProviderType {
    GOOGLE_DRIVE,
    DROPBOX,
    WEBDAV
}

/**
 * Represents the active sync status of the synchronization engine.
 */
sealed interface SyncState {
    data object Idle : SyncState
    data class Syncing(
        val provider: CloudProviderType,
        val currentItem: String,
        val completedItems: Int,
        val totalItems: Int
    ) : SyncState
    data class Success(val lastSyncEpochMs: Long, val syncedCount: Int) : SyncState
    data class Conflict(val conflicts: List<ConflictRecord>) : SyncState
    data class Error(val message: String, val throwable: Throwable? = null) : SyncState
}

/**
 * Provider-specific connection and authentication status.
 */
sealed interface ProviderAuthState {
    data object Unauthenticated : ProviderAuthState
    data object Authenticating : ProviderAuthState
    data class Authenticated(val accountName: String, val lastVerifiedEpochMs: Long) : ProviderAuthState
    data class Error(val message: String) : ProviderAuthState
}
