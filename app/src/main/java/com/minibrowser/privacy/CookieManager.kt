package com.minibrowser.privacy

import android.webkit.CookieManager
import android.webkit.WebView
import com.minibrowser.data.UrlUtils

data class CookieItem(val name: String, val value: String)

/**
 * Менеджер cookie поверх android.webkit.CookieManager (назва файлу відповідає специфікації,
 * клас названо SiteCookieManager, щоб не конфліктувати з системним).
 *
 * Обмеження WebView: CookieManager повертає тільки пари name=value для заданого URL — без
 * домену, шляху, терміну дії та прапорців Secure/HttpOnly/SameSite. Переглянути cookie
 * довільного стороннього домену без знання його URL неможливо.
 */
class SiteCookieManager(private val privateSession: PrivateSessionManager) {

    fun managerFor(isPrivate: Boolean): CookieManager {
        if (isPrivate) privateSession.cookieManagerOrNull()?.let { return it }
        return CookieManager.getInstance()
    }

    /** true, якщо видалення «приватних» cookie не зачепить звичайні (профіль ізольований). */
    fun privateIsIsolated(): Boolean = privateSession.cookieManagerOrNull() != null

    fun configure(webView: WebView, isPrivate: Boolean, allowThirdParty: Boolean) {
        val cm = managerFor(isPrivate)
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, allowThirdParty)
    }

    fun cookiesFor(url: String, isPrivate: Boolean): List<CookieItem> {
        if (!UrlUtils.isHttp(url)) return emptyList()
        val raw = managerFor(isPrivate).getCookie(url) ?: return emptyList()
        return raw.split(';').mapNotNull { part ->
            val t = part.trim()
            if (t.isEmpty()) null else {
                val eq = t.indexOf('=')
                if (eq < 0) CookieItem(t, "") else CookieItem(t.substring(0, eq), t.substring(eq + 1))
            }
        }
    }

    /** Видаляє одну cookie, перебираючи можливі домени (хост і батьківські) та шляхи. */
    fun deleteCookie(url: String, name: String, isPrivate: Boolean) {
        val cm = managerFor(isPrivate)
        val host = UrlUtils.hostOf(url) ?: return
        val secure = if (url.startsWith("https://", ignoreCase = true)) "; Secure" else ""
        val expired = "Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0"
        val domains = ArrayList<String?>()
        domains += null
        if (!UrlUtils.isIpAddress(host)) {
            var h = host
            while (h.contains('.')) {
                domains += h
                domains += ".$h"
                h = h.substringAfter('.')
            }
        }
        val paths = LinkedHashSet<String>()
        paths += "/"
        val path = try {
            android.net.Uri.parse(url).path.orEmpty()
        } catch (e: Exception) {
            ""
        }
        var acc = ""
        for (seg in path.split('/').filter { it.isNotEmpty() }.dropLast(1)) {
            acc += "/$seg"
            paths += acc
        }
        for (d in domains) {
            for (p in paths) {
                val domainPart = if (d == null) "" else "; Domain=$d"
                cm.setCookie(url, "$name=; $expired; Path=$p$domainPart$secure")
            }
        }
        cm.flush()
    }

    fun clearSite(url: String, isPrivate: Boolean): Int {
        val items = cookiesFor(url, isPrivate)
        items.forEach { deleteCookie(url, it.name, isPrivate) }
        return items.size
    }

    fun clearAll(isPrivate: Boolean, done: (() -> Unit)? = null) {
        val cm = managerFor(isPrivate)
        cm.removeAllCookies { done?.invoke() }
        cm.flush()
    }

    fun flush() {
        CookieManager.getInstance().flush()
        privateSession.cookieManagerOrNull()?.flush()
    }
}
