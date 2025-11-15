package com.rifters.riftedreader.domain.library

enum class TagsUpdateMode {
    REPLACE,
    APPEND
}

enum class FavoriteUpdate {
    NoChange,
    Favorite,
    NotFavorite
}

data class BookMetadataUpdate(
    val title: String? = null,
    val author: String? = null,
    val clearAuthor: Boolean = false,
    val tags: List<String>? = null,
    val tagsMode: TagsUpdateMode = TagsUpdateMode.REPLACE,
    val favorite: FavoriteUpdate = FavoriteUpdate.NoChange
)
