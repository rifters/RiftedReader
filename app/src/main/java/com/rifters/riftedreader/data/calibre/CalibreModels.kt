package com.rifters.riftedreader.data.calibre

data class CalibreBook(
    val id: Int,
    val title: String,
    val authors: List<String>,
    val formats: List<String>,
    val tags: List<String>,
    val series: String?,
    val seriesIndex: Double?,
    val coverUrl: String?,
    val publishedDate: String?
)

data class CalibreLibrary(
    val books: List<CalibreBook>,
    val totalCount: Int
)
