package com.rifters.riftedreader.data.calibre

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import java.lang.reflect.Type

data class CalibreListResponse(
    val books: Map<Int, CalibreBookMetadata>,
    val totalCount: Int = books.size,
    val errorMessage: String? = null,
)

data class CalibreBookMetadata(
    val id: Int?,
    val title: String,
    val authors: List<String>,
    val formats: List<String>,
    val tags: List<String>,
    val series: String?,
    val seriesIndex: Double?,
    val publishedDate: String?,
)

class CalibreListResponseDeserializer : JsonDeserializer<CalibreListResponse> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): CalibreListResponse {
        val root = json.asObjectOrNull() ?: throw JsonParseException("Expected Calibre response object")
        val error = root.firstString("err", "error")
        val booksElement = root.firstPresent("result", "data", "metadata") ?: json
        val books = parseBooks(booksElement)
        return CalibreListResponse(
            books = books,
            totalCount = root.firstInt("total", "total_count", "count") ?: books.size,
            errorMessage = error,
        )
    }

    private fun parseBooks(element: JsonElement): Map<Int, CalibreBookMetadata> {
        val result = linkedMapOf<Int, CalibreBookMetadata>()
        when {
            element.isJsonObject -> {
                val obj = element.asJsonObject
                obj.entrySet().forEach { (key, value) ->
                    val valueObject = value.asObjectOrNull() ?: return@forEach
                    val id = key.toIntOrNull() ?: valueObject.firstInt("book_id", "id") ?: return@forEach
                    result[id] = valueObject.toMetadata(id)
                }
            }

            element.isJsonArray -> {
                element.asJsonArray.forEach { item ->
                    val valueObject = item.asObjectOrNull() ?: return@forEach
                    val id = valueObject.firstInt("book_id", "id") ?: return@forEach
                    result[id] = valueObject.toMetadata(id)
                }
            }
        }
        return result
    }

    private fun JsonObject.toMetadata(id: Int): CalibreBookMetadata {
        return CalibreBookMetadata(
            id = firstInt("book_id", "id") ?: id,
            title = firstString("title") ?: DEFAULT_TITLE,
            authors = firstStringList("authors"),
            formats = parseFormats(firstPresent("formats")),
            tags = firstStringList("tags"),
            series = firstString("series")?.takeIf { it.isNotBlank() },
            seriesIndex = firstDouble("series_index", "seriesIndex"),
            publishedDate = firstString("pubdate", "published", "publishedDate"),
        )
    }

    private fun parseFormats(element: JsonElement?): List<String> {
        return when {
            element == null || element is JsonNull -> emptyList()
            element.isJsonArray -> element.asJsonArray.mapNotNull { it.asStringOrNull() }
            element.isJsonObject -> element.asJsonObject.keySet().toList()
            else -> element.asStringOrNull()
                ?.split(',', ';')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        }.map { it.uppercase() }.distinct()
    }

    private fun JsonObject.firstPresent(vararg names: String): JsonElement? {
        return names.firstNotNullOfOrNull { name ->
            get(name)?.takeUnless { it is JsonNull }
        }
    }

    private fun JsonObject.firstString(vararg names: String): String? {
        return firstPresent(*names)?.asStringOrNull()
    }

    private fun JsonObject.firstStringList(vararg names: String): List<String> {
        val element = firstPresent(*names) ?: return emptyList()
        return when {
            element.isJsonArray -> element.asJsonArray.mapNotNull { it.asStringOrNull() }
            else -> element.asStringOrNull()
                ?.split(',', ';', '&')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        }
    }

    private fun JsonObject.firstInt(vararg names: String): Int? {
        return firstPresent(*names)?.asIntOrNull()
    }

    private fun JsonObject.firstDouble(vararg names: String): Double? {
        return firstPresent(*names)?.asDoubleOrNull()
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? {
        return takeIf { it.isJsonObject }?.asJsonObject
    }

    private fun JsonElement.asStringOrNull(): String? {
        return takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
    }

    private fun JsonElement.asIntOrNull(): Int? {
        if (!isJsonPrimitive) return null
        val primitive = asJsonPrimitive
        return if (primitive.isNumber) primitive.asInt else primitive.asString.toIntOrNull()
    }

    private fun JsonElement.asDoubleOrNull(): Double? {
        if (!isJsonPrimitive) return null
        val primitive = asJsonPrimitive
        return if (primitive.isNumber) primitive.asDouble else primitive.asString.toDoubleOrNull()
    }

    private companion object {
        const val DEFAULT_TITLE = "Untitled"
    }
}
