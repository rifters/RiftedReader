package com.rifters.riftedreader.data.download

import android.content.Context
import com.rifters.riftedreader.data.database.entities.BookMeta
import com.rifters.riftedreader.data.repository.BookRepository
import com.rifters.riftedreader.domain.parser.ParserFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.rifters.riftedreader.util.ReaderConstants.DOWNLOAD_CONNECT_TIMEOUT_SECONDS
import com.rifters.riftedreader.util.ReaderConstants.DOWNLOAD_FILENAME_MAX_ATTEMPTS
import com.rifters.riftedreader.util.ReaderConstants.DOWNLOAD_READ_TIMEOUT_SECONDS
import com.rifters.riftedreader.util.ReaderConstants.DOWNLOAD_WRITE_TIMEOUT_SECONDS

class BookDownloadManager(
    context: Context,
    private val bookRepository: BookRepository,
    private val client: OkHttpClient = DEFAULT_CLIENT,
) {
    private val appContext = context.applicationContext
    private val notifHelper = DownloadNotificationHelper(appContext)
    // Serializes metadata insertion so duplicate checks and inserts cannot interleave.
    private val importMutex = Mutex()

    suspend fun downloadFromUrl(
        url: String,
        filename: String,
        headers: Map<String, String> = emptyMap(),
    ): Result<BookMeta> {
        val notifId = notifIdFor(url)
        notifHelper.notifyProgress(notifId, filename)
        return try {
            withContext(Dispatchers.IO) {
                var downloadedFile: File? = null
                try {
                    val destination = createDestination(filename)
                    downloadedFile = destination
                    downloadToFile(url, headers, destination)
                    Result.success(importDownloadedBook(destination))
                } catch (throwable: Throwable) {
                    if (throwable is CancellationException) throw throwable
                    downloadedFile?.delete()
                    Result.failure(throwable)
                }
            }.also { result ->
                result
                    .onSuccess { book -> notifHelper.notifySuccess(notifId, book.title) }
                    .onFailure { error ->
                        notifHelper.notifyFailure(
                            notifId,
                            error.message.orEmpty().ifBlank { "Unknown error" }
                        )
                    }
            }
        } catch (throwable: CancellationException) {
            notifHelper.cancel(notifId)
            throw throwable
        }
    }

    private fun notifIdFor(url: String): Int = url.hashCode()

    private fun downloadToFile(url: String, headers: Map<String, String>, destination: File) {
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
        headers.forEach { (name, value) ->
            if (name.isNotBlank()) {
                requestBuilder.header(name, value)
            }
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw BookDownloadException(
                    "Download failed with HTTP ${response.code}: ${response.message.ifBlank { "Unknown error" }}"
                )
            }
            val body = response.body ?: throw BookDownloadException("Download response was empty")
            body.byteStream().use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private suspend fun importDownloadedBook(file: File): BookMeta {
        return importMutex.withLock {
            val parser = ParserFactory.getParser(file)
                ?: throw UnsupportedBookDownloadException(file.name)

            bookRepository.getBookByPath(file.absolutePath)?.let { existing ->
                throw DuplicateBookDownloadException(existing)
            }

            parser.extractMetadata(file).also { metadata ->
                bookRepository.insertBook(metadata)
            }
        }
    }

    private fun createDestination(filename: String): File {
        val directory = File(appContext.filesDir, IMPORTS_DIRECTORY).apply {
            if (!exists() && !mkdirs()) {
                throw IOException("Failed to create imports directory at $absolutePath. Check available storage.")
            }
        }
        val safeName = sanitizeFileName(filename)
        var counter = 0
        while (counter < DOWNLOAD_FILENAME_MAX_ATTEMPTS) {
            val candidate = if (counter == 0) safeName else uniqueName(safeName, counter)
            val destination = File(directory, candidate)
            if (destination.createNewFile()) {
                return destination
            }
            if (!destination.exists()) {
                throw IOException("Unable to create import file at ${destination.absolutePath}")
            }
            counter++
        }
        throw IOException("Unable to reserve a unique import filename for $safeName")
    }

    private fun uniqueName(fileName: String, counter: Int): String {
        val base = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "")
        return if (extension.isEmpty()) {
            "${base}_$counter"
        } else {
            "${base}_$counter.$extension"
        }
    }

    private fun sanitizeFileName(name: String): String {
        val sanitized = name.replace(UNSAFE_FILENAME_CHARS, "_").trim()
            .ifEmpty { FALLBACK_FILENAME }
        if (sanitized.length <= MAX_FILENAME_LENGTH) {
            return sanitized
        }

        val base = sanitized.substringBeforeLast('.', sanitized)
        val extension = sanitized.substringAfterLast('.', "")
        val suffix = if (extension.isEmpty()) "" else ".$extension"
        val maxBaseLength = (MAX_FILENAME_LENGTH - suffix.length).coerceAtLeast(1)
        return base.take(maxBaseLength) + suffix
    }

    companion object {
        @Volatile
        private var INSTANCE: BookDownloadManager? = null

        fun getInstance(context: Context, bookRepository: BookRepository): BookDownloadManager {
            val current = INSTANCE
            if (current != null) return current
            return synchronized(this) {
                INSTANCE ?: BookDownloadManager(context, bookRepository).also { INSTANCE = it }
            }
        }

        // Reuse the library's internal import location so downloaded books survive app restarts.
        private const val IMPORTS_DIRECTORY = "imports"
        private const val MAX_FILENAME_LENGTH = 120
        private const val FALLBACK_FILENAME = "downloaded_book"
        private val UNSAFE_FILENAME_CHARS = Regex("[\\\\/:*?\"<>|]+")
        private val DEFAULT_CLIENT = OkHttpClient.Builder()
            .connectTimeout(DOWNLOAD_CONNECT_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
            .readTimeout(DOWNLOAD_READ_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
            .writeTimeout(DOWNLOAD_WRITE_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
            .build()
    }
}

open class BookDownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

class UnsupportedBookDownloadException(filename: String) :
    BookDownloadException("\"$filename\" is not a supported book format")

class DuplicateBookDownloadException(val existingBook: BookMeta) :
    BookDownloadException("\"${existingBook.title}\" is already in your library")
