package com.rifters.riftedreader.sync.credentials

import com.rifters.riftedreader.sync.model.CloudProviderType

data class ProviderCredentials(
    val providerType: CloudProviderType,
    val serverUrl: String? = null,
    val username: String? = null,
    val tokenOrPassword: String? = null,
    val refreshToken: String? = null,
    val tokenExpiryEpochMs: Long? = null
)

/**
 * Interface for securely storing and retrieving credentials per cloud provider.
 */
interface SyncCredentialStore {
    suspend fun saveCredentials(credentials: ProviderCredentials): Result<Unit>
    suspend fun getCredentials(providerType: CloudProviderType): ProviderCredentials?
    suspend fun clearCredentials(providerType: CloudProviderType): Result<Unit>
    suspend fun hasCredentials(providerType: CloudProviderType): Boolean
}
