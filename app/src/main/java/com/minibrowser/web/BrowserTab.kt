package com.minibrowser.web

import android.graphics.Bitmap
import com.minibrowser.data.UrlUtils
import java.util.UUID

/**
 * Вкладка браузера. Перші поля зберігаються на диск (крім приватних вкладок),
 * решта — стан виконання, що відновлюється під час роботи.
 */
data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    /** Порожній URL означає стартову сторінку MiniBrowser. */
    val url: String = "",
    val title: String = "",
    val isPrivate: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),

    // --- стан виконання (не зберігається) ---
    val progress: Int = 0,
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isErrorPage: Boolean = false,
    /** Збільшується, щоб примусово перестворити WebView (після збою процесу рендерингу). */
    val generation: Int = 0,
    val thumbnail: Bitmap? = null,
    val favicon: Bitmap? = null,
) {
    val isHome: Boolean get() = url.isBlank()
    val isSecure: Boolean get() = url.startsWith("https://", ignoreCase = true)
    val host: String get() = UrlUtils.hostOf(url).orEmpty()
    val displayTitle: String
        get() = when {
            title.isNotBlank() -> title
            isHome -> "Нова вкладка"
            else -> UrlUtils.displayHost(url)
        }
}
