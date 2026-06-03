package com.rifters.riftedreader.data.calibre

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

private val Context.calibreConnectionDataStore by preferencesDataStore(name = "calibre_connection_preferences")

class DefaultCalibreConnectionRepository(
    context: Context,
    private val credentialStore: CalibreCredentialStore = CalibreCredentialStore(context),
    private val client: OkHttpClient = OkHttpClient()
) : CalibreConnectionRepository {

    private val appContext = context.applicationContext

    override suspend fun saveConfig(config: CalibreConnectionConfig) {
        if (config.contentServerPassword.isEmpty()) {
            credentialStore.clearPassword()
        } else {
            credentialStore.savePassword(config.contentServerPassword)
        }

        appContext.calibreConnectionDataStore.edit { preferences ->
            preferences[KEY_CONTENT_SERVER_URL] = config.contentServerUrl.trim()
            preferences[KEY_CONTENT_SERVER_USERNAME] = config.contentServerUsername.trim()
            preferences[KEY_CONTENT_SERVER_ENABLED] = config.contentServerEnabled
            preferences[KEY_CALIBRE_WEB_URL] = config.calibreWebUrl.trim()
            preferences[KEY_CALIBRE_WEB_ENABLED] = config.calibreWebEnabled
            preferences[KEY_DOWNLOAD_DIRECTORY] = config.downloadDirectory
            preferences[KEY_PREFERRED_FORMAT] = config.preferredFormat.name
        }
    }

    override suspend fun loadConfig(): CalibreConnectionConfig = configFlow().first()

    override fun configFlow(): Flow<CalibreConnectionConfig> = appContext.calibreConnectionDataStore.data.map { preferences ->
        val formatName = preferences[KEY_PREFERRED_FORMAT] ?: BookFormat.ANY.name
        CalibreConnectionConfig(
            contentServerUrl = preferences[KEY_CONTENT_SERVER_URL].orEmpty(),
            contentServerUsername = preferences[KEY_CONTENT_SERVER_USERNAME].orEmpty(),
            contentServerPassword = credentialStore.loadPassword(),
            contentServerEnabled = preferences[KEY_CONTENT_SERVER_ENABLED] ?: false,
            calibreWebUrl = preferences[KEY_CALIBRE_WEB_URL].orEmpty(),
            calibreWebEnabled = preferences[KEY_CALIBRE_WEB_ENABLED] ?: false,
            downloadDirectory = preferences[KEY_DOWNLOAD_DIRECTORY] ?: defaultDownloadDirectory(),
            preferredFormat = runCatching { BookFormat.valueOf(formatName) }.getOrDefault(BookFormat.ANY)
        )
    }

    override suspend fun testContentServerConnection(): ConnectionTestResult = withContext(Dispatchers.IO) {
        val config = loadConfig()
        CalibreUrlValidator.validateContentServerUrl(config.contentServerUrl)?.let { error ->
            return@withContext ConnectionTestResult.Failed(error, null)
        }

        val requestBuilder = Request.Builder()
            .url("${config.contentServerUrl}/cdb/cmd/list/0")
            .get()

        if (config.contentServerUsername.isNotBlank() || config.contentServerPassword.isNotEmpty()) {
            requestBuilder.header(
                "Authorization",
                Credentials.basic(config.contentServerUsername, config.contentServerPassword)
            )
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                when {
                    response.isSuccessful -> ConnectionTestResult.Success
                    response.code == HTTP_UNAUTHORIZED -> ConnectionTestResult.AuthRequired(parseRealm(response.header("WWW-Authenticate")))
                    else -> ConnectionTestResult.Failed(response.message.ifBlank { "Connection failed" }, response.code)
                }
            }
        } catch (e: IOException) {
            ConnectionTestResult.Failed(e.localizedMessage ?: "Connection failed", null)
        } catch (e: IllegalArgumentException) {
            ConnectionTestResult.Failed(e.localizedMessage ?: "Invalid URL", null)
        }
    }

    private fun defaultDownloadDirectory(): String {
        return appContext.getExternalFilesDir(null)?.absolutePath ?: appContext.filesDir.absolutePath
    }

    private fun parseRealm(header: String?): String {
        if (header.isNullOrBlank()) return ""
        val match = Regex("realm=\"([^\"]*)\"|realm=([^,\\s]+)").find(header) ?: return ""
        return match.groups[1]?.value ?: match.groups[2]?.value.orEmpty()
    }

    companion object {
        private const val HTTP_UNAUTHORIZED = 401

        private val KEY_CONTENT_SERVER_URL = stringPreferencesKey("content_server_url")
        private val KEY_CONTENT_SERVER_USERNAME = stringPreferencesKey("content_server_username")
        private val KEY_CONTENT_SERVER_ENABLED = booleanPreferencesKey("content_server_enabled")
        private val KEY_CALIBRE_WEB_URL = stringPreferencesKey("calibre_web_url")
        private val KEY_CALIBRE_WEB_ENABLED = booleanPreferencesKey("calibre_web_enabled")
        private val KEY_DOWNLOAD_DIRECTORY = stringPreferencesKey("download_directory")
        private val KEY_PREFERRED_FORMAT = stringPreferencesKey("preferred_format")
    }
}
