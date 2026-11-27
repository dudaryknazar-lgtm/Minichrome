package com.minibrowser.privacy

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

data class BlockedHost(val host: String, val category: ListCategory, val count: Int)
data class SuspiciousRequest(val host: String, val reason: String)
data class PermissionEvent(val type: SitePermissionType, val decision: PermissionDecision, val atMs: Long)
data class FingerprintAttempt(val kind: String, val frameHost: String?)

/** Незмінний знімок статистики поточної сторінки вкладки для UI. Усі числа — реальні лічильники. */
data class PrivacySnapshot(
    val pageUrl: String = "",
    val totalRequests: Int = 0,
    val thirdPartyRequests: Int = 0,
    val blockedRequests: Int = 0,
    val blockedByCategory: Map<ListCategory, Int> = emptyMap(),
    val blockedDomainsByCategory: Map<ListCategory, Int> = emptyMap(),
    /** Унікальні домени, що збіглися зі списками (незалежно від того, чи ввімкнене блокування). */
    val detectedDomainsByCategory: Map<ListCategory, Int> = emptyMap(),
    /** Сторонні запити, надіслані при вимкнених сторонніх cookie. */
    val cookieSuppressedRequests: Int = 0,
    val topBlocked: List<BlockedHost> = emptyList(),
    val thirdPartyHosts: List<String> = emptyList(),
    val suspicious: List<SuspiciousRequest> = emptyList(),
    val fingerprintAttempts: List<FingerprintAttempt> = emptyList(),
    val permissionEvents: List<PermissionEvent> = emptyList(),
    /** Сторонні запити, заблоковані суворим рівнем (скрипти, iframe, XHR/fetch, object). */
    val strictBlocked: Int = 0,
    val strictHosts: List<Pair<String, Int>> = emptyList(),
    /** 1, якщо адресу цієї сторінки було автоматично змінено з http на https. */
    val httpsUpgraded: Int = 0,
) {
    fun blocked(c: ListCategory) = blockedByCategory[c] ?: 0
    fun blockedDomains(c: ListCategory) = blockedDomainsByCategory[c] ?: 0
    fun detectedDomains(c: ListCategory) = detectedDomainsByCategory[c] ?: 0
}

private class PageStats(var pageUrl: String) {
    var total = 0
    var thirdParty = 0
    var blocked = 0
    var cookieSuppressed = 0
    var strictBlocked = 0
    var httpsUpgraded = 0
    val strictHosts = HashMap<String, Int>()
    val blockedByCat = HashMap<ListCategory, Int>()
    val blockedDomains = HashMap<ListCategory, HashSet<String>>()
    val detectedDomains = HashMap<ListCategory, HashSet<String>>()
    val blockedHosts = HashMap<String, Pair<ListCategory, Int>>()
    val thirdPartyHosts = LinkedHashSet<String>()
    val suspicious = LinkedHashMap<String, String>()
    val fingerprint = LinkedHashSet<FingerprintAttempt>()
    val permissions = ArrayList<PermissionEvent>()
}

/**
 * Статистика приватності для кожної вкладки. Лічильники оновлюються тільки реальними подіями
 * (перехоплені запити, повідомлення від детектора, запити дозволів); нічого не емулюється.
 */
class PrivacyDashboard(context: Context) {
    private val lock = Any()
    private val pages = HashMap<String, PageStats>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val notifyPending = AtomicBoolean(false)

    private val prefs = context.applicationContext.getSharedPreferences("minibrowser_privacy_stats", Context.MODE_PRIVATE)
    private var lifetime = prefs.getLong("blocked_total", 0L)

    private val _changes = MutableStateFlow(0)
    /** Збільшується (не частіше ~4 разів/с) після будь-якої зміни статистики. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private val _lifetimeBlocked = MutableStateFlow(lifetime)
    val lifetimeBlocked: StateFlow<Long> = _lifetimeBlocked.asStateFlow()

    private fun notifyChanged() {
        if (notifyPending.compareAndSet(false, true)) {
            mainHandler.postDelayed({
                notifyPending.set(false)
                _changes.value = _changes.value + 1
                val l = synchronized(lock) { lifetime }
                _lifetimeBlocked.value = l
                prefs.edit().putLong("blocked_total", l).apply()
            }, 250)
        }
    }

    private val pendingUpgrade = HashSet<String>()

    /** Позначає, що наступна сторінка вкладки завантажується після автоматичного http→https. */
    fun markHttpsUpgrade(tabId: String) {
        synchronized(lock) { pendingUpgrade.add(tabId) }
    }

