package com.minibrowser

import android.Manifest
import android.app.Application
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.minibrowser.data.BookmarkManager
import com.minibrowser.data.BrowserSettings
import com.minibrowser.data.DownloadRequestInfo
import com.minibrowser.data.DownloadsManager
import com.minibrowser.data.HistoryManager
import com.minibrowser.data.JsonFileStore
import com.minibrowser.data.SettingsStore
import com.minibrowser.data.UrlUtils
import com.minibrowser.privacy.CookieItem
import com.minibrowser.privacy.DataType
import com.minibrowser.privacy.FilterListManager
import com.minibrowser.privacy.FingerprintProtection
import com.minibrowser.privacy.PermissionDecision
import com.minibrowser.privacy.PrivacyDashboard
import com.minibrowser.privacy.PrivacyEngine
import com.minibrowser.privacy.PrivacyManager
import com.minibrowser.privacy.PrivateSessionManager
import com.minibrowser.privacy.SiteCookieManager
import com.minibrowser.privacy.SitePermissionType
import com.minibrowser.privacy.SitePermissionsManager
import com.minibrowser.privacy.TrackingBlocker
import com.minibrowser.web.BrowserEngine
import com.minibrowser.web.BrowserTab
import com.minibrowser.web.EngineHost
import com.minibrowser.web.JsDialogRequest
import com.minibrowser.web.LinkMenuRequest
import com.minibrowser.web.SitePermissionPrompt
import com.minibrowser.web.TabManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** Екрани, що відкриваються поверх браузера. */
enum class Screen { TABS, HISTORY, BOOKMARKS, DOWNLOADS, SETTINGS, PRIVACY, COOKIES, PERMISSIONS, FILTERS, PRIVATE_INFO }

class CustomViewState(val view: View, val callback: WebChromeClient.CustomViewCallback)

/**
 * Єдине джерело стану UI. Створює менеджери (проста ручна DI), зв'язує їх із рушієм WebView та
 * реалізує [EngineHost] — канал, яким рушій просить UI показати діалоги, відкрити вкладку тощо.
 */
class BrowserViewModel(app: Application) : AndroidViewModel(app), EngineHost {

    private val appCtx: Context = app.applicationContext

    // ------------------------------------------------------------ менеджери
    private val store = JsonFileStore(File(appCtx.filesDir, "data"))
    val settings = SettingsStore(appCtx)
    val history = HistoryManager(store, viewModelScope)
    val bookmarks = BookmarkManager(store, viewModelScope)
    val tabManager = TabManager(store, viewModelScope)
    val filterLists = FilterListManager(appCtx, viewModelScope)
    val dashboard = PrivacyDashboard(appCtx)
    val privateSession = PrivateSessionManager()
    val cookies = SiteCookieManager(privateSession)
    val permissions = SitePermissionsManager(store, viewModelScope)
    val downloads = DownloadsManager(appCtx, store, viewModelScope, settings)
    private val blocker = TrackingBlocker(filterLists)
    private val fingerprint = FingerprintProtection(dashboard)
    val privacy = PrivacyEngine(settings, filterLists, blocker, dashboard, fingerprint, cookies, permissions, privateSession)
    val privacyManager = PrivacyManager(appCtx, settings, history, cookies)
    val engine = BrowserEngine(appCtx, tabManager, privacy, history, downloads, settings)

