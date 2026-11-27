package com.minibrowser.web

import com.minibrowser.data.JsonFileStore
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

/**
 * Список вкладок, активна вкладка та їх збереження між запусками.
 * Приватні вкладки НІКОЛИ не записуються на диск.
 * Усі методи викликаються з головного потоку.
 */
class TabManager(
    private val store: JsonFileStore,
    private val scope: CoroutineScope,
) {
    private val file = "tabs.json"
    private val mutex = Mutex()

    private val _tabs = MutableStateFlow<List<BrowserTab>>(emptyList())
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    val active: BrowserTab? get() = _tabs.value.firstOrNull { it.id == _activeId.value }

    fun get(id: String): BrowserTab? = _tabs.value.firstOrNull { it.id == id }

    /** Відновлює вкладки з диска; якщо їх немає — створює одну порожню. */
    fun restore() {
        var restored = false
        val text = store.read(file)
        if (text != null) {
            try {
                val root = JSONObject(text)
                val arr = root.getJSONArray("tabs")
                val list = ArrayList<BrowserTab>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list += BrowserTab(
                        id = o.getString("id"),
                        url = o.optString("url"),
                        title = o.optString("title"),
                        isPrivate = false,
                        createdAt = o.optLong("t", System.currentTimeMillis()),
                    )
                }
                if (list.isNotEmpty()) {
                    _tabs.value = list
                    val saved = root.optString("active")
                    _activeId.value = list.firstOrNull { it.id == saved }?.id ?: list.last().id
                    restored = true
                }
            } catch (_: Exception) {
            }
        }
        if (!restored) newTab(activate = true)
    }

    fun newTab(url: String = "", isPrivate: Boolean = false, activate: Boolean = true, title: String = ""): BrowserTab {
        val tab = BrowserTab(url = url, isPrivate = isPrivate, title = title)
        _tabs.value = _tabs.value + tab
        if (activate || _activeId.value == null) _activeId.value = tab.id
        persist()
        return tab
    }

    fun select(id: String) {
        if (_tabs.value.any { it.id == id }) {
            _activeId.value = id
            persist()
        }
    }

    /**
     * Закриває вкладку й повертає її. Якщо закрито активну — вибирається сусідня того ж типу
     * (звичайна/приватна), інакше будь-яка; якщо вкладок не лишилось, створюється порожня.
     */
    fun close(id: String): BrowserTab? {
        val list = _tabs.value
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val closed = list[idx]
        val rest = list.filterNot { it.id == id }
        _tabs.value = rest
        if (_activeId.value == id) {
            val before = rest.take(idx).lastOrNull { it.isPrivate == closed.isPrivate }
            val after = rest.drop(idx).firstOrNull { it.isPrivate == closed.isPrivate }
            val next = after ?: before ?: rest.lastOrNull()
            _activeId.value = next?.id
        }
        if (_tabs.value.isEmpty()) newTab(activate = true)
        persist()
        return closed
    }

    /** Закриває всі вкладки вказаного типу й повертає закриті. */
    fun closeAll(isPrivate: Boolean): List<BrowserTab> {
        val closed = _tabs.value.filter { it.isPrivate == isPrivate }
        _tabs.value = _tabs.value.filterNot { it.isPrivate == isPrivate }
        if (_tabs.value.none { it.id == _activeId.value }) {
            _activeId.value = _tabs.value.lastOrNull()?.id
        }
        if (_tabs.value.isEmpty()) newTab(activate = true)
        persist()
        return closed
    }

    fun update(id: String, persistChange: Boolean = false, block: (BrowserTab) -> BrowserTab) {
        val list = _tabs.value
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        val old = list[idx]
        val new = block(old)
        if (new == old) return
        _tabs.value = list.toMutableList().also { it[idx] = new }
        if (persistChange || new.url != old.url || new.title != old.title) persist()
    }

    private fun persist() {
        val snapshotTabs = _tabs.value.filterNot { it.isPrivate }
        val active = _activeId.value
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                val arr = JSONArray()
                for (t in snapshotTabs) {
                    arr.put(JSONObject().put("id", t.id).put("url", t.url).put("title", t.title).put("t", t.createdAt))
                }
                val activeRegular = snapshotTabs.firstOrNull { it.id == active }?.id ?: snapshotTabs.lastOrNull()?.id.orEmpty()
                val root = JSONObject().put("tabs", arr).put("active", activeRegular)
                try {
                    store.write(file, root.toString())
                } catch (_: Exception) {
                }
            }
        }
    }
}