    fun beginPage(tabId: String, url: String) {
        synchronized(lock) {
            val p = PageStats(url)
            if (pendingUpgrade.remove(tabId)) p.httpsUpgraded = 1
            pages[tabId] = p
        }
        notifyChanged()
    }

    fun forget(tabId: String) {
        synchronized(lock) { pages.remove(tabId) }
        notifyChanged()
    }

    private inline fun withPage(tabId: String, block: (PageStats) -> Unit) {
        synchronized(lock) {
            val p = pages.getOrPut(tabId) { PageStats("") }
            block(p)
        }
        notifyChanged()
    }

    fun recordRequest(tabId: String, thirdParty: Boolean, host: String) = withPage(tabId) {
        it.total++
        if (thirdParty) {
            it.thirdParty++
            if (it.thirdPartyHosts.size < 300) it.thirdPartyHosts.add(host)
        }
    }

    fun recordDetected(tabId: String, host: String, category: ListCategory) = withPage(tabId) {
        it.detectedDomains.getOrPut(category) { HashSet() }.add(host)
    }

    fun recordBlocked(tabId: String, host: String, category: ListCategory) = withPage(tabId) {
        it.blocked++
        it.blockedByCat[category] = (it.blockedByCat[category] ?: 0) + 1
        it.blockedDomains.getOrPut(category) { HashSet() }.add(host)
        val prev = it.blockedHosts[host]
        it.blockedHosts[host] = category to ((prev?.second ?: 0) + 1)
        lifetime++
    }

    fun recordStrictBlocked(tabId: String, host: String) = withPage(tabId) {
        it.blocked++
        it.strictBlocked++
        it.strictHosts[host] = (it.strictHosts[host] ?: 0) + 1
        lifetime++
    }

    fun recordCookieSuppressed(tabId: String) = withPage(tabId) { it.cookieSuppressed++ }

    fun recordSuspicious(tabId: String, host: String, reason: String) = withPage(tabId) {
        if (it.suspicious.size < 100) it.suspicious.putIfAbsent("$host|$reason", reason)
    }

    fun recordFingerprint(tabId: String, kind: String, frameHost: String?) = withPage(tabId) {
        it.fingerprint.add(FingerprintAttempt(kind, frameHost))
    }

    fun recordPermission(tabId: String, type: SitePermissionType, decision: PermissionDecision) = withPage(tabId) {
        it.permissions.add(PermissionEvent(type, decision, System.currentTimeMillis()))
    }

    fun snapshot(tabId: String?): PrivacySnapshot {
        if (tabId == null) return PrivacySnapshot()
        synchronized(lock) {
            val p = pages[tabId] ?: return PrivacySnapshot()
            return PrivacySnapshot(
                pageUrl = p.pageUrl,
                totalRequests = p.total,
                thirdPartyRequests = p.thirdParty,
                blockedRequests = p.blocked,
                blockedByCategory = HashMap(p.blockedByCat),
                blockedDomainsByCategory = p.blockedDomains.mapValues { it.value.size },
                detectedDomainsByCategory = p.detectedDomains.mapValues { it.value.size },
                cookieSuppressedRequests = p.cookieSuppressed,
                topBlocked = p.blockedHosts.entries
                    .map { BlockedHost(it.key, it.value.first, it.value.second) }
                    .sortedByDescending { it.count }
                    .take(30),
                thirdPartyHosts = p.thirdPartyHosts.toList().sorted(),
                suspicious = p.suspicious.entries.map { e -> SuspiciousRequest(e.key.substringBefore('|'), e.value) },
                fingerprintAttempts = p.fingerprint.toList(),
                permissionEvents = p.permissions.toList(),
                strictBlocked = p.strictBlocked,
                strictHosts = p.strictHosts.entries.sortedByDescending { it.value }.take(30).map { it.key to it.value },
                httpsUpgraded = p.httpsUpgraded,
            )
        }
    }
}
