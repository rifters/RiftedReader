package com.rifters.riftedreader.data.calibre

import kotlinx.coroutines.flow.Flow

interface CalibreConnectionRepository {
    suspend fun saveConfig(config: CalibreConnectionConfig)
    suspend fun loadConfig(): CalibreConnectionConfig
    fun configFlow(): Flow<CalibreConnectionConfig>
    suspend fun testContentServerConnection(): ConnectionTestResult
}

sealed class ConnectionTestResult {
    object Success : ConnectionTestResult()
    data class AuthRequired(val realm: String) : ConnectionTestResult()
    data class Failed(val message: String, val httpCode: Int?) : ConnectionTestResult()
}
