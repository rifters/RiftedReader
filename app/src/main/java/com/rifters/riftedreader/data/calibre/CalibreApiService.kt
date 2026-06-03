package com.rifters.riftedreader.data.calibre

import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface CalibreApiService {
    @POST("cdb/cmd/list/0")
    suspend fun listBooks(@Body request: List<Any?>): CalibreListResponse

    @POST("cdb/cmd/search/0")
    suspend fun searchBooks(@Body request: List<Any?>): CalibreListResponse

    @GET("get/{format}/{bookId}/{filename}")
    suspend fun downloadBook(
        @Path("format") format: String,
        @Path("bookId") bookId: Int,
        @Path("filename") filename: String,
    ): ResponseBody
}
