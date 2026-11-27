package com.minibrowser.privacy

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Приватний режим.
 *
 * Якщо WebView підтримує кілька профілів (androidx.webkit MULTI_PROFILE, Chrome/WebView 128+),
 * усі приватні вкладки працюють в окремому профілі "mb_private" з власними cookie, localStorage
 * і кешем; після закриття останньої приватної вкладки профіль видаляється разом з даними.
 *
 * Якщо профілі недоступні, приватні вкладки не пишуть історію, не використовують кеш і не
 * зберігають дані форм, АЛЕ cookie ділять зі звичайним перегляданням — застосунок прямо
 * повідомляє про це користувачу ([isolationSupported]).
 */
class PrivateSessionManager {

    val isolationSupported: Boolean
        get() = try {
            WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        } catch (e: Exception) {
            false
        }

    @Volatile
    private var profileInUse = false

    /** Привʼязує новий (ще не завантажений) WebView до приватного профілю. */
    fun bind(webView: WebView): Boolean {
        if (!isolationSupported) return false
        return try {
            WebViewCompat.setProfile(webView, PROFILE_NAME)
            profileInUse = true
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Менеджер cookie приватного профілю або null, якщо ізоляції немає. */
    fun cookieManagerOrNull(): CookieManager? {
        if (!isolationSupported || !profileInUse) return null
        return try {
            ProfileStore.getInstance().getOrCreateProfile(PROFILE_NAME).cookieManager
        } catch (e: Exception) {
            null
        }
    }

    /** Сховище (localStorage, IndexedDB…) приватного профілю або null, якщо ізоляції немає. */
    fun webStorageOrNull(): android.webkit.WebStorage? {
        if (!isolationSupported || !profileInUse) return null
        return try {
            ProfileStore.getInstance().getOrCreateProfile(PROFILE_NAME).webStorage
        } catch (e: Exception) {
            null
        }
    }

    /** Видаляє профіль і всі його дані. Повертає false, якщо профіль ще використовується. */
    fun endSession(): Boolean {
        if (!isolationSupported) return true
        return try {
            ProfileStore.getInstance().deleteProfile(PROFILE_NAME)
            profileInUse = false
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Після аварійного завершення приватна сесія могла лишитися на диску — прибираємо її. */
    fun wipeStaleOnStartup() {
        if (!isolationSupported) return
        try {
            val store = ProfileStore.getInstance()
            if (store.allProfileNames.contains(PROFILE_NAME)) store.deleteProfile(PROFILE_NAME)
        } catch (_: Exception) {
        }
    }

    val notStored: List<String> = listOf(
        "Історія відвідувань (не записується в історію MiniBrowser).",
        "Cookie, localStorage та кеш сайтів — тимчасові; видаляються після закриття останньої приватної вкладки.",
        "Рядки пошуку та адреси з приватних вкладок не потрапляють у підказки.",
        "Приватні вкладки не зберігаються після перезапуску.",
        "Записи про завантаження з приватних вкладок не потрапляють у звичайний список завантажень.",
    )

    val stillVisible: List<String> = listOf(
        "Сайти, які ви відвідуєте, бачать вашу IP-адресу та дані пристрою, що передає браузер.",
        "Інтернет-провайдер, Wi-Fi-мережа, роботодавець чи школа можуть бачити, з якими серверами ви з'єднуєтесь.",
        "Завантажені файли залишаються у папці «Завантаження» на пристрої, поки ви їх не видалите.",
        "Закладки, створені в приватній вкладці, зберігаються — це ваша явна дія.",
        "Дозволи сайтів, надані в приватній вкладці, діють лише до кінця приватної сесії.",
        "Приватний режим не робить вас анонімним у мережі й не замінює VPN чи Tor.",
    )

    companion object {
        const val PROFILE_NAME = "mb_private"
    }
}
