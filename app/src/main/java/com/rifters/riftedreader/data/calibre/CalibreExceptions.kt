package com.rifters.riftedreader.data.calibre

sealed class CalibreException(message: String, cause: Throwable? = null) : Exception(message, cause)

class CalibreAuthException(message: String, cause: Throwable? = null) : CalibreException(message, cause)

class CalibreNotFoundException(message: String, cause: Throwable? = null) : CalibreException(message, cause)

class CalibreNetworkException(message: String, cause: Throwable? = null) : CalibreException(message, cause)

class CalibreConfigException(message: String, cause: Throwable? = null) : CalibreException(message, cause)

class CalibreApiException(
    val code: Int,
    message: String,
    cause: Throwable? = null,
) : CalibreException(message, cause)

class CalibreParseException(message: String, cause: Throwable? = null) : CalibreException(message, cause)
