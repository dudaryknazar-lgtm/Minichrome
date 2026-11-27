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

    fun isProtectionOffFor(pageHost: String?): Boolean {
        if (pageHost.isNullOrEmpty()) return false
        return UrlUtils.registrableDomain(pageHost) in settings.value.protectionOffSites
    }

    fun setSiteProtection(pageHost: String, enabled: Boolean) {
        val key = UrlUtils.registrableDomain(pageHost)
        settings.update { s ->
            s.copy(protectionOffSites = if (enabled) s.protectionOffSites - key else s.protectionOffSites + key)
        }
    }

    /** Застосовує поточні налаштування до WebView (викликати на UI-потоці). */
    fun applySettings(webView: WebView, isPrivate: Boolean) {
        val s = settings.value
        cookies.configure(webView, isPrivate, allowThirdParty = !s.thirdPartyCookieProtection)
        fingerprint.setEnabled(webView, s.fingerprintProtection)
    }

    private fun enabled(category: ListCategory): Boolean {
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
            if (s.suspiciousDomainWarning && !isProtectionOffFor(host) && host !in suspiciousAllowed) {
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

        if (isProtectionOffFor(pageHost)) return null

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
            val by = verdict.blockedBy { enabled(it) }
            if (by != null) {
                dashboard.recordBlocked(tabId, host, by.category)
                return blockedResponse()
            }
        }
        if (third && s.thirdPartyCookieProtection) dashboard.recordCookieSuppressed(tabId)
        return null
    }

    private fun blockedResponse() = WebResourceResponse(
        "text/plain", "UTF-8", 403, "Blocked by MiniBrowser",
        mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"),
        ByteArrayInputStream(ByteArray(0)),
    )
}
