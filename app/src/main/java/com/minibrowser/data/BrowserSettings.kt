package com.minibrowser.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BrowserSettings(
    // Вигляд / пошук
    val theme: ThemeMode = ThemeMode.DARK,
    val searchEngineId: String = "google",
    /** Порожній рядок = власна стартова сторінка MiniBrowser. */
    val homepage: String = "",
    val javascriptEnabled: Boolean = true,
    val forceDarkWeb: Boolean = false,

    // Privacy Engine (розділ 12.7)
    val trackingProtection: Boolean = true,
    val adBlocking: Boolean = true,
    val thirdPartyCookieProtection: Boolean = true,
    val fingerprintProtection: Boolean = true,
    /** «Private Browsing»: нові вкладки за замовчуванням відкриваються приватними. */
    val privateByDefault: Boolean = false,
    val autoCleanup: Boolean = false,
    val suspiciousDomainWarning: Boolean = true,

    // Додаткові
    val safeBrowsing: Boolean = true,
    val blockScreenshotsInPrivate: Boolean = true,
    val cleanupCookies: Boolean = true,
    val cleanupCache: Boolean = true,
    val cleanupHistory: Boolean = false,
    /** Сайти, для яких автоочищення cookie не виконується. */
    val cleanupKeepHosts: Set<String> = emptySet(),
    /** Сайти, для яких захист вимкнено (сумісність). Ключ — зареєстрований домен. */
    val protectionOffSites: Set<String> = emptySet(),
    /** Сайти з явно заданим суворим / стандартним рівнем захисту (ключ — зареєстрований домен). */
    val strictSites: Set<String> = emptySet(),
    val standardSites: Set<String> = emptySet(),
    /** Рівень за замовчуванням для сайтів без власного налаштування: true — «Суворий». */
    val strictByDefault: Boolean = false,
    /** Автоматично відкривати http-адреси через https (при помилці — запит користувачу). */
    val httpsUpgrade: Boolean = true,

    // Завантаження
    val askBeforeDownload: Boolean = true,
    val downloadWifiOnly: Boolean = false,
) {
    val searchEngine: SearchEngine get() = SearchEngines.byId(searchEngineId)
}

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("minibrowser_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<BrowserSettings> = _state.asStateFlow()
    val value: BrowserSettings get() = _state.value

    private fun load(): BrowserSettings {
        val d = BrowserSettings()
        return BrowserSettings(
            theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", d.theme.name)!!) }.getOrDefault(d.theme),
            searchEngineId = prefs.getString("searchEngine", d.searchEngineId) ?: d.searchEngineId,
            homepage = prefs.getString("homepage", d.homepage) ?: "",
            javascriptEnabled = prefs.getBoolean("js", d.javascriptEnabled),
            forceDarkWeb = prefs.getBoolean("forceDarkWeb", d.forceDarkWeb),
            trackingProtection = prefs.getBoolean("trackingProtection", d.trackingProtection),
            adBlocking = prefs.getBoolean("adBlocking", d.adBlocking),
            thirdPartyCookieProtection = prefs.getBoolean("thirdPartyCookies", d.thirdPartyCookieProtection),
            fingerprintProtection = prefs.getBoolean("fingerprint", d.fingerprintProtection),
            privateByDefault = prefs.getBoolean("privateByDefault", d.privateByDefault),
            autoCleanup = prefs.getBoolean("autoCleanup", d.autoCleanup),
            suspiciousDomainWarning = prefs.getBoolean("suspiciousWarning", d.suspiciousDomainWarning),
            safeBrowsing = prefs.getBoolean("safeBrowsing", d.safeBrowsing),
            blockScreenshotsInPrivate = prefs.getBoolean("blockScreenshots", d.blockScreenshotsInPrivate),
            cleanupCookies = prefs.getBoolean("cleanupCookies", d.cleanupCookies),
            cleanupCache = prefs.getBoolean("cleanupCache", d.cleanupCache),
            cleanupHistory = prefs.getBoolean("cleanupHistory", d.cleanupHistory),
            cleanupKeepHosts = prefs.getStringSet("cleanupKeepHosts", emptySet())?.toSet() ?: emptySet(),
            protectionOffSites = prefs.getStringSet("protectionOffSites", emptySet())?.toSet() ?: emptySet(),
            strictSites = prefs.getStringSet("strictSites", emptySet())?.toSet() ?: emptySet(),
            standardSites = prefs.getStringSet("standardSites", emptySet())?.toSet() ?: emptySet(),
            strictByDefault = prefs.getBoolean("strictByDefault", d.strictByDefault),
            httpsUpgrade = prefs.getBoolean("httpsUpgrade", d.httpsUpgrade),
            askBeforeDownload = prefs.getBoolean("askBeforeDownload", d.askBeforeDownload),
            downloadWifiOnly = prefs.getBoolean("downloadWifiOnly", d.downloadWifiOnly),
        )
    }

    @Synchronized
    fun update(block: (BrowserSettings) -> BrowserSettings) {
        val s = block(_state.value)
        _state.value = s
        prefs.edit()
            .putString("theme", s.theme.name)
            .putString("searchEngine", s.searchEngineId)
            .putString("homepage", s.homepage)
            .putBoolean("js", s.javascriptEnabled)
            .putBoolean("forceDarkWeb", s.forceDarkWeb)
            .putBoolean("trackingProtection", s.trackingProtection)
            .putBoolean("adBlocking", s.adBlocking)
            .putBoolean("thirdPartyCookies", s.thirdPartyCookieProtection)
            .putBoolean("fingerprint", s.fingerprintProtection)
            .putBoolean("privateByDefault", s.privateByDefault)
            .putBoolean("autoCleanup", s.autoCleanup)
            .putBoolean("suspiciousWarning", s.suspiciousDomainWarning)
            .putBoolean("safeBrowsing", s.safeBrowsing)
            .putBoolean("blockScreenshots", s.blockScreenshotsInPrivate)
            .putBoolean("cleanupCookies", s.cleanupCookies)
            .putBoolean("cleanupCache", s.cleanupCache)
            .putBoolean("cleanupHistory", s.cleanupHistory)
            .putStringSet("cleanupKeepHosts", s.cleanupKeepHosts)
            .putStringSet("protectionOffSites", s.protectionOffSites)
            .putStringSet("strictSites", s.strictSites)
            .putStringSet("standardSites", s.standardSites)
            .putBoolean("strictByDefault", s.strictByDefault)
            .putBoolean("httpsUpgrade", s.httpsUpgrade)
            .putBoolean("askBeforeDownload", s.askBeforeDownload)
            .putBoolean("downloadWifiOnly", s.downloadWifiOnly)
            .apply()
    }
}