    /** Незалежний від життєвого циклу ViewModel scope — для очищення даних під час виходу. */
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ------------------------------------------------------------ стан UI
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    val tabs: StateFlow<List<BrowserTab>> = tabManager.tabs
    val activeId: StateFlow<String?> = tabManager.activeId
    val activeTab: StateFlow<BrowserTab?> = combine(tabManager.tabs, tabManager.activeId) { list, id ->
        list.firstOrNull { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _stack = MutableStateFlow<List<Screen>>(emptyList())
    val stack: StateFlow<List<Screen>> = _stack.asStateFlow()

    private val _fullscreen = MutableStateFlow(false)
    val fullscreen: StateFlow<Boolean> = _fullscreen.asStateFlow()

    private val _customView = MutableStateFlow<CustomViewState?>(null)
    val customView: StateFlow<CustomViewState?> = _customView.asStateFlow()

    private val _permissionPrompt = MutableStateFlow<SitePermissionPrompt?>(null)
    val permissionPrompt: StateFlow<SitePermissionPrompt?> = _permissionPrompt.asStateFlow()

    private val _jsDialog = MutableStateFlow<JsDialogRequest?>(null)
    val jsDialog: StateFlow<JsDialogRequest?> = _jsDialog.asStateFlow()

    private val _downloadConfirm = MutableStateFlow<DownloadRequestInfo?>(null)
    val downloadConfirm: StateFlow<DownloadRequestInfo?> = _downloadConfirm.asStateFlow()

    private val _linkMenu = MutableStateFlow<LinkMenuRequest?>(null)
    val linkMenu: StateFlow<LinkMenuRequest?> = _linkMenu.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _updatingFilters = MutableStateFlow(false)
    val updatingFilters: StateFlow<Boolean> = _updatingFilters.asStateFlow()

    // ------------------------------------------------------------ зв'язки з Activity (встановлює MainActivity)
    var fileChooserLauncher: ((Intent, (Int, Intent?) -> Unit) -> Boolean)? = null
    var permissionRequester: ((List<String>, (Map<String, Boolean>) -> Unit) -> Unit)? = null
    var externalStarter: ((Intent) -> Boolean)? = null

    init {
        engine.host = this
        // Приватна сесія, що могла залишитись після аварійного завершення, видаляється до створення WebView.
        privateSession.wipeStaleOnStartup()
        history.load()
        bookmarks.load()
        permissions.load()
        downloads.load()
        filterLists.load()

        viewModelScope.launch {
            // Автоочищення виконується ДО відновлення вкладок, щоб не стерти щойно отримані cookie.
            try {
                privacyManager.runAutoCleanup()
            } catch (_: Exception) {
            }
            tabManager.restore()
            _ready.value = true
        }
        viewModelScope.launch {
            settings.state.drop(1).collect { engine.reapplySettings() }
        }
    }

    // ------------------------------------------------------------ навігація по екранах
    fun open(screen: Screen) {
        if (screen == Screen.TABS) captureActiveThumbnail()
        _stack.value = _stack.value + screen
    }

    fun closeScreen(): Boolean {
        if (_stack.value.isEmpty()) return false
        _stack.value = _stack.value.dropLast(1)
        return true
    }

    fun closeAllScreens() {
        _stack.value = emptyList()
    }

    // ------------------------------------------------------------ адресний рядок і навігація
    fun submitAddress(input: String) {
        val tab = tabManager.active ?: return
        val url = UrlUtils.resolveInput(input, settings.value.searchEngine)
        if (url.isNotEmpty()) navigateTab(tab.id, url)
    }

    fun navigate(url: String) {
        val tab = tabManager.active ?: return
        navigateTab(tab.id, url)
    }

    /** Відкриває URL у активній вкладці й закриває всі екрани поверх браузера. */
    fun openUrlHere(url: String) {
        closeAllScreens()
        navigate(url)
    }

    private fun navigateTab(tabId: String, url: String) {
        if (!UrlUtils.isHttp(url)) return
        tabManager.update(tabId, persistChange = true) {
            it.copy(url = url, title = "", isErrorPage = false, isLoading = true, progress = 5)
        }
        engine.load(tabId, url)
    }

    fun goBack(): Boolean {
        val tab = tabManager.active ?: return false
        if (engine.goBack(tab.id)) return true
        if (!tab.isHome) {
            engine.goHome(tab.id)
            return true
        }
        return false
    }

    fun goForward() {
        tabManager.active?.let { engine.goForward(it.id) }
    }

    fun reloadOrStop() {
        val tab = tabManager.active ?: return
        if (tab.isLoading) engine.stop(tab.id) else engine.reload(tab.id)
    }

    fun goHome() {
        val tab = tabManager.active ?: return
        val home = settings.value.homepage
        if (UrlUtils.isHttp(home)) navigateTab(tab.id, home) else engine.goHome(tab.id)
    }

    fun handleIntent(intent: Intent?) {
        intent ?: return
        val url: String? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString?.takeIf { UrlUtils.isHttp(it) }
            Intent.ACTION_WEB_SEARCH -> intent.getStringExtra(SearchManager.QUERY)
                ?.let { UrlUtils.resolveInput(it, settings.value.searchEngine) }
            else -> null
        }
        if (url != null) {
            viewModelScope.launch {
                ready.first { it }
                closeAllScreens()
                val a = tabManager.active
                if (a != null && a.isHome && !a.isPrivate) navigate(url) else newTab(url)
            }
        }
    }

    // ------------------------------------------------------------ вкладки
    fun captureActiveThumbnail() {
        tabManager.active?.let { engine.captureThumbnail(it.id) }
    }

    fun newTab(url: String = "", isPrivate: Boolean? = null) {
        captureActiveThumbnail()
        tabManager.newTab(url = url, isPrivate = isPrivate ?: settings.value.privateByDefault)
        closeAllScreens()
    }

    fun selectTab(id: String) {
        captureActiveThumbnail()
        tabManager.select(id)
        closeAllScreens()
    }

    fun closeTab(id: String) {
        val closed = tabManager.close(id) ?: return
        engine.destroy(id)
        if (closed.isPrivate) engine.endPrivateSessionIfUnused()
    }

    fun closeAllTabs(isPrivate: Boolean) {
        val closed = tabManager.closeAll(isPrivate)
        closed.forEach { engine.destroy(it.id) }
        if (isPrivate) engine.endPrivateSessionIfUnused()
    }

    // ------------------------------------------------------------ закладки / поширення
    fun toggleBookmark() {
        val tab = tabManager.active ?: return
        if (!UrlUtils.isHttp(tab.url)) {
            toast("Цю сторінку не можна додати в закладки")
            return
        }
        val added = bookmarks.toggle(tab.url, tab.title)
        toast(if (added) "Додано в закладки" else "Закладку видалено")
    }

    fun shareUrl(url: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
        startExternal(Intent.createChooser(send, "Поділитися").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun copyToClipboard(text: String) {
        val cm = appCtx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("MiniBrowser", text))
        if (Build.VERSION.SDK_INT < 33) toast("Скопійовано")
    }

    fun toggleFullscreen() {
        _fullscreen.value = !_fullscreen.value
    }

    fun exitFullscreen() {
        _fullscreen.value = false
    }

    // ------------------------------------------------------------ життєвий цикл Activity
    fun onActivityPause() = engine.onPause(tabManager.activeId.value)
    fun onActivityResume() = engine.onResume(tabManager.activeId.value)

    fun onActivityDestroy(a: android.app.Activity, finishing: Boolean) {
        engine.detachActivity(a)
        if (finishing) {
            engine.shutdown()
            if (settings.value.autoCleanup) processScope.launch { privacyManager.runAutoCleanup() }
        }
    }

    override fun onCleared() {
        engine.shutdown()
        super.onCleared()
    }

    // ------------------------------------------------------------ налаштування / приватність
    fun updateSettings(block: (BrowserSettings) -> BrowserSettings) = settings.update(block)

    fun clearData(types: Set<DataType>) {
        viewModelScope.launch {
            privacyManager.clear(types)
            toast("Дані очищено")
        }
    }

    fun readCookies(): Pair<String?, List<CookieItem>> {
        val tab = tabManager.active ?: return null to emptyList()
        if (!UrlUtils.isHttp(tab.url)) return null to emptyList()
        return tab.url to cookies.cookiesFor(tab.url, tab.isPrivate)
    }

    fun deleteCookie(name: String) {
        val tab = tabManager.active ?: return
        cookies.deleteCookie(tab.url, name, tab.isPrivate)
    }

    fun clearSiteCookies() {
        val tab = tabManager.active ?: return
        val n = cookies.clearSite(tab.url, tab.isPrivate)
        toast("Видалено cookie: $n")
    }

    fun clearAllCookies() {
        viewModelScope.launch {
            privacyManager.clear(setOf(DataType.COOKIES))
            toast("Усі cookie видалено")
        }
    }

    fun updateFilterLists() {
        if (_updatingFilters.value) return
        _updatingFilters.value = true
        viewModelScope.launch {
            val ok = try {
                filterLists.updateAll()
            } catch (e: Exception) {
                0
            }
            _updatingFilters.value = false
            val total = filterLists.specs.count { it.updateUrls.isNotEmpty() }
            toast(if (ok == total) "Списки оновлено" else "Оновлено списків: $ok з $total. Деталі — у розділі «Списки фільтрів»")
        }
    }

    fun setSiteProtection(enabled: Boolean) {
        val tab = tabManager.active ?: return
        val host = tab.host.ifEmpty { return }
        privacy.setSiteProtection(host, enabled)
        engine.reload(tab.id)
    }

    fun isSiteProtectionOff(): Boolean = privacy.isProtectionOffFor(tabManager.active?.host)

    // ------------------------------------------------------------ діалоги: дозволи, JS, завантаження
    fun answerPermission(allowed: Set<SitePermissionType>, remember: Boolean) {
        val p = _permissionPrompt.value ?: return
        _permissionPrompt.value = null
        p.respond(allowed, remember)
    }

    fun dismissPermission() {
        val p = _permissionPrompt.value ?: return
        _permissionPrompt.value = null
        p.cancel()
    }

    fun answerJsDialog(confirmed: Boolean, text: String?) {
        val d = _jsDialog.value ?: return
        _jsDialog.value = null
        d.respond(confirmed, text)
    }

    fun confirmDownload(start: Boolean) {
        val info = _downloadConfirm.value ?: return
        _downloadConfirm.value = null
        if (start) startDownload(info)
    }

    private fun startDownload(info: DownloadRequestInfo) {
        val needsStorage = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(appCtx, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needsStorage) {
            requestAndroidPermissions(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)) { res ->
                if (res.values.all { it }) enqueueNow(info) else toast("Без дозволу на сховище файл не можна зберегти")
            }
        } else {
            enqueueNow(info)
        }
    }

    private fun enqueueNow(info: DownloadRequestInfo) {
        val err = downloads.enqueue(info)
        toast(err ?: "Завантаження розпочато: ${info.fileName}")
    }

    fun linkAction(request: LinkMenuRequest, action: LinkAction) {
        _linkMenu.value = null
        when (action) {
            LinkAction.OPEN_NEW_TAB -> tabManager.newTab(request.url, request.isPrivate, activate = false).also { toast("Відкрито у новій вкладці") }
            LinkAction.OPEN_PRIVATE -> tabManager.newTab(request.url, isPrivate = true, activate = false).also { toast("Відкрито у приватній вкладці") }
            LinkAction.COPY -> copyToClipboard(request.url)
            LinkAction.SHARE -> shareUrl(request.url)
            LinkAction.DOWNLOAD_IMAGE -> {
                val cookie = try {
                    cookies.managerFor(request.isPrivate).getCookie(request.url)
                } catch (e: Exception) {
                    null
                }
                onDownload(
                    DownloadRequestInfo(
                        url = request.url, userAgent = null, contentDisposition = null, mimeType = null, contentLength = -1,
                        referer = tabManager.get(request.tabId)?.url, cookie = cookie, isPrivate = request.isPrivate,
                        userInitiated = true, tabId = request.tabId,
                    ),
                )
            }
        }
    }

    fun dismissLinkMenu() {
        _linkMenu.value = null
    }

    // ------------------------------------------------------------ EngineHost
    override fun openInNewTab(url: String, isPrivate: Boolean, activate: Boolean) {
        if (activate) captureActiveThumbnail()
        tabManager.newTab(url = url, isPrivate = isPrivate, activate = activate)
    }

    override fun showPermissionPrompt(prompt: SitePermissionPrompt) {
        _permissionPrompt.value?.cancel?.invoke()
        _permissionPrompt.value = prompt
    }

    override fun dismissPermissionPrompt(id: Long) {
        if (_permissionPrompt.value?.id == id) _permissionPrompt.value = null
    }

    override fun showJsDialog(request: JsDialogRequest) {
        _jsDialog.value?.respond?.invoke(false, null)
        _jsDialog.value = request
    }

    override fun launchFileChooser(intent: Intent, onResult: (Int, Intent?) -> Unit): Boolean =
        fileChooserLauncher?.invoke(intent, onResult) ?: false

    override fun requestAndroidPermissions(permissions: List<String>, onResult: (Map<String, Boolean>) -> Unit) {
        val r = permissionRequester
        if (r == null) onResult(permissions.associateWith { false }) else r(permissions, onResult)
    }

    override fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        _customView.value?.callback?.onCustomViewHidden()
        _customView.value = CustomViewState(view, callback)
    }

