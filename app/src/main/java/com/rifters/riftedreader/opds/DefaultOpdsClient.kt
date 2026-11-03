package com.rifters.riftedreader.opds

import com.rifters.riftedreader.opds.model.OpdsFeed
import java.io.File

/**
 * Default implementation stub for OpdsClient.
 * XML/Atom parsing will be implemented with standard XmlPullParser or Kotlinx Serialization.
 */
class DefaultOpdsClient : OpdsClient {

    override suspend fun getFeed(feedUrl: String, headers: Map<String, String>): Result<OpdsFeed> {
        return Result.failure(NotImplementedError("OpdsClient.getFeed pending Stage 8 implementation"))
    }

    override suspend fun search(
        searchUrl: String,
        query: String,
        headers: Map<String, String>
    ): Result<OpdsFeed> {
        return Result.failure(NotImplementedError("OpdsClient.search pending Stage 8 implementation"))
    }

    override suspend fun downloadAcquisition(
        acquisitionUrl: String,
        destination: File,
        headers: Map<String, String>
    ): Result<File> {
        return Result.failure(NotImplementedError("OpdsClient.downloadAcquisition pending Stage 8 implementation"))
    }
}
