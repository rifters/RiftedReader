package com.rifters.riftedreader.sync.provider

import com.rifters.riftedreader.sync.model.CloudProviderType
import com.rifters.riftedreader.sync.model.ProviderAuthState
import com.rifters.riftedreader.sync.model.RemoteFileRef
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Universal interface for cloud storage backends (Google Drive, Dropbox, WebDAV).
 * Covers authentication state, remote listing, downloading, uploading, and deleting.
 */
interface CloudProvider {
    val providerType: CloudProviderType
    val authState: StateFlow<ProviderAuthState>

    suspend fun authenticate(): Result<Unit>
    suspend fun disconnect(): Result<Unit>

    suspend fun listFiles(remoteDirectoryPath: String): Result<List<RemoteFileRef>>
    suspend fun downloadFile(remoteFile: RemoteFileRef, destination: File): Result<File>
    suspend fun uploadFile(localFile: File, remoteDirectoryPath: String): Result<RemoteFileRef>
    suspend fun deleteFile(remoteFile: RemoteFileRef): Result<Unit>
}
