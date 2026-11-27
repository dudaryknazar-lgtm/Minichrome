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

data class HistoryEntry(
    val id: Long,
    val url: String,
    val title: String,
    val visitedAt: Long,
)

/** Історія відвідувань (лише звичайні вкладки; приватні сюди не потрапляють). */
class HistoryManager(
    private val store: JsonFileStore,
    private val scope: CoroutineScope,
) {
    private val file = "history.json"
    private val maxEntries = 3000
    private val mutex = Mutex()

    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    /** Від найновіших до найстаріших. */
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    private var nextId = 1L

    fun load() {
        val text = store.read(file) ?: return
        try {
            val arr = JSONArray(text)
            val list = ArrayList<HistoryEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list += HistoryEntry(o.getLong("id"), o.getString("url"), o.optString("title"), o.getLong("t"))
            }
            list.sortByDescending { it.visitedAt }
            nextId = (list.maxOfOrNull { it.id } ?: 0L) + 1
            _entries.value = list
        } catch (e: Exception) {
            // Пошкоджений файл ігноруємо: історія просто порожня.
        }
    }

    @Synchronized
    fun record(url: String, title: String?) {
        if (!UrlUtils.isHttp(url)) return
        val now = System.currentTimeMillis()
        val cur = _entries.value
        val first = cur.firstOrNull()
        val updated = if (first != null && first.url == url && now - first.visitedAt < 30 * 60_000L) {
            // Повторне відвідування тієї самої сторінки поспіль — оновлюємо запис.
            listOf(first.copy(visitedAt = now, title = title?.takeIf { it.isNotBlank() } ?: first.title)) + cur.drop(1)
        } else {
            listOf(HistoryEntry(nextId++, url, title.orEmpty(), now)) + cur
        }
        _entries.value = if (updated.size > maxEntries) updated.take(maxEntries) else updated
        persist()
    }

    @Synchronized
    fun updateTitle(url: String, title: String?) {
        if (title.isNullOrBlank()) return
        val cur = _entries.value
        val idx = cur.indexOfFirst { it.url == url }
        if (idx < 0 || cur[idx].title == title) return
        _entries.value = cur.toMutableList().also { it[idx] = it[idx].copy(title = title) }
        persist()
    }

    @Synchronized
    fun remove(id: Long) {
        _entries.value = _entries.value.filterNot { it.id == id }
        persist()
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
        persist()
    }

    fun search(query: String): List<HistoryEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return _entries.value
        return _entries.value.filter { it.title.lowercase().contains(q) || it.url.lowercase().contains(q) }
    }

    /** Унікальні нещодавно відвідані сайти (за хостом) для головної сторінки. */
    fun recentSites(limit: Int): List<HistoryEntry> {
        val seen = HashSet<String>()
        val out = ArrayList<HistoryEntry>()
        for (e in _entries.value) {
            val host = UrlUtils.hostOf(e.url) ?: continue
            if (seen.add(host)) {
                out += e
                if (out.size >= limit) break
            }
        }
        return out
    }

    private fun persist() {
        val snapshot = _entries.value
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                val arr = JSONArray()
                for (e in snapshot) {
                    arr.put(JSONObject().put("id", e.id).put("url", e.url).put("title", e.title).put("t", e.visitedAt))
                }
                try {
                    store.write(file, arr.toString())
                } catch (_: Exception) {
                }
            }
        }
    }
}
