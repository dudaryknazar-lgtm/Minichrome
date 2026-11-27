package com.minibrowser.privacy

import com.minibrowser.data.JsonFileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

enum class SitePermissionType(
    val label: String,
    /** false — Android WebView не реалізує відповідний API, тож керувати дозволом неможливо. */
    val supportedByWebView: Boolean,
    val defaultDecision: PermissionDecision,
) {
    CAMERA("Камера", true, PermissionDecision.ASK),
    MICROPHONE("Мікрофон", true, PermissionDecision.ASK),
    LOCATION("Геолокація", true, PermissionDecision.ASK),
    NOTIFICATIONS("Сповіщення", false, PermissionDecision.DENY),
    FILES("Файли (вибір файлу)", true, PermissionDecision.ALLOW),
    AUTO_DOWNLOADS("Автоматичні завантаження", true, PermissionDecision.ASK),
}

enum class PermissionDecision(val label: String) {
    ASK("Запитувати"),
    ALLOW("Дозволено"),
    DENY("Заборонено"),
}

/**
 * Рішення щодо дозволів, збережені для кожного сайту (за хостом).
 * Для приватних вкладок рішення «запам'ятати» діють лише до кінця приватної сесії.
 */
class SitePermissionsManager(
    private val store: JsonFileStore,
    private val scope: CoroutineScope,
) {
    private val file = "site_permissions.json"
    private val mutex = Mutex()

    private val _decisions = MutableStateFlow<Map<String, Map<SitePermissionType, PermissionDecision>>>(emptyMap())
    val decisions: StateFlow<Map<String, Map<SitePermissionType, PermissionDecision>>> = _decisions.asStateFlow()

    private val sessionDecisions = ConcurrentHashMap<String, MutableMap<SitePermissionType, PermissionDecision>>()

    fun load() {
        val text = store.read(file) ?: return
        try {
            val root = JSONObject(text)
            val out = LinkedHashMap<String, Map<SitePermissionType, PermissionDecision>>()
            for (host in root.keys()) {
                val o = root.getJSONObject(host)
                val m = LinkedHashMap<SitePermissionType, PermissionDecision>()
                for (k in o.keys()) {
                    val type = runCatching { SitePermissionType.valueOf(k) }.getOrNull() ?: continue
                    val dec = runCatching { PermissionDecision.valueOf(o.getString(k)) }.getOrNull() ?: continue
                    if (dec != PermissionDecision.ASK) m[type] = dec
                }
                if (m.isNotEmpty()) out[host] = m
            }
            _decisions.value = out
        } catch (_: Exception) {
        }
    }

    fun get(host: String, type: SitePermissionType, isPrivate: Boolean): PermissionDecision {
        if (!type.supportedByWebView) return type.defaultDecision
        if (isPrivate) sessionDecisions[host]?.get(type)?.let { return it }
        return _decisions.value[host]?.get(type) ?: type.defaultDecision
    }

    @Synchronized
    fun set(host: String, type: SitePermissionType, decision: PermissionDecision, isPrivate: Boolean) {
        if (isPrivate) {
            val m = sessionDecisions.getOrPut(host) { ConcurrentHashMap() }
            if (decision == PermissionDecision.ASK) m.remove(type) else m[type] = decision
            return
        }
        val all = _decisions.value.toMutableMap()
        val site = (all[host] ?: emptyMap()).toMutableMap()
        if (decision == PermissionDecision.ASK) site.remove(type) else site[type] = decision
        if (site.isEmpty()) all.remove(host) else all[host] = site
        _decisions.value = all
        persist()
    }

    /** Відкликає всі рішення для сайту (повертає до «Запитувати»). */
    @Synchronized
    fun resetSite(host: String) {
        sessionDecisions.remove(host)
        val all = _decisions.value.toMutableMap()
        if (all.remove(host) != null) {
            _decisions.value = all
            persist()
        }
    }

    @Synchronized
    fun resetAll() {
        sessionDecisions.clear()
        _decisions.value = emptyMap()
        persist()
    }

    fun clearSession() {
        sessionDecisions.clear()
    }

    private fun persist() {
        val snapshot = _decisions.value
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                val root = JSONObject()
                for ((host, m) in snapshot) {
                    val o = JSONObject()
                    for ((t, d) in m) o.put(t.name, d.name)
                    root.put(host, o)
                }
                try {
                    store.write(file, root.toString())
                } catch (_: Exception) {
                }
            }
        }
    }
}
