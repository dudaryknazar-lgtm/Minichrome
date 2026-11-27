package com.minibrowser.web

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import androidx.core.content.ContextCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.minibrowser.data.DownloadRequestInfo
import com.minibrowser.data.HistoryManager
import com.minibrowser.data.SettingsStore
import com.minibrowser.data.UrlUtils
import com.minibrowser.data.DownloadsManager
import com.minibrowser.privacy.PermissionDecision
import com.minibrowser.privacy.PrivacyEngine
import com.minibrowser.privacy.SitePermissionType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Створює та налаштовує WebView для кожної вкладки, обробляє навігацію, помилки, SSL, дозволи,
 * завантаження та передає мережеві запити в [PrivacyEngine].
 *
 * Безпека: JavascriptInterface не використовується; доступ до файлів/контенту вимкнено;
 * SSL-помилки ніколи не ігноруються автоматично; змішаний вміст — у режимі сумісності
 * (активний змішаний вміст блокується); зовнішні схеми відкриваються лише після жесту користувача.
 */
class BrowserEngine(
    private val appContext: Context,
    private val tabManager: TabManager,
    private val privacy: PrivacyEngine,
    private val history: HistoryManager,
    private val downloads: DownloadsManager,
    private val settings: SettingsStore,
) {
    var host: EngineHost? = null

    private class TabRuntime(val tabId: String, val isPrivate: Boolean, val webView: WebView) {
        @Volatile var pageUrl: String = ""
        @Volatile var lastTouch: Long = -1_000_000L
        var showingError = false
        var errorLoadPending = false
        var errorUrl: String? = null
        var sslHandledUrl: String? = null
        /** Адреса, на яку http було змінено на https, і початкова http-адреса (для запиту при помилці). */
        var upgradedTo: String? = null
        var upgradedFrom: String? = null
        val upgradeAttempts = HashMap<String, Int>()
        /** Хост поточної сторінки (для очищення даних суворого рівня після виходу з сайту). */
        var siteHost: String? = null
    }

    /** Повертає https-варіант http-адреси, якщо потрібно, і веде облік (захист від циклів перенаправлень). */
    private fun tryUpgrade(rt: TabRuntime, url: String): String? {
        val https = privacy.httpsUpgradeFor(url) ?: return null
        val n = (rt.upgradeAttempts[url] ?: 0) + 1
        rt.upgradeAttempts[url] = n
        if (n > 2) {
            // Сайт сам постійно повертає на http — цикл. Дозволяємо http для цього хоста й повідомляємо.
            UrlUtils.hostOf(url)?.let { privacy.allowHttp(it) }
            host?.toast("Сайт постійно перенаправляє на http — відкрито без шифрування")
            return null
        }
        rt.upgradedTo = https
        rt.upgradedFrom = url
        privacy.dashboard.markHttpsUpgrade(rt.tabId)
        return https
    }

    /**
     * Суворий рівень: після виходу з сайту (або закриття вкладки) видаляє його cookie та localStorage,
     * якщо жодна інша вкладка того ж типу не відкрита на цьому сайті.
     */
    private fun cleanupLeftSite(rt: TabRuntime, leftHost: String?, newHost: String?) {
        if (leftHost.isNullOrEmpty()) return
        val key = UrlUtils.registrableDomain(leftHost)
        if (newHost != null && UrlUtils.registrableDomain(newHost) == key) return
        if (privacy.levelFor(leftHost) != com.minibrowser.privacy.ProtectionLevel.STRICT) return
        val stillOpen = tabManager.tabs.value.any {
            it.id != rt.tabId && it.isPrivate == rt.isPrivate && it.host.isNotEmpty() && UrlUtils.registrableDomain(it.host) == key
        }
        if (stillOpen) return
        privacy.clearSiteData(leftHost, rt.isPrivate)
        host?.toast("Суворий режим: дані сайту $key видалено")
    }

    private val runtimes = ConcurrentHashMap<String, TabRuntime>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val promptIds = AtomicLong(1)
    private var activity: Activity? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    /** Хости, для яких користувач свідомо дозволив відкрити сторінку з недійсним сертифікатом (до кінця процесу). */
    private val sslAllowedHosts = ConcurrentHashMap.newKeySet<String>()

    // ----------------------------------------------------------------- життєвий цикл

    fun attachActivity(a: Activity) {
        activity = a
        runtimes.values.forEach { (it.webView.context as? MutableContextWrapper)?.baseContext = a }
    }

    fun detachActivity(a: Activity) {
        if (activity !== a) return
        activity = null
        runtimes.values.forEach { (it.webView.context as? MutableContextWrapper)?.baseContext = appContext }
    }

    fun onPause(activeTabId: String?) {
        activeTabId?.let { runtimes[it] }?.webView?.onPause()
        privacy.cookies.flush()
    }

    fun onResume(activeTabId: String?) {
        activeTabId?.let { runtimes[it] }?.webView?.onResume()
    }

    fun hasWebView(tabId: String) = runtimes.containsKey(tabId)

    fun reapplySettings() {
        runtimes.values.forEach { applyWebSettings(it) }
    }

    // ----------------------------------------------------------------- створення WebView

    /** Повертає WebView вкладки; створює його, якщо потрібно (лише головний потік). */
    fun webViewFor(tab: BrowserTab): WebView {
        runtimes[tab.id]?.let { return it.webView }
        return createWebView(tab).webView
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    @Suppress("DEPRECATION")
    private fun createWebView(tab: BrowserTab): TabRuntime {
        val wv = WebView(MutableContextWrapper(activity ?: appContext))
        val rt = TabRuntime(tab.id, tab.isPrivate, wv)
        runtimes[tab.id] = rt

        // Профіль приватного режиму має бути заданий до будь-якого завантаження.
        if (tab.isPrivate) privacy.privateSession.bind(wv)

        val ws = wv.settings
        ws.domStorageEnabled = true
        ws.allowFileAccess = false
        ws.allowContentAccess = false
        ws.allowFileAccessFromFileURLs = false
        ws.allowUniversalAccessFromFileURLs = false
        ws.saveFormData = false
        ws.javaScriptCanOpenWindowsAutomatically = false
        ws.setSupportMultipleWindows(true)
        ws.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        ws.useWideViewPort = true
        ws.loadWithOverviewMode = true
        ws.setSupportZoom(true)
        ws.builtInZoomControls = true
        ws.displayZoomControls = false
        ws.mediaPlaybackRequiresUserGesture = true
        ws.setGeolocationEnabled(true)

        privacy.fingerprint.install(wv, tab.id, settings.value.fingerprintProtection)
        applyWebSettings(rt)

        wv.webViewClient = Client(rt)
        wv.webChromeClient = Chrome(rt)
        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            val cookie = try {
                privacy.cookies.managerFor(rt.isPrivate).getCookie(url)
            } catch (e: Exception) {
                null
            }
            host?.onDownload(
                DownloadRequestInfo(
                    url = url,
                    userAgent = userAgent,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType,
                    contentLength = contentLength,
                    referer = rt.pageUrl.takeIf { UrlUtils.isHttp(it) },
                    cookie = cookie,
                    isPrivate = rt.isPrivate,
                    userInitiated = SystemClock.uptimeMillis() - rt.lastTouch < 6_000,
                    tabId = rt.tabId,
                ),
            )
        }
        wv.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) rt.lastTouch = SystemClock.uptimeMillis()
            false
        }
        wv.setOnLongClickListener {
            val hit = wv.hitTestResult
            val extra = hit.extra
            val isImage = when (hit.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE -> false
                WebView.HitTestResult.IMAGE_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> true
                else -> return@setOnLongClickListener false
            }
            if (extra != null && UrlUtils.isHttp(extra)) {
                host?.showLinkMenu(LinkMenuRequest(extra, isImage, rt.tabId, rt.isPrivate))
                true
            } else {
                false
            }
        }

        if (tab.url.isNotBlank() && UrlUtils.isHttp(tab.url)) {
            rt.pageUrl = tab.url
            wv.loadUrl(tab.url)
        }
        return rt
    }

    private fun applyWebSettings(rt: TabRuntime) {
        val s = settings.value
        val wv = rt.webView
        val ws = wv.settings
        ws.javaScriptEnabled = s.javascriptEnabled
        ws.cacheMode = if (rt.isPrivate && !privacy.cookies.privateIsIsolated()) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
                WebSettingsCompat.setSafeBrowsingEnabled(ws, s.safeBrowsing)
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(ws, s.forceDarkWeb)
            }
        } catch (_: Exception) {
        }
        privacy.applySettings(wv, rt.isPrivate)
    }

    // ----------------------------------------------------------------- керування навігацією

    fun load(tabId: String, url: String) {
        val rt = runtimes[tabId] ?: return
        val target = tryUpgrade(rt, url) ?: url
        rt.pageUrl = target
        rt.showingError = false
        privacy.applyCookiePolicy(rt.webView, rt.isPrivate, UrlUtils.hostOf(target))
        rt.webView.loadUrl(target)
    }

    /** Повертає true, якщо WebView сам обробив «назад». */
    fun goBack(tabId: String): Boolean {
        val wv = runtimes[tabId]?.webView ?: return false
        return if (wv.canGoBack()) {
            wv.goBack()
            true
        } else {
            false
        }
    }

    fun goForward(tabId: String) {
        runtimes[tabId]?.webView?.takeIf { it.canGoForward() }?.goForward()
    }

    fun reload(tabId: String) {
        val rt = runtimes[tabId] ?: return
        val err = rt.errorUrl
        if (rt.showingError && err != null) {
            rt.showingError = false
            rt.webView.loadUrl(err)
        } else {
            rt.webView.reload()
        }
    }

    fun stop(tabId: String) {
        runtimes[tabId]?.webView?.stopLoading()
        tabManager.update(tabId) { it.copy(isLoading = false) }
    }

    fun currentUrl(tabId: String): String? = runtimes[tabId]?.webView?.url

    /** Знімає мініатюру для картки вкладки (лише в памʼяті). */
    fun captureThumbnail(tabId: String) {
        val wv = runtimes[tabId]?.webView ?: return
        if (wv.width <= 0 || wv.height <= 0 || wv.parent == null) return
        try {
            val scale = 0.32f
            val bmp = Bitmap.createBitmap(
                (wv.width * scale).toInt().coerceAtLeast(1),
                (wv.height * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.RGB_565,
            )
            val canvas = Canvas(bmp)
            canvas.scale(scale, scale)
            wv.draw(canvas)
            tabManager.update(tabId) { it.copy(thumbnail = bmp) }
        } catch (_: Throwable) {
        }
    }

    /** Знищує всі WebView (вихід із застосунку) і пробує видалити приватний профіль. */
    fun shutdown() {
        val all = runtimes.values.toList()
        runtimes.clear()
        all.forEach { release(it) }
        privacy.permissions.clearSession()
        privacy.privateSession.endSession()
    }

    /** Знищує WebView, не закриваючи вкладку (повернення на стартову сторінку, збій рендерера). */
    fun discard(tabId: String) {
        val rt = runtimes.remove(tabId) ?: return
        release(rt)
    }

    /** Знищує WebView закритої вкладки та, за потреби, завершує приватну сесію. */
    fun destroy(tabId: String) {
        val rt = runtimes.remove(tabId)
        if (rt != null) {
            release(rt)
            cleanupLeftSite(rt, rt.siteHost, null)
        }
        privacy.dashboard.forget(tabId)
    }

    private fun release(rt: TabRuntime) {
        try {
            (rt.webView.parent as? ViewGroup)?.removeView(rt.webView)
            rt.webView.stopLoading()
            privacy.fingerprint.forget(rt.webView)
            rt.webView.destroy()
        } catch (_: Exception) {
        }
    }

    /** Викликати після закриття вкладок: якщо приватних більше немає — стирає їхні дані. */
    fun endPrivateSessionIfUnused(attempt: Int = 0) {
        if (tabManager.tabs.value.any { it.isPrivate }) return
        if (runtimes.values.any { it.isPrivate }) return
        privacy.permissions.clearSession()
        downloads.forgetPrivate()
        val ok = privacy.privateSession.endSession()
        if (!ok && attempt < 4) {
            // Профіль ще «зайнятий» знищеним WebView — повторюємо трохи згодом.
            mainHandler.postDelayed({ endPrivateSessionIfUnused(attempt + 1) }, 1_500L)
        }
    }

    // ----------------------------------------------------------------- навігація та внутрішні команди

    private fun handleNavigation(rt: TabRuntime, uri: Uri, gesture: Boolean): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        return when (scheme) {
            "https" -> false
            "http" -> {
                val upgraded = tryUpgrade(rt, uri.toString())
                if (upgraded != null) {
                    mainHandler.post { load(rt.tabId, upgraded) }
                    true
                } else {
                    false
                }
            }
            InternalPages.SCHEME -> {
                handleInternal(rt, uri)
                true
            }
            "about", "blob" -> false
            "javascript", "file", "content", "data" -> true
            else -> {
                if (gesture) openExternal(rt, uri) else host?.toast("Автоматичне відкриття застосунку заблоковано")
                true
            }
        }
    }

    private fun openExternal(rt: TabRuntime, uri: Uri) {
        try {
            val intent: Intent
            var fallback: String? = null
            if (uri.scheme.equals("intent", ignoreCase = true)) {
                intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                fallback = intent.getStringExtra("browser_fallback_url")
                // Безпека: сторінка не може явно вказувати компонент чи селектор.
                intent.component = null
                intent.selector = null
                intent.addCategory(Intent.CATEGORY_BROWSABLE)
            } else {
                intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val started = host?.startExternal(intent) ?: false
            if (!started && fallback != null && UrlUtils.isHttp(fallback)) {
                load(rt.tabId, fallback)
            }
        } catch (e: Exception) {
            host?.toast("Не вдалося відкрити посилання")
        }
    }

    private fun handleInternal(rt: TabRuntime, uri: Uri) {
        val target = uri.getQueryParameter("url")?.takeIf { UrlUtils.isHttp(it) }
        when (uri.host) {
            "back" -> if (!goBack(rt.tabId)) goHome(rt.tabId)
            "retry" -> reload(rt.tabId)
            "proceed" -> if (target != null) {
                UrlUtils.hostOf(target)?.let { privacy.allowSuspicious(it) }
                load(rt.tabId, target)
            }
            "http-proceed" -> if (target != null) {
                UrlUtils.hostOf(target)?.let { privacy.allowHttp(it) }
                load(rt.tabId, target)
            }
            "ssl-proceed" -> if (target != null) {
                UrlUtils.hostOf(target)?.let { sslAllowedHosts.add(it) }
                load(rt.tabId, target)
            }
        }
    }

    /** Повертає вкладку на стартову сторінку. */
    fun goHome(tabId: String) {
        discard(tabId)
        tabManager.update(tabId, persistChange = true) {
            it.copy(url = "", title = "", isLoading = false, progress = 0, canGoBack = false, canGoForward = false, isErrorPage = false)
        }
    }

    private fun showErrorPage(rt: TabRuntime, view: WebView, failingUrl: String, html: String) {
        rt.errorUrl = failingUrl
        rt.errorLoadPending = true
        view.loadDataWithBaseURL(failingUrl, html, "text/html", "UTF-8", failingUrl)
    }

    // ----------------------------------------------------------------- WebViewClient

    private inner class Client(private val rt: TabRuntime) : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val req = request ?: return false
            return handleNavigation(rt, req.url, req.hasGesture())
        }

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val req = request ?: return null
            if (req.isForMainFrame) rt.pageUrl = req.url.toString()
            return try {
                privacy.intercept(rt.tabId, rt.pageUrl, req)
            } catch (e: Exception) {
                null // збій фільтра не повинен ламати завантаження сторінок
            }
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            val u = url ?: return
            if (rt.errorLoadPending) {
                rt.errorLoadPending = false
                rt.showingError = true
            } else {
                rt.showingError = false
                rt.errorUrl = null
            }
            val showing = rt.showingError
            if (UrlUtils.isHttp(u) && !showing) {
                val newHost = UrlUtils.hostOf(u)
                view?.let { privacy.applyCookiePolicy(it, rt.isPrivate, newHost) }
                val left = rt.siteHost
                rt.siteHost = newHost
                cleanupLeftSite(rt, left, newHost)
            }
            tabManager.update(rt.tabId) {
                it.copy(
                    url = if (UrlUtils.isHttp(u)) u else it.url,
                    isLoading = true,
                    progress = maxOf(it.progress, 5),
                    isErrorPage = showing,
                )
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            val wv = view ?: return
            val u = url ?: return
            val showing = rt.showingError
            tabManager.update(rt.tabId) {
                it.copy(
                    isLoading = false,
                    progress = 100,
                    canGoBack = wv.canGoBack(),
                    canGoForward = wv.canGoForward(),
                    isErrorPage = showing,
                )
            }
            if (!rt.isPrivate && !showing && !rt.errorLoadPending && UrlUtils.isHttp(u)) {
                history.record(u, wv.title)
            }
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            val wv = view ?: return
            val u = url ?: return
            tabManager.update(rt.tabId) {
                it.copy(
                    url = if (UrlUtils.isHttp(u)) u else it.url,
                    canGoBack = wv.canGoBack(),
                    canGoForward = wv.canGoForward(),
                )
            }
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            val wv = view ?: return
            val req = request ?: return
            val err = error ?: return
            if (!req.isForMainFrame) return
            val failing = req.url.toString()
            val description = err.description?.toString().orEmpty()
            if (description.contains("ERR_ABORTED")) return
            if (err.errorCode == ERROR_FAILED_SSL_HANDSHAKE && rt.sslHandledUrl == failing) return
            val (title, msg) = when (err.errorCode) {
                ERROR_HOST_LOOKUP -> "Сайт не знайдено" to "Не вдалося знайти сервер. Перевірте адресу та підключення до інтернету."
                ERROR_CONNECT -> "Немає з'єднання" to "Не вдалося підключитися до сервера."
                ERROR_TIMEOUT -> "Час очікування вичерпано" to "Сервер не відповів вчасно."
                ERROR_FAILED_SSL_HANDSHAKE -> "Помилка безпечного з'єднання" to "Не вдалося встановити захищене з'єднання."
                ERROR_BAD_URL -> "Некоректна адреса" to "Адресу сторінки не вдалося розпізнати."
                ERROR_REDIRECT_LOOP -> "Забагато перенаправлень" to "Сторінка постійно перенаправляє сама на себе."
                ERROR_UNSUPPORTED_SCHEME -> "Непідтримувана схема" to "MiniBrowser не може відкрити цей тип посилань."
                ERROR_IO -> "Помилка мережі" to "Під час обміну даними сталася помилка."
                else -> "Не вдалося відкрити сторінку" to "Сталася помилка під час завантаження."
            }
            val shown = description.ifBlank { null }
            // Якщо помилка сталася на автоматично оновленому https-адресі — пропонуємо http, але лише за явним рішенням.
            val httpFallback = if (failing == rt.upgradedTo) rt.upgradedFrom else null
            showErrorPage(rt, wv, failing, InternalPages.errorPage(failing, title, msg, shown ?: err.errorCode.toString(), httpFallback))
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            val h = handler ?: return
            val err = error
            if (err == null) {
                h.cancel()
                return
            }
            val errHost = UrlUtils.hostOf(err.url)
            if (errHost != null && errHost in sslAllowedHosts) {
                // Користувач явно підтвердив виняток для цього хоста (ssl-proceed).
                h.proceed()
                return
            }
            h.cancel() // Ніколи не обходимо помилки сертифіката автоматично.
            val wv = view ?: return
            val url = err.url ?: return
            val pageHost = UrlUtils.hostOf(rt.pageUrl)
            if (errHost != null && errHost == pageHost) {
                rt.sslHandledUrl = url
                val details = when (err.primaryError) {
                    SslError.SSL_EXPIRED -> "Термін дії сертифіката минув."
                    SslError.SSL_NOTYETVALID -> "Сертифікат ще не набув чинності."
                    SslError.SSL_IDMISMATCH -> "Ім'я в сертифікаті не збігається з адресою сайту."
                    SslError.SSL_UNTRUSTED -> "Сертифікат виданий недовіреним центром сертифікації."
                    SslError.SSL_DATE_INVALID -> "Дата сертифіката недійсна."
                    else -> "Сертифікат недійсний."
                }
                showErrorPage(rt, wv, url, InternalPages.sslErrorPage(url, errHost, details))
            } else if (errHost != null) {
                privacy.dashboard.recordSuspicious(rt.tabId, errHost, "Недійсний сертифікат стороннього ресурсу — запит скасовано")
            }
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            discard(rt.tabId)
            tabManager.update(rt.tabId) { it.copy(generation = it.generation + 1, isLoading = false, progress = 0) }
            host?.toast("Вкладку перезавантажено після збою WebView")
            return true
        }
    }

    // ----------------------------------------------------------------- WebChromeClient

    private inner class Chrome(private val rt: TabRuntime) : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            tabManager.update(rt.tabId) { it.copy(progress = newProgress, isLoading = newProgress < 100) }
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            if (title == null || rt.showingError) return
            tabManager.update(rt.tabId) { it.copy(title = title) }
            if (!rt.isPrivate) view?.url?.let { history.updateTitle(it, title) }
        }

        override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
            if (icon != null) tabManager.update(rt.tabId) { it.copy(favicon = icon) }
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (view != null && callback != null) host?.showCustomView(view, callback) else callback?.onCustomViewHidden()
        }

        override fun onHideCustomView() {
            host?.hideCustomView()
        }

        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
            val msg = resultMsg ?: return false
            if (!isUserGesture) {
                host?.toast("Спливаюче вікно заблоковано")
                return false
            }
            val transport = msg.obj as? WebView.WebViewTransport ?: return false
            // Тимчасовий WebView лише перехоплює першу адресу, яку сторінка відкриває у новому вікні.
            val temp = WebView(MutableContextWrapper(appContext))
            if (rt.isPrivate) privacy.privateSession.bind(temp)
            var handled = false
            fun finish(target: Uri?) {
                if (handled) return
                val u = target?.toString()
                if (u == null || !UrlUtils.isHttp(u)) return
                handled = true
                host?.openInNewTab(u, rt.isPrivate, true)
                mainHandler.post {
                    try {
                        temp.destroy()
                    } catch (_: Exception) {
                    }
                }
            }
            temp.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                    finish(request?.url)
                    return true
                }

                override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                    finish(url?.let { Uri.parse(it) })
                }
            }
            mainHandler.postDelayed({
                if (!handled) {
                    handled = true
                    try {
                        temp.destroy()
                    } catch (_: Exception) {
                    }
                }
            }, 15_000L)
            transport.webView = temp
            msg.sendToTarget()
            return true
        }

        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            val r = result ?: return false
            val h = host ?: run { r.cancel(); return true }
            h.showJsDialog(JsDialogRequest(JsDialogKind.ALERT, UrlUtils.hostOf(url).orEmpty(), message.orEmpty(), "") { _, _ -> r.confirm() })
            return true
        }

        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            val r = result ?: return false
            val h = host ?: run { r.cancel(); return true }
            h.showJsDialog(
                JsDialogRequest(JsDialogKind.CONFIRM, UrlUtils.hostOf(url).orEmpty(), message.orEmpty(), "") { ok, _ ->
                    if (ok) r.confirm() else r.cancel()
                },
            )
            return true
        }

        override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
            val r = result ?: return false
            val h = host ?: run { r.cancel(); return true }
            h.showJsDialog(
                JsDialogRequest(JsDialogKind.PROMPT, UrlUtils.hostOf(url).orEmpty(), message.orEmpty(), defaultValue.orEmpty()) { ok, text ->
                    if (ok) r.confirm(text.orEmpty()) else r.cancel()
                },
            )
            return true
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?,
        ): Boolean {
            val cb = filePathCallback ?: return false
            val params = fileChooserParams
            val h = host
            if (params == null || h == null) {
                cb.onReceiveValue(null)
                return true
            }
            val siteHost = UrlUtils.hostOf(rt.pageUrl).orEmpty()
            if (privacy.permissions.get(siteHost, SitePermissionType.FILES, rt.isPrivate) == PermissionDecision.DENY) {
                privacy.dashboard.recordPermission(rt.tabId, SitePermissionType.FILES, PermissionDecision.DENY)
                h.toast("Для цього сайту заборонено вибір файлів")
                cb.onReceiveValue(null)
                return true
            }
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = cb
            val launched = try {
                h.launchFileChooser(params.createIntent()) { code, data ->
                    fileChooserCallback = null
                    cb.onReceiveValue(FileChooserParams.parseResult(code, data))
                }
            } catch (e: Exception) {
                false
            }
            if (!launched) {
                fileChooserCallback = null
                cb.onReceiveValue(null)
                h.toast("Не вдалося відкрити вибір файлів")
            } else {
                privacy.dashboard.recordPermission(rt.tabId, SitePermissionType.FILES, PermissionDecision.ALLOW)
            }
            return true
        }

        // ---- дозволи сайтів (камера / мікрофон)

        private val pendingPrompts = HashMap<PermissionRequest, Long>()

        override fun onPermissionRequest(request: PermissionRequest?) {
            val req = request ?: return
            val siteHost = req.origin?.host.orEmpty()
            val wanted = LinkedHashSet<SitePermissionType>()
            for (res in req.resources) {
                when (res) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> wanted += SitePermissionType.CAMERA
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> wanted += SitePermissionType.MICROPHONE
                }
            }
            if (wanted.isEmpty()) {
                req.deny() // MIDI, захищений вміст тощо — не підтримуються
                return
            }
            val promptId = askSitePermissions(rt, siteHost, wanted.toList()) { allowed ->
                pendingPrompts.remove(req)
                if (allowed.isEmpty()) {
                    req.deny()
                } else {
                    ensureAndroidPermissions(allowed) { usable ->
                        val resources = ArrayList<String>()
                        if (SitePermissionType.CAMERA in usable) resources += PermissionRequest.RESOURCE_VIDEO_CAPTURE
                        if (SitePermissionType.MICROPHONE in usable) resources += PermissionRequest.RESOURCE_AUDIO_CAPTURE
                        if (resources.isEmpty()) req.deny() else req.grant(resources.toTypedArray())
                    }
                }
            }
            if (promptId != null) pendingPrompts[req] = promptId
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest?) {
            val id = pendingPrompts.remove(request ?: return) ?: return
            host?.dismissPermissionPrompt(id)
        }

        private var geoPromptId: Long? = null

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
            val cb = callback ?: return
            val o = origin ?: run { return }
            val siteHost = UrlUtils.hostOf(o).orEmpty()
            geoPromptId = askSitePermissions(rt, siteHost, listOf(SitePermissionType.LOCATION)) { allowed ->
                geoPromptId = null
                if (SitePermissionType.LOCATION in allowed) {
                    ensureAndroidPermissions(allowed) { usable ->
                        // retain=false: WebView сам нічого не запам'ятовує — рішення зберігає PermissionsManager.
                        cb.invoke(o, SitePermissionType.LOCATION in usable, false)
                    }
                } else {
                    cb.invoke(o, false, false)
                }
            }
        }

        override fun onGeolocationPermissionsHidePrompt() {
            geoPromptId?.let { host?.dismissPermissionPrompt(it) }
            geoPromptId = null
        }
    }

    // ----------------------------------------------------------------- дозволи

    /**
     * Застосовує збережені рішення і, якщо потрібно, показує діалог. [onDecision] викликається рівно
     * один раз з набором дозволених типів. Повертає id діалогу (або null, якщо діалог не потрібен).
     */
    private fun askSitePermissions(
        rt: TabRuntime,
        siteHost: String,
        types: List<SitePermissionType>,
        onDecision: (Set<SitePermissionType>) -> Unit,
    ): Long? {
        val allowed = LinkedHashSet<SitePermissionType>()
        val toAsk = ArrayList<SitePermissionType>()
        for (t in types) {
            when (privacy.permissions.get(siteHost, t, rt.isPrivate)) {
                PermissionDecision.ALLOW -> {
                    allowed += t
                    privacy.dashboard.recordPermission(rt.tabId, t, PermissionDecision.ALLOW)
                }
                PermissionDecision.DENY -> privacy.dashboard.recordPermission(rt.tabId, t, PermissionDecision.DENY)
                PermissionDecision.ASK -> toAsk += t
            }
        }
        val h = host
        if (toAsk.isEmpty() || h == null) {
            onDecision(allowed)
            return null
        }
        val id = promptIds.getAndIncrement()
        var done = false
        h.showPermissionPrompt(
            SitePermissionPrompt(
                id = id,
                tabId = rt.tabId,
                host = siteHost,
                types = toAsk,
                isPrivate = rt.isPrivate,
                respond = { picked, remember ->
                    if (!done) {
                        done = true
                        for (t in toAsk) {
                            val dec = if (t in picked) PermissionDecision.ALLOW else PermissionDecision.DENY
                            if (remember) privacy.permissions.set(siteHost, t, dec, rt.isPrivate)
                            privacy.dashboard.recordPermission(rt.tabId, t, dec)
                        }
                        onDecision(allowed + picked)
                    }
                },
                cancel = {
                    if (!done) {
                        done = true
                        for (t in toAsk) privacy.dashboard.recordPermission(rt.tabId, t, PermissionDecision.DENY)
                        onDecision(allowed)
                    }
                },
            ),
        )
        return id
    }

    /** Перевіряє/запитує системні дозволи Android для дозволених типів; повертає ті, що реально доступні. */
    private fun ensureAndroidPermissions(types: Set<SitePermissionType>, done: (Set<SitePermissionType>) -> Unit) {
        fun granted(p: String) = ContextCompat.checkSelfPermission(appContext, p) == PackageManager.PERMISSION_GRANTED
        fun usable(): Set<SitePermissionType> = types.filterTo(LinkedHashSet()) {
            when (it) {
                SitePermissionType.CAMERA -> granted(Manifest.permission.CAMERA)
                SitePermissionType.MICROPHONE -> granted(Manifest.permission.RECORD_AUDIO)
                SitePermissionType.LOCATION ->
                    granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
                else -> true
            }
        }
        val missing = ArrayList<String>()
        for (t in types) when (t) {
            SitePermissionType.CAMERA -> if (!granted(Manifest.permission.CAMERA)) missing += Manifest.permission.CAMERA
            SitePermissionType.MICROPHONE -> if (!granted(Manifest.permission.RECORD_AUDIO)) missing += Manifest.permission.RECORD_AUDIO
            SitePermissionType.LOCATION -> if (usable().none { it == SitePermissionType.LOCATION }) {
                missing += Manifest.permission.ACCESS_FINE_LOCATION
                missing += Manifest.permission.ACCESS_COARSE_LOCATION
            }
            else -> Unit
        }
        val h = host
        if (missing.isEmpty() || h == null) {
            done(usable())
        } else {
            h.requestAndroidPermissions(missing) { done(usable()) }
        }
    }
}