    override fun hideCustomView() {
        val cv = _customView.value ?: return
        _customView.value = null
        cv.callback.onCustomViewHidden()
    }

    override fun startExternal(intent: Intent): Boolean {
        val s = externalStarter
        val ok = if (s != null) s(intent) else try {
            appCtx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            false
        }
        if (!ok) toast("Немає застосунку для відкриття цього посилання")
        return ok
    }

    override fun onDownload(info: DownloadRequestInfo) {
        val s = settings.value
        val pageHost = UrlUtils.hostOf(info.referer) ?: info.host
        fun proceed() {
            if (s.askBeforeDownload) _downloadConfirm.value = info else startDownload(info)
        }
        if (info.userInitiated) {
            proceed()
            return
        }
        when (permissions.get(pageHost, SitePermissionType.AUTO_DOWNLOADS, info.isPrivate)) {
            PermissionDecision.ALLOW -> {
                dashboard.recordPermission(info.tabId, SitePermissionType.AUTO_DOWNLOADS, PermissionDecision.ALLOW)
                proceed()
            }
            PermissionDecision.DENY -> {
                dashboard.recordPermission(info.tabId, SitePermissionType.AUTO_DOWNLOADS, PermissionDecision.DENY)
                toast("Автоматичне завантаження заблоковано: ${info.fileName}")
            }
            PermissionDecision.ASK -> {
                var done = false
                showPermissionPrompt(
                    SitePermissionPrompt(
                        id = System.nanoTime(),
                        tabId = info.tabId,
                        host = pageHost,
                        types = listOf(SitePermissionType.AUTO_DOWNLOADS),
                        isPrivate = info.isPrivate,
                        respond = { allowed, remember ->
                            if (!done) {
                                done = true
                                val ok = SitePermissionType.AUTO_DOWNLOADS in allowed
                                val dec = if (ok) PermissionDecision.ALLOW else PermissionDecision.DENY
                                if (remember) permissions.set(pageHost, SitePermissionType.AUTO_DOWNLOADS, dec, info.isPrivate)
                                dashboard.recordPermission(info.tabId, SitePermissionType.AUTO_DOWNLOADS, dec)
                                if (ok) proceed()
                            }
                        },
                        cancel = { done = true },
                    ),
                )
            }
        }
    }

    override fun showLinkMenu(request: LinkMenuRequest) {
        _linkMenu.value = request
    }

    override fun toast(message: String) {
        _messages.tryEmit(message)
    }

    fun webViewFor(tab: BrowserTab): WebView = engine.webViewFor(tab)

    val fingerprintObserverSupported: Boolean get() = fingerprint.isSupported
}

enum class LinkAction { OPEN_NEW_TAB, OPEN_PRIVATE, COPY, SHARE, DOWNLOAD_IMAGE }
