package com.ufuk.kitap.data

import org.json.JSONArray
import org.json.JSONObject

/** Kitaplıktaki okunacak kitap (EPUB / PDF / TXT). id = dosya içeriğinin özeti; aynı dosya her cihazda aynı id'yi alır. */
data class Book(
    val id: String,
    val title: String,
    val author: String,
    val type: String, // "epub", "pdf", "txt"
    val size: Long,
    val addedAt: Long,
    val position: String = "",
    val percent: Float = 0f,
    val posUpdatedAt: Long = 0L,
    val lastOpened: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("author", author).put("type", type)
        .put("size", size).put("addedAt", addedAt).put("position", position)
        .put("percent", percent.toDouble()).put("posUpdatedAt", posUpdatedAt).put("lastOpened", lastOpened)

    companion object {
        fun fromJson(o: JSONObject) = Book(
            id = o.getString("id"),
            title = o.optString("title"),
            author = o.optString("author"),
            type = o.optString("type", "txt"),
            size = o.optLong("size"),
            addedAt = o.optLong("addedAt"),
            position = o.optString("position"),
            percent = o.optDouble("percent", 0.0).toFloat(),
            posUpdatedAt = o.optLong("posUpdatedAt"),
            lastOpened = o.optLong("lastOpened"),
        )
    }
}

data class Chapter(val id: String, val title: String, val body: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("body", body)

    companion object {
        fun fromJson(o: JSONObject) = Chapter(o.getString("id"), o.optString("title"), o.optString("body"))
    }
}

/** Kullanıcının yazdığı kitap (el yazması). Görseller manuscripts/<id>/images/ altında durur. */
data class Manuscript(
    val id: String,
    val title: String,
    val author: String,
    val description: String = "",
    val language: String = "tr",
    val cover: String = "", // images/ altındaki dosya adı
    val chapters: List<Chapter> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toJson(): JSONObject {
        val arr = JSONArray()
        chapters.forEach { arr.put(it.toJson()) }
        return JSONObject()
            .put("id", id).put("title", title).put("author", author).put("description", description)
            .put("language", language).put("cover", cover).put("chapters", arr)
            .put("createdAt", createdAt).put("updatedAt", updatedAt)
    }

    fun wordCount(): Int = chapters.sumOf { c -> c.body.split(Regex("\\s+")).count { it.isNotBlank() } }

    companion object {
        fun fromJson(o: JSONObject): Manuscript {
            val arr = o.optJSONArray("chapters") ?: JSONArray()
            val list = (0 until arr.length()).map { Chapter.fromJson(arr.getJSONObject(it)) }
            return Manuscript(
                id = o.getString("id"),
                title = o.optString("title"),
                author = o.optString("author"),
                description = o.optString("description"),
                language = o.optString("language", "tr"),
                cover = o.optString("cover"),
                chapters = list,
                createdAt = o.optLong("createdAt"),
                updatedAt = o.optLong("updatedAt"),
            )
        }
    }
}
