package com.minibrowser.privacy

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.minibrowser.data.SettingsStore
import com.minibrowser.data.UrlUtils
import com.minibrowser.web.InternalPages
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Центральний модуль приватності: вирішує, що робити з кожним мережевим запитом вкладки,
 * веде статистику й застосовує налаштування захисту до WebView.
 *
 * Обмеження Android WebView (див. README):
 *  - shouldInterceptRequest не викликається для POST-тіл, WebSocket та частини запитів Service Worker;
 *  - тип ресурсу (script/image/...) наближено визначається евристиками, не точно;
 *  - косметичні правила (приховування елементів) і $csp/$redirect/$removeparam не підтримуються;
 *  - окремі сторонні cookie порахувати неможливо — рахуються лише запити, надіслані при вимкнених
 *    сторонніх cookie.
 */
class PrivacyEngine(
    val settings: SettingsStore,
    val filterLists: FilterListManager,
    val blocker: TrackingBlocker,
    val dashboard: PrivacyDashboard,
    val fingerprint: FingerprintProtection,
    val cookies: SiteCookieManager,
    val permissions: SitePermissionsManager,
    val privateSession: PrivateSessionManager,
) {
    /** Хости, для яких користувач підтвердив відкриття попри попередження (до кінця процесу). */
    private val suspiciousAllowed = ConcurrentHashMap.newKeySet<String>()

    fun allowSuspicious(host: String) {
        suspiciousAllowed.add(host)
    }

    /** Рівень захисту для сайту: явне налаштування сайту або рівень за замовчуванням. */
    fun levelFor(pageHost: String?): ProtectionLevel {
        val s = settings.value
        if (pageHost.isNullOrEmpty()) return if (s.strictByDefault) ProtectionLevel.STRICT else ProtectionLevel.STANDARD
        val key = UrlUtils.registrableDomain(pageHost)
        return when {
            key in s.protectionOffSites -> ProtectionLevel.OFF
            key in s.strictSites -> ProtectionLevel.STRICT
            key in s.standardSites -> ProtectionLevel.STANDARD
            s.strictByDefault -> ProtectionLevel.STRICT
            else -> ProtectionLevel.STANDARD
        }
    }

    fun isProtectionOffFor(pageHost: String?): Boolean =
        !pageHost.isNullOrEmpty() && levelFor(pageHost) == ProtectionLevel.OFF

    fun setSiteLevel(pageHost: String, level: ProtectionLevel) {
        val key = UrlUtils.registrableDomain(pageHost)
        settings.update { s ->
            s.copy(
                protectionOffSites = s.protectionOffSites - key + if (level == ProtectionLevel.OFF) setOf(key) else emptySet(),
                strictSites = s.strictSites - key + if (level == ProtectionLevel.STRICT) setOf(key) else emptySet(),
                standardSites = s.standardSites - key + if (level == ProtectionLevel.STANDARD) setOf(key) else emptySet(),
            )
        }
    }

    /** Повертає сайт до рівня за замовчуванням. */
    fun resetSiteLevel(key: String) {
        settings.update { s ->
            s.copy(
                protectionOffSites = s.protectionOffSites - key,
                strictSites = s.strictSites - key,
                standardSites = s.standardSites - key,
            )
        }
    }

    // ------------------------------------------------------------------ https-first

    private val httpAllowed = ConcurrentHashMap.newKeySet<String>()

    /** Користувач свідомо дозволив небезпечний http для хоста (до кінця процесу). */
    fun allowHttp(host: String) {
        httpAllowed.add(host)
    }

    /**
     * Повертає https-варіант адреси, якщо http-адресу треба автоматично оновити, інакше null.
     * Не чіпає IP-адреси, localhost, одноярусні та .local-імена, а також хости, для яких користувач дозволив http.
     */
    fun httpsUpgradeFor(url: String): String? {
        val s = settings.value
        if (!s.httpsUpgrade) return null
        if (!url.startsWith("http://", ignoreCase = true)) return null
        val host = UrlUtils.hostOf(url) ?: return null
        if (host in httpAllowed) return null
        if (!host.contains('.') || UrlUtils.isIpAddress(host) || host.endsWith(".local") || host.endsWith(".localhost")) return null
        if (levelFor(host) == ProtectionLevel.OFF) return null
        return "https://" + url.substring("http://".length)
    }

    // ------------------------------------------------------------------ дані сайту

    /**
     * Видаляє cookie та сховище (localStorage, IndexedDB, WebSQL) сайту.
     * Повертає кількість видалених cookie. Кеш HTTP за сайтами WebView видаляти не вміє.
     */
    fun clearSiteData(host: String, isPrivate: Boolean): Int {
        if (host.isEmpty()) return 0
        val n = cookies.clearSite("https://$host/", isPrivate)
        try {
            val ws = (if (isPrivate) privateSession.webStorageOrNull() else null) ?: android.webkit.WebStorage.getInstance()
            ws.deleteOrigin("https://$host")
            ws.deleteOrigin("http://$host")
            val reg = UrlUtils.registrableDomain(host)
            if (reg != host) {
                ws.deleteOrigin("https://$reg")
                ws.deleteOrigin("http://$reg")
            }
        } catch (_: Exception) {
        }
        cookies.flush()
        return n
    }

    /** Застосовує поточні налаштування до WebView (викликати на UI-потоці). */
    fun applySettings(webView: WebView, isPrivate: Boolean, pageHost: String? = null) {
        val s = settings.value
        fingerprint.setEnabled(webView, s.fingerprintProtection)
        applyCookiePolicy(webView, isPrivate, pageHost)
    }

    /** Політика сторонніх cookie залежить від рівня захисту поточного сайту (діє на весь WebView вкладки). */
    fun applyCookiePolicy(webView: WebView, isPrivate: Boolean, pageHost: String?) {
        val s = settings.value
        val allow = when (levelFor(pageHost)) {
            ProtectionLevel.OFF -> true
            ProtectionLevel.STRICT -> false
            ProtectionLevel.STANDARD -> !s.thirdPartyCookieProtection
        }
        cookies.configure(webView, isPrivate, allowThirdParty = allow)
    }

    private fun enabled(category: ListCategory, level: ProtectionLevel): Boolean {
        if (level == ProtectionLevel.STRICT) return true
        val s = settings.value
        return when (category) {
            ListCategory.AD -> s.adBlocking
            ListCategory.TRACKER -> s.trackingProtection
            ListCategory.FINGERPRINT -> s.fingerprintProtection
            ListCategory.MALWARE -> s.suspiciousDomainWarning
        }
    }

    /**
     * Викликається з WebViewClient.shouldInterceptRequest (фоновий потік).
     * Повертає відповідь-заглушку для заблокованого запиту, сторінку-попередження для підозрілого
     * домену або null, щоб WebView виконав запит у звичайному порядку.
     */
    fun intercept(tabId: String, pageUrl: String?, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase() ?: return null
        val url = uri.toString()
        val s = settings.value

        if (request.isForMainFrame) {
            dashboard.beginPage(tabId, url)
            dashboard.recordRequest(tabId, thirdParty = false, host = host)
            if ((s.suspiciousDomainWarning || levelFor(host) == ProtectionLevel.STRICT) && !isProtectionOffFor(host) && host !in suspiciousAllowed) {
                filterLists.awaitReady(3000)
                if (blocker.isMalwareHost(host)) {
                    dashboard.recordSuspicious(tabId, host, "Домен у списку шкідливих (URLhaus) — показано попередження")
                    val html = InternalPages.maliciousPage(url, host)
                    return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
                }
            }
            return null
        }

        val pageHost = UrlUtils.hostOf(pageUrl)
        val third = UrlUtils.isThirdParty(host, pageHost)
        dashboard.recordRequest(tabId, third, host)

        val level = levelFor(pageHost)
        if (level == ProtectionLevel.OFF) return null

        // Підозрілі сторонні запити (евристики, див. README).
        if (third && UrlUtils.isIpAddress(host)) {
            dashboard.recordSuspicious(tabId, host, "Сторонній запит до IP-адреси замість домену")
        }
        if (scheme == "http" && pageUrl?.startsWith("https://", ignoreCase = true) == true) {
            dashboard.recordSuspicious(tabId, host, "Незахищений http-запит зі сторінки https")
        }

        // Списки ще завантажуються — чекаємо (ми у фоновому потоці), але не довше кількох секунд.
        if (!filterLists.isReady() && !filterLists.awaitReady(4000)) return null

        val type = TrackingBlocker.inferType(uri.path, request.requestHeaders ?: emptyMap())
        val verdict = blocker.evaluate(url, host, pageHost, third, type)
        if (verdict.matched.isNotEmpty()) {
            val category = verdict.detected!!
            dashboard.recordDetected(tabId, host, category)
            if (verdict.matched.any { it.category == ListCategory.MALWARE }) {
                dashboard.recordSuspicious(tabId, host, "Домен у списку шкідливих (URLhaus)")
            }
            val by = verdict.blockedBy { enabled(it, level) }
            if (by != null) {
                dashboard.recordBlocked(tabId, host, by.category)
                return blockedResponse()
            }
        }
        // Суворий рівень: усі сторонні скрипти, iframe, XHR/fetch та object блокуються, навіть якщо їх немає у списках.
        // Тип ресурсу визначається евристично; запити з невідомим типом (0) не блокуються.
        if (level == ProtectionLevel.STRICT && third) {
            val strictTypes = ResourceType.SCRIPT or ResourceType.SUBDOC or ResourceType.XHR or ResourceType.OBJECT
            if (type and strictTypes != 0) {
                dashboard.recordStrictBlocked(tabId, host)
                return blockedResponse()
            }
        }
        val cookiesOff = level == ProtectionLevel.STRICT || (level == ProtectionLevel.STANDARD && s.thirdPartyCookieProtection)
        if (third && cookiesOff) dashboard.recordCookieSuppressed(tabId)
        return null
    }

    private fun blockedResponse() = WebResourceResponse(
        "text/plain", "UTF-8", 403, "Blocked by MiniBrowser",
        mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"),
        ByteArrayInputStream(ByteArray(0)),
    )
}
