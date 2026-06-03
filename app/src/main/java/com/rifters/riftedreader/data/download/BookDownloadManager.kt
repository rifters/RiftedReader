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

class BookDownloadManager(
    context: Context,
    private val bookRepository: BookRepository,
    private val client: OkHttpClient = DEFAULT_CLIENT,
) {
    private val appContext = context.applicationContext
    // Serializes metadata insertion so duplicate checks and inserts cannot interleave.
    private val importMutex = Mutex()

    suspend fun downloadFromUrl(
        url: String,
        filename: String,
        headers: Map<String, String> = emptyMap(),
    ): Result<BookMeta> = withContext(Dispatchers.IO) {
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
    }

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
        while (counter < MAX_FILENAME_ATTEMPTS) {
            val candidate = if (counter == 0) safeName else uniqueName(safeName, counter)
            val destination = File(directory, candidate)
            if (destination.createNewFile()) {
                return destination
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
        return name.replace(UNSAFE_FILENAME_CHARS, "_").trim()
            .ifEmpty { throw BookDownloadException("Downloaded filename must not be blank") }
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

        private const val IMPORTS_DIRECTORY = "imports"
        private const val MAX_FILENAME_ATTEMPTS = 1_000
        private val UNSAFE_FILENAME_CHARS = Regex("[\\\\/:*?\"<>|]+")
        private val DEFAULT_CLIENT = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}

open class BookDownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

class UnsupportedBookDownloadException(filename: String) :
    BookDownloadException("\"$filename\" is not a supported book format")

class DuplicateBookDownloadException(val existingBook: BookMeta) :
    BookDownloadException("\"${existingBook.title}\" is already in your library")
