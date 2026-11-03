package com.rifters.riftedreader.sync.engine

import com.rifters.riftedreader.sync.model.ConflictRecord
import com.rifters.riftedreader.sync.model.ConflictResolutionStrategy
import com.rifters.riftedreader.sync.model.QueuedSyncOperation
import com.rifters.riftedreader.sync.model.SyncState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Default implementation stub for SyncEngine.
 * Handles coordination of offline queues and provider calls pending Stage 8 decisions.
 */
class DefaultSyncEngine : SyncEngine {

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    override val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    override suspend fun syncNow(): Result<Unit> {
        return Result.failure(NotImplementedError("SyncEngine.syncNow pending Stage 8 implementation"))
    }

    override suspend fun enqueueOperation(operation: QueuedSyncOperation): Result<Unit> {
        return Result.failure(NotImplementedError("SyncEngine.enqueueOperation pending Stage 8 implementation"))
    }

    override suspend fun getPendingOperations(): List<QueuedSyncOperation> {
        return emptyList()
    }

    override suspend fun resolveConflict(
        conflictId: String,
        strategy: ConflictResolutionStrategy
    ): Result<Unit> {
        return Result.failure(NotImplementedError("SyncEngine.resolveConflict pending Stage 8 implementation"))
    }

    override suspend fun getActiveConflicts(): List<ConflictRecord> {
        return emptyList()
    }
}
