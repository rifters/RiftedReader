package com.rifters.riftedreader.data.calibre

import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.Response

class CalibreAuthInterceptor(
    private val credentialStore: CalibreCredentialStore,
    private val usernameProvider: () -> String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val username = usernameProvider().trim()
        if (username.isEmpty()) return chain.proceed(chain.request())

        val request = chain.request().newBuilder()
            .header("Authorization", Credentials.basic(username, credentialStore.loadPassword()))
            .build()
        return chain.proceed(request)
    }
}
