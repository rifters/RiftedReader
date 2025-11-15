package com.rifters.riftedreader.data.preferences

import android.content.Context
import androidx.core.content.edit
import com.rifters.riftedreader.domain.library.LibrarySearchFilters
import com.rifters.riftedreader.domain.library.SavedLibrarySearch
import com.rifters.riftedreader.domain.library.SmartCollectionId
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashSet
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central place for storing and retrieving library specific preferences such as the last used
 * filters and the collection of saved searches. The implementation mirrors the lightweight
 * preference based storage used in Librera (`AppState`, `Prefs`) while keeping the API Kotlin
 * friendly.
 */
class LibraryPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _savedSearches = MutableStateFlow(readSavedSearches())
    val savedSearches: StateFlow<List<SavedLibrarySearch>> = _savedSearches.asStateFlow()

    private var cachedFilters: LibrarySearchFilters = readFiltersFromPreferences()

    fun readFilters(): LibrarySearchFilters = cachedFilters

    fun saveFilters(filters: LibrarySearchFilters) {
        if (filters == cachedFilters) return
        cachedFilters = filters
        prefs.edit { putString(KEY_LAST_FILTERS, filters.toJson().toString()) }
    }

    fun addSavedSearch(name: String, filters: LibrarySearchFilters): SavedLibrarySearch {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("Empty saved search name")
        if (_savedSearches.value.any { it.name.equals(trimmed, ignoreCase = true) }) {
            throw IllegalArgumentException("Duplicate saved search name")
        }
        val search = SavedLibrarySearch(name = trimmed, filters = filters)
        val updated = _savedSearches.value + search
        persistSavedSearches(updated)
        _savedSearches.value = updated
        return search
    }

    fun deleteSavedSearch(id: String): SavedLibrarySearch? {
        val current = _savedSearches.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index == -1) return null
        val removed = current.removeAt(index)
        persistSavedSearches(current)
        _savedSearches.value = current
        return removed
    }

    fun updateSavedSearch(
        id: String,
        name: String? = null,
        filters: LibrarySearchFilters? = null
    ): SavedLibrarySearch? {
        val current = _savedSearches.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index == -1) return null

        val trimmedName = name?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmedName != null && current.any { it.id != id && it.name.equals(trimmedName, ignoreCase = true) }) {
            throw IllegalArgumentException("Duplicate saved search name")
        }

        val existing = current[index]
        val updated = existing.copy(
            name = trimmedName ?: existing.name,
            filters = filters ?: existing.filters,
            updatedAt = System.currentTimeMillis()
        )
        current[index] = updated
        persistSavedSearches(current)
        _savedSearches.value = current
        return updated
    }

    fun getSavedSearch(id: String): SavedLibrarySearch? = _savedSearches.value.firstOrNull { it.id == id }

    private fun readFiltersFromPreferences(): LibrarySearchFilters {
        val raw = prefs.getString(KEY_LAST_FILTERS, null) ?: return LibrarySearchFilters()
        return runCatching {
            val json = JSONObject(raw)
            parseFilters(json)
        }.getOrElse { LibrarySearchFilters() }
    }

    private fun readSavedSearches(): List<SavedLibrarySearch> {
        val raw = prefs.getString(KEY_SAVED_SEARCHES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    val name = obj.optString(KEY_NAME).trim()
                    if (name.isEmpty()) continue
                    val filtersObj = obj.optJSONObject(KEY_FILTERS)
                    val filters = if (filtersObj != null) parseFilters(filtersObj) else LibrarySearchFilters()
                    val id = obj.optString(KEY_ID).ifBlank { UUID.randomUUID().toString() }
                    val createdAt = obj.optLong(KEY_CREATED_AT, System.currentTimeMillis())
                    val updatedAt = obj.optLong(KEY_UPDATED_AT, createdAt)
                    add(
                        SavedLibrarySearch(
                            id = id,
                            name = name,
                            filters = filters,
                            createdAt = createdAt,
                            updatedAt = updatedAt
                        )
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun persistSavedSearches(list: List<SavedLibrarySearch>) {
        val array = JSONArray().apply {
            list.forEach { search ->
                put(search.toJson())
            }
        }
        prefs.edit { putString(KEY_SAVED_SEARCHES, array.toString()) }
    }

    private fun LibrarySearchFilters.toJson(): JSONObject = JSONObject().apply {
        put(KEY_QUERY, query)
        put(KEY_FORMATS, JSONArray(formats.toList()))
        put(KEY_TAGS, JSONArray(tags.toList()))
        put(KEY_COLLECTIONS, JSONArray(collections.toList()))
        put(KEY_FAVORITES_ONLY, favoritesOnly)
        smartCollection?.let { put(KEY_SMART_COLLECTION, it.name) }
    }

    private fun SavedLibrarySearch.toJson(): JSONObject = JSONObject().apply {
        put(KEY_ID, id)
        put(KEY_NAME, name)
        put(KEY_CREATED_AT, createdAt)
        put(KEY_UPDATED_AT, updatedAt)
        put(KEY_FILTERS, filters.toJson())
    }

    private fun parseFilters(json: JSONObject): LibrarySearchFilters {
        val query = json.optString(KEY_QUERY, "")
        val formats = json.optJSONArray(KEY_FORMATS)?.toStringSet() ?: emptySet()
        val tags = json.optJSONArray(KEY_TAGS)?.toStringSet() ?: emptySet()
        val collections = json.optJSONArray(KEY_COLLECTIONS)?.toStringSet() ?: emptySet()
        val favoritesOnly = json.optBoolean(KEY_FAVORITES_ONLY, false)
        val smartCollection = if (json.has(KEY_SMART_COLLECTION) && !json.isNull(KEY_SMART_COLLECTION)) {
            val name = json.optString(KEY_SMART_COLLECTION)
            name.takeIf { it.isNotBlank() }?.let { runCatching { SmartCollectionId.valueOf(it) }.getOrNull() }
        } else {
            null
        }
        return LibrarySearchFilters(
            query = query,
            formats = formats,
            tags = tags,
            collections = collections,
            favoritesOnly = favoritesOnly,
            smartCollection = smartCollection
        )
    }

    private fun JSONArray.toStringSet(): Set<String> {
        val result = LinkedHashSet<String>(length())
        for (i in 0 until length()) {
            val value = optString(i)
            if (!value.isNullOrEmpty()) {
                result += value
            }
        }
        return result
    }

    companion object {
        private const val PREFS_NAME = "library_preferences"
        private const val KEY_LAST_FILTERS = "last_filters"
        private const val KEY_SAVED_SEARCHES = "saved_searches"

        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_FILTERS = "filters"
        private const val KEY_QUERY = "query"
        private const val KEY_FORMATS = "formats"
        private const val KEY_TAGS = "tags"
        private const val KEY_COLLECTIONS = "collections"
        private const val KEY_FAVORITES_ONLY = "favoritesOnly"
        private const val KEY_SMART_COLLECTION = "smartCollection"
        private const val KEY_CREATED_AT = "createdAt"
        private const val KEY_UPDATED_AT = "updatedAt"
    }
}
