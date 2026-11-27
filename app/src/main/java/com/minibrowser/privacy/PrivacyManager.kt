package com.minibrowser.privacy

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import com.minibrowser.data.HistoryManager
import com.minibrowser.data.SettingsStore
import com.minibrowser.data.UrlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

enum class DataType(val label: String, val description: String) {
    HISTORY("Історія відвідувань", "Список відвіданих сторінок"),
    COOKIES("Cookie", "Вихід з акаунтів на сайтах"),
    CACHE("Кеш", "Збережені копії зображень і скриптів"),
    SITE_DATA("Дані сайтів", "localStorage, IndexedDB, WebSQL"),
}

/**
 * Очищення даних: історія, cookie, кеш, дані сайтів, а також автоматичне очищення.
 * Усі методи, що торкаються WebView/CookieManager, виконуються на головному потоці.
 */
class PrivacyManager(
    context: Context,
    private val settings: SettingsStore,
    private val history: HistoryManager,
    private val cookies: SiteCookieManager,
) {
    private val app = context.applicationContext

    suspend fun clear(types: Set<DataType>) = withContext(Dispatchers.Main) {
        if (DataType.HISTORY in types) history.clear()
        if (DataType.COOKIES in types) removeAllCookies()
        if (DataType.CACHE in types) {
            // Кеш HTTP спільний для всіх WebView застосунку; clearCache(true) очищає і дисковий кеш.
            WebView(app).apply {
                clearCache(true)
                destroy()
            }
        }
        if (DataType.SITE_DATA in types) {
            WebStorage.getInstance().deleteAllData()
        }
    }

    private suspend fun removeAllCookies() = suspendCancellableCoroutine<Unit> { cont ->
        val cm = CookieManager.getInstance()
        cm.removeAllCookies { if (cont.isActive) cont.resume(Unit) }
        cm.flush()
    }

    /**
     * Автоочищення за налаштуваннями. Cookie сайтів зі списку «зберігати» знімаються перед
     * видаленням і відновлюються після — тож вхід у потрібні акаунти не втрачається.
     * (Знімок містить лише name=value, без прапорців HttpOnly/SameSite/терміну дії — обмеження WebView.)
     */
    suspend fun runAutoCleanup() {
        val s = settings.value
        if (!s.autoCleanup) return
        val keep = s.cleanupKeepHosts
        if (s.cleanupCookies) {
            val snapshot = withContext(Dispatchers.Main) {
                val cm = CookieManager.getInstance()
                keep.associateWith { host -> cookies.cookiesFor("https://$host/", false) }
                    .filterValues { it.isNotEmpty() }
                    .also { cm.flush() }
            }
            removeAllCookies()
            withContext(Dispatchers.Main) {
                val cm = CookieManager.getInstance()
                for ((host, items) in snapshot) {
                    for (c in items) {
                        cm.setCookie("https://$host/", "${c.name}=${c.value}; Path=/; Secure")
                    }
                }
                cm.flush()
            }
        }
        val types = buildSet<DataType> {
            if (s.cleanupCache) add(DataType.CACHE)
            if (s.cleanupHistory) add(DataType.HISTORY)
        }
        if (types.isNotEmpty()) clear(types)
    }

    /** Чи є сайт у списку збережених від автоочищення. */
    fun isKept(host: String?): Boolean {
        if (host == null) return false
        val reg = UrlUtils.registrableDomain(host)
        return settings.value.cleanupKeepHosts.any { UrlUtils.registrableDomain(it) == reg }
    }
}
