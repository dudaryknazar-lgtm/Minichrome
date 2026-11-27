package com.minibrowser.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Bookmark(
    val id: String,
    val url: String,
    val title: String,
    /** Показується на головній сторінці як швидкий доступ. */
    val favorite: Boolean,
    val createdAt: Long,
)

class BookmarkManager(
    private val store: JsonFileStore,
    private val scope: CoroutineScope,
) {
    private val file = "bookmarks.json"
    private val mutex = Mutex()

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    fun load() {
        val text = store.read(file)
        if (text == null) {
            // Перший запуск: початкові улюблені сайти.
            val now = System.currentTimeMillis()
            _bookmarks.value = DefaultFavorites.sites.mapIndexed { i, (title, url) ->
                Bookmark(UUID.randomUUID().toString(), url, title, true, now + i)
            }
            persist()
            return
        }
        try {
            val arr = JSONArray(text)
            val list = ArrayList<Bookmark>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list += Bookmark(
                    o.getString("id"), o.getString("url"), o.optString("title"),
                    o.optBoolean("fav", false), o.optLong("t", 0L),
                )
            }
            _bookmarks.value = list
        } catch (_: Exception) {
        }
    }

    fun isBookmarked(url: String): Boolean = _bookmarks.value.any { it.url == url }

    /** Додає закладку або видаляє, якщо вона вже є. Повертає true, якщо закладку додано. */
    @Synchronized
    fun toggle(url: String, title: String): Boolean {
        if (!UrlUtils.isHttp(url)) return false
        val existing = _bookmarks.value.firstOrNull { it.url == url }
        return if (existing != null) {
            _bookmarks.value = _bookmarks.value.filterNot { it.id == existing.id }
            persist()
            false
        } else {
            val t = title.ifBlank { UrlUtils.displayHost(url) }
            _bookmarks.value = _bookmarks.value + Bookmark(UUID.randomUUID().toString(), url, t, false, System.currentTimeMillis())
            persist()
            true
        }
    }

    @Synchronized
    fun update(id: String, title: String, url: String) {
        if (!UrlUtils.isHttp(url)) return
        _bookmarks.value = _bookmarks.value.map {
            if (it.id == id) it.copy(title = title.ifBlank { UrlUtils.displayHost(url) }, url = url.trim()) else it
        }
        persist()
    }

    @Synchronized
    fun setFavorite(id: String, favorite: Boolean) {
        _bookmarks.value = _bookmarks.value.map { if (it.id == id) it.copy(favorite = favorite) else it }
        persist()
    }

    @Synchronized
    fun remove(id: String) {
        _bookmarks.value = _bookmarks.value.filterNot { it.id == id }
        persist()
    }

    fun suggestions(query: String, limit: Int): List<Bookmark> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return _bookmarks.value.filter { it.title.lowercase().contains(q) || it.url.lowercase().contains(q) }.take(limit)
    }

    private fun persist() {
        val snapshot = _bookmarks.value
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                val arr = JSONArray()
                for (b in snapshot) {
                    arr.put(
                        JSONObject().put("id", b.id).put("url", b.url).put("title", b.title)
                            .put("fav", b.favorite).put("t", b.createdAt),
                    )
                }
                try {
                    store.write(file, arr.toString())
                } catch (_: Exception) {
                }
            }
        }
    }
}
