package com.rifters.riftedreader.data.calibre

import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class CalibreContentServerRepository(
    private val connectionRepository: CalibreConnectionRepository,
    private val credentialStore: CalibreCredentialStore,
    private val configProvider: (() -> CalibreConnectionConfig?)? = null,
    okHttpClientBuilder: OkHttpClient.Builder = OkHttpClient.Builder(),
) {
    @Volatile
    private var currentConfig: CalibreConnectionConfig? = null
    private var cachedBaseUrl: String? = null
    private var cachedApiService: CalibreApiService? = null
    private val configLock = Any()
    private val apiLock = Any()

    private val okHttpClient = okHttpClientBuilder
        .addInterceptor(CalibreAuthInterceptor(credentialStore) {
            synchronized(configLock) {
                currentConfig?.contentServerUsername.orEmpty()
            }
        })
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun getLibrary(
        offset: Int = 0,
        limit: Int = 50,
    ): Result<CalibreLibrary> = runCalibreCall {
        val config = loadEnabledConfig()
        val response = apiFor(config).listBooks(listRequest())
        response.toLibrary(config.contentServerUrl, offset, limit)
    }

    suspend fun searchBooks(
        query: String,
        offset: Int = 0,
        limit: Int = 50,
    ): Result<CalibreLibrary> = runCalibreCall {
        val config = loadEnabledConfig()
        val response = apiFor(config).searchBooks(searchRequest(query))
        response.toLibrary(config.contentServerUrl, offset, limit)
    }

    fun getDownloadUrl(
        book: CalibreBook,
        format: BookFormat,
    ): String {
        val config = latestConfig()
        val selectedFormat = selectFormat(book, format, config.preferredFormat)
        return config.contentServerUrl.toHttpUrl()
            .newBuilder()
            .addPathSegment("get")
            .addPathSegment(selectedFormat.name.lowercase())
            .addPathSegment(book.id.toString())
            .addPathSegment(downloadFilename(book, selectedFormat))
            .build()
            .toString()
    }

    fun getCoverUrl(bookId: Int): String {
        return latestConfig().contentServerUrl.toHttpUrl()
            .newBuilder()
            .addPathSegment("get")
            .addPathSegment("cover")
            .addPathSegment(bookId.toString())
            .addPathSegment("cover.jpg")
            .build()
            .toString()
    }

    private suspend fun loadEnabledConfig(): CalibreConnectionConfig {
        val config = connectionRepository.loadConfig()
        synchronized(configLock) {
            currentConfig = config
        }
        if (!config.contentServerEnabled) {
            throw CalibreConfigException("Content Server is not enabled")
        }
        CalibreUrlValidator.validateContentServerUrl(config.contentServerUrl)?.let { error ->
            throw CalibreConfigException(error)
        }
        return config
    }

    private fun latestConfig(): CalibreConnectionConfig {
        return configProvider?.invoke()
            ?: synchronized(configLock) {
                currentConfig
            }
            ?: throw IllegalStateException("Calibre Content Server config has not been loaded")
    }

    private fun apiFor(config: CalibreConnectionConfig): CalibreApiService {
        synchronized(apiLock) {
            val baseUrl = retrofitBaseUrl(config.contentServerUrl)
            cachedApiService?.let { service ->
                if (cachedBaseUrl == baseUrl) return service
            }

            val gson = GsonBuilder()
                .registerTypeAdapter(CalibreListResponse::class.java, CalibreListResponseDeserializer())
                .create()

            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
                .create(CalibreApiService::class.java)
                .also {
                    cachedBaseUrl = baseUrl
                    cachedApiService = it
                }
        }
    }

    private fun CalibreListResponse.toLibrary(
        baseUrl: String,
        offset: Int,
        limit: Int,
    ): CalibreLibrary {
        errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
            throw CalibreApiException(0, error)
        }
        val books = books.map { (id, metadata) ->
            metadata.toBook(id, baseUrl)
        }
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceAtLeast(0)
        return CalibreLibrary(
            books = books.drop(safeOffset).take(safeLimit),
            totalCount = totalCount,
        )
    }

    private fun CalibreBookMetadata.toBook(id: Int, baseUrl: String): CalibreBook {
        return CalibreBook(
            id = this.id ?: id,
            title = title,
            authors = authors,
            formats = formats,
            tags = tags,
            series = series,
            seriesIndex = seriesIndex,
            coverUrl = coverUrl(baseUrl, this.id ?: id),
            publishedDate = publishedDate,
        )
    }

    private fun selectFormat(
        book: CalibreBook,
        requestedFormat: BookFormat,
        preferredFormat: BookFormat,
    ): BookFormat {
        val available = book.formats.map { it.uppercase() }.toSet()
        return listOf(requestedFormat, preferredFormat)
            .firstOrNull { it != BookFormat.ANY && it.name in available }
            ?: BookFormat.entries.firstOrNull { it != BookFormat.ANY && it.name in available }
            ?: throw IllegalStateException("No supported format is available for ${book.title}")
    }

    private suspend fun <T> runCalibreCall(block: suspend () -> T): Result<T> {
        return withContext(Dispatchers.IO) {
            runCatching { block() }.recoverCatching { throwable ->
                if (throwable is CancellationException) throw throwable
                throw mapError(throwable)
            }
        }
    }

    private fun mapError(throwable: Throwable): Throwable {
        return when (throwable) {
            is CalibreException -> throwable
            is HttpException -> when (throwable.code()) {
                HTTP_UNAUTHORIZED -> CalibreAuthException("Authentication failed", throwable)
                HTTP_NOT_FOUND -> CalibreNotFoundException("Book or format not found", throwable)
                else -> CalibreApiException(
                    throwable.code(),
                    throwable.message().ifBlank { "Calibre API error" },
                    throwable,
                )
            }
            is SocketTimeoutException -> CalibreNetworkException("Connection timed out", throwable)
            is JsonSyntaxException,
            is JsonParseException -> CalibreParseException(throwable.localizedMessage ?: "Unable to parse Calibre response", throwable)
            is IOException -> CalibreNetworkException(throwable.localizedMessage ?: "Network error", throwable)
            else -> throwable
        }
    }

    private fun listRequest(): List<Any?> = listOf("", CALIBRE_FIELDS, "", false, null)

    private fun searchRequest(query: String): List<Any?> = listOf(query, SEARCH_FIELDS, "", false, null)

    private fun retrofitBaseUrl(url: String): String = url.trim().trimEnd('/') + "/"

    private fun coverUrl(baseUrl: String, bookId: Int): String {
        return baseUrl.toHttpUrl()
            .newBuilder()
            .addPathSegment("get")
            .addPathSegment("cover")
            .addPathSegment(bookId.toString())
            .addPathSegment("cover.jpg")
            .build()
            .toString()
    }

    private fun downloadFilename(book: CalibreBook, format: BookFormat): String {
        val safeTitle = book.title
            .replace(UNSAFE_FILENAME_CHARS, "_")
            .trim()
            .ifBlank { "book-${book.id}" }
        return "$safeTitle.${format.name.lowercase()}"
    }

    companion object {
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_NOT_FOUND = 404
        private const val CALIBRE_FIELDS = "book_id,title,authors,formats,tags,series,series_index,pubdate"
        private const val SEARCH_FIELDS = "book_id,title,authors,formats"
        private val UNSAFE_FILENAME_CHARS = Regex("[\\\\/:*?\"<>|]+")
    }
}
