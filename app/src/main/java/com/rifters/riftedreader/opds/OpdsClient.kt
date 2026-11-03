package com.rifters.riftedreader.opds

import com.rifters.riftedreader.opds.model.OpdsFeed
import java.io.File

/**
 * Interface for navigating and acquiring books from OPDS (Open Publication Distribution System) catalogs.
 */
interface OpdsClient {
    suspend fun getFeed(feedUrl: String, headers: Map<String, String> = emptyMap()): Result<OpdsFeed>
    suspend fun search(searchUrl: String, query: String, headers: Map<String, String> = emptyMap()): Result<OpdsFeed>
    suspend fun downloadAcquisition(acquisitionUrl: String, destination: File, headers: Map<String, String> = emptyMap()): Result<File>
}
