package com.rifters.riftedreader.data.calibre

import android.net.Uri

object CalibreUrlValidator {
    fun validateCalibreWebUrl(url: String): String? = validateHttpUrl(url, requirePort = false)

    fun validateContentServerUrl(url: String): String? = validateHttpUrl(url, requirePort = true)

    private fun validateHttpUrl(url: String, requirePort: Boolean): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return "URL is required"
        if (trimmed.endsWith('/')) return "Remove the trailing slash"

        val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
            ?: return "Enter a valid URL"
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return "URL must start with http:// or https://"
        if (uri.host.isNullOrBlank()) return "Enter a host name or IP address"
        if (requirePort && uri.port == -1) return "Content Server URL must include a port"
        return null
    }
}
