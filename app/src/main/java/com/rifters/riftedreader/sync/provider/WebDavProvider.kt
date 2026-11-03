package com.rifters.riftedreader.sync.provider

import com.rifters.riftedreader.sync.model.CloudProviderType
import com.rifters.riftedreader.sync.model.ProviderAuthState
import com.rifters.riftedreader.sync.model.RemoteFileRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * WebDAV sync provider scaffolding (Stage 8) (supports Nextcloud, ownCloud, Fastmail, etc.).
 * Scaffolding only: methods return not implemented / error pending architectural decisions.
 */
class WebDavProvider : CloudProvider {
    override val providerType: CloudProviderType = CloudProviderType.WEBDAV

    private val _authState = MutableStateFlow<ProviderAuthState>(ProviderAuthState.Unauthenticated)
    override val authState: StateFlow<ProviderAuthState> = _authState.asStateFlow()

    override suspend fun authenticate(): Result<Unit> {
        return Result.failure(NotImplementedError("WebDAV authentication pending Stage 8 setup"))
    }

    override suspend fun disconnect(): Result<Unit> {
        _authState.value = ProviderAuthState.Unauthenticated
        return Result.success(Unit)
    }

    override suspend fun listFiles(remoteDirectoryPath: String): Result<List<RemoteFileRef>> {
        return Result.failure(NotImplementedError("WebDAV listFiles pending Stage 8 implementation"))
    }

    override suspend fun downloadFile(remoteFile: RemoteFileRef, destination: File): Result<File> {
        return Result.failure(NotImplementedError("WebDAV downloadFile pending Stage 8 implementation"))
    }

    override suspend fun uploadFile(localFile: File, remoteDirectoryPath: String): Result<RemoteFileRef> {
        return Result.failure(NotImplementedError("WebDAV uploadFile pending Stage 8 implementation"))
    }

    override suspend fun deleteFile(remoteFile: RemoteFileRef): Result<Unit> {
        return Result.failure(NotImplementedError("WebDAV deleteFile pending Stage 8 implementation"))
    }
}
