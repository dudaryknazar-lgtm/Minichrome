package com.minibrowser.data

import android.net.Uri
import java.net.URLEncoder

object UrlUtils {

    fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            Uri.parse(url).host?.lowercase()
        } catch (e: Exception) {
            null
        }
    }

    fun isHttp(url: String?): Boolean {
        val u = url?.trim()?.lowercase() ?: return false
        return u.startsWith("http://") || u.startsWith("https://")
    }

    fun isIpAddress(host: String): Boolean {
        if (host.contains(':')) return true // IPv6
        val parts = host.split('.')
        return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } }
    }

    private val secondLevel = setOf("co", "com", "org", "net", "gov", "edu", "ac", "or", "ne", "go", "mil")

    /**
     * Наближення «зареєстрованого домену» без Public Suffix List:
     * останні 2 мітки, або 3 для шаблонів на кшталт example.co.uk / example.com.ua.
     */
    fun registrableDomain(host: String): String {
        if (isIpAddress(host)) return host
        val parts = host.split('.').filter { it.isNotEmpty() }
        if (parts.size <= 2) return host
        val last = parts[parts.size - 1]
        val second = parts[parts.size - 2]
        return if (last.length == 2 && second in secondLevel) {
            parts.takeLast(3).joinToString(".")
        } else {
            parts.takeLast(2).joinToString(".")
        }
    }

    fun isThirdParty(requestHost: String, pageHost: String?): Boolean {
        if (pageHost.isNullOrEmpty()) return false
        return registrableDomain(requestHost) != registrableDomain(pageHost)
    }

    /** Чи схожий введений текст на адресу сайту (а не на пошуковий запит). */
    fun looksLikeUrl(input: String): Boolean {
        val s = input.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return false
        val lower = s.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return true
        val hostPart = lower.substringBefore('/').substringBefore('?').substringBefore('#')
        val hostNoPort = hostPart.substringBefore(':')
        if (hostNoPort == "localhost") return true
        if (isIpAddress(hostNoPort) && !hostNoPort.contains(':')) return true
        if (!hostNoPort.contains('.')) return false
        val tld = hostNoPort.substringAfterLast('.')
        return tld.length >= 2 && tld.all { it.isLetter() || it == '-' } && !hostNoPort.startsWith('.') && !hostNoPort.endsWith('.')
    }

    /** Перетворює текст з адресного рядка на URL або на пошуковий запит вибраної системи. */
    fun resolveInput(input: String, engine: SearchEngine): String {
        val s = input.trim()
        if (s.isEmpty()) return ""
        if (looksLikeUrl(s)) {
            val lower = s.lowercase()
            if (lower.startsWith("http://") || lower.startsWith("https://")) return s
            val host = lower.substringBefore('/').substringBefore(':')
            val plain = host == "localhost" || isIpAddress(host) || host.endsWith(".local")
            return (if (plain) "http://" else "https://") + s
        }
        return engine.template.replace("%s", URLEncoder.encode(s, "UTF-8"))
    }

    /** Коротке відображення URL для UI: без схеми та «www.». */
    fun display(url: String): String {
        if (url.isBlank()) return ""
        var s = url
        s = s.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        return s.trimEnd('/')
    }

    fun displayHost(url: String): String = hostOf(url)?.removePrefix("www.") ?: display(url)
}
