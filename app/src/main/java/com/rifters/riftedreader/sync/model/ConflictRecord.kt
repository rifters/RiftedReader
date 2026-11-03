package com.rifters.riftedreader.sync.model

enum class ConflictResolutionStrategy {
    SERVER_WINS,
    CLIENT_WINS,
    LATEST_TIMESTAMP_WINS,
    MANUAL_PROMPT
}

/**
 * Captures a sync collision (e.g. reading position changed on two devices simultaneously,
 * or book file modified both locally and in cloud storage).
 */
data class ConflictRecord(
    val conflictId: String,
    val bookId: Long,
    val bookTitle: String,
    val localTimestampEpochMs: Long,
    val remoteTimestampEpochMs: Long,
    val localValueJson: String,
    val remoteValueJson: String,
    val detectedAtEpochMs: Long = System.currentTimeMillis(),
    val resolved: Boolean = false,
    val resolutionChoice: ConflictResolutionStrategy? = null
)
