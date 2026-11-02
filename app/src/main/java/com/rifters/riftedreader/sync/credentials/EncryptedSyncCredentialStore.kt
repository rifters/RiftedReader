package com.rifters.riftedreader.sync.credentials

import com.rifters.riftedreader.sync.model.CloudProviderType

/**
 * Encrypted implementation stub for SyncCredentialStore.
 * Will use AndroidX Security Crypto (EncryptedSharedPreferences) or Jetpack DataStore
 * once provider auth strategies are finalized.
 */
class EncryptedSyncCredentialStore : SyncCredentialStore {

    override suspend fun saveCredentials(credentials: ProviderCredentials): Result<Unit> {
        return Result.failure(NotImplementedError("Encrypted credential storage pending Stage 8 implementation"))
    }

    override suspend fun getCredentials(providerType: CloudProviderType): ProviderCredentials? {
        return null
    }

    override suspend fun clearCredentials(providerType: CloudProviderType): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun hasCredentials(providerType: CloudProviderType): Boolean {
        return false
    }
}
