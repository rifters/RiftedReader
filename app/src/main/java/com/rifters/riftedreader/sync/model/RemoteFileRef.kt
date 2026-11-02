package com.rifters.riftedreader.sync.model

/**
 * References a remote file hosted on a cloud storage provider (Google Drive, Dropbox, WebDAV).
 */
data class RemoteFileRef(
    val id: String,
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val lastModifiedEpochMs: Long,
    val mimeType: String? = null,
    val checksum: String? = null,
    val providerType: CloudProviderType
)
