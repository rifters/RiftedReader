package com.rifters.riftedreader.sync.engine

import com.rifters.riftedreader.sync.model.ConflictRecord
import com.rifters.riftedreader.sync.model.ConflictResolutionStrategy
import com.rifters.riftedreader.sync.model.QueuedSyncOperation
import com.rifters.riftedreader.sync.model.SyncState
import kotlinx.coroutines.flow.StateFlow

/**
 * High-level synchronization engine coordinating cloud providers,
 * the offline operation queue, and conflict resolution.
 */
interface SyncEngine {
    val syncState: StateFlow<SyncState>

    suspend fun syncNow(): Result<Unit>
    suspend fun enqueueOperation(operation: QueuedSyncOperation): Result<Unit>
    suspend fun getPendingOperations(): List<QueuedSyncOperation>
    suspend fun resolveConflict(conflictId: String, strategy: ConflictResolutionStrategy): Result<Unit>
    suspend fun getActiveConflicts(): List<ConflictRecord>
}
