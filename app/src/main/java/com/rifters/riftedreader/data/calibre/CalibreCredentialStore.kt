package com.rifters.riftedreader.data.calibre

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.security.GeneralSecurityException

class CalibreCredentialStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs by lazy {
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("Calibre credential storage is unavailable", e)
        } catch (e: IOException) {
            throw IllegalStateException("Calibre credential storage is unavailable", e)
        }
    }

    fun savePassword(password: String) {
        prefs.edit { putString(KEY_CONTENT_SERVER_PASSWORD, password) }
    }

    fun loadPassword(): String = prefs.getString(KEY_CONTENT_SERVER_PASSWORD, null).orEmpty()

    fun clearPassword() {
        prefs.edit { remove(KEY_CONTENT_SERVER_PASSWORD) }
    }

    companion object {
        private const val PREFS_NAME = "calibre_credentials"
        private const val KEY_CONTENT_SERVER_PASSWORD = "content_server_password"
    }
}
