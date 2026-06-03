package com.rifters.riftedreader.data.calibre

data class CalibreConnectionConfig(
    val contentServerUrl: String,
    val contentServerUsername: String,
    val contentServerPassword: String,
    val contentServerEnabled: Boolean,
    val calibreWebUrl: String,
    val calibreWebEnabled: Boolean,
    val downloadDirectory: String,
    val preferredFormat: BookFormat
)

enum class BookFormat { EPUB, MOBI, PDF, ANY }
