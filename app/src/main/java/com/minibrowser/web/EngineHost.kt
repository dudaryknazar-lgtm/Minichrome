package com.minibrowser.web

import android.content.Intent
import android.view.View
import android.webkit.WebChromeClient
import com.minibrowser.data.DownloadRequestInfo
import com.minibrowser.privacy.SitePermissionType

/** Запит сайту на дозволи; UI показує діалог і викликає [respond] або [cancel]. */
class SitePermissionPrompt(
    val id: Long,
    val tabId: String,
    val host: String,
    val types: List<SitePermissionType>,
    val isPrivate: Boolean,
    /** Якщо true, це запит на «автоматичне завантаження» і текст діалогу відрізняється. */
    val respond: (allowed: Set<SitePermissionType>, remember: Boolean) -> Unit,
    val cancel: () -> Unit,
)

enum class JsDialogKind { ALERT, CONFIRM, PROMPT }

class JsDialogRequest(
    val kind: JsDialogKind,
    val host: String,
    val message: String,
    val defaultValue: String,
    val respond: (confirmed: Boolean, text: String?) -> Unit,
)

class LinkMenuRequest(
    val url: String,
    val isImage: Boolean,
    val tabId: String,
    val isPrivate: Boolean,
)

/** Зв'язок рушія з UI-шаром (реалізується BrowserViewModel). Усі виклики — на головному потоці. */
interface EngineHost {
    fun openInNewTab(url: String, isPrivate: Boolean, activate: Boolean)
    fun showPermissionPrompt(prompt: SitePermissionPrompt)
    fun dismissPermissionPrompt(id: Long)
    fun showJsDialog(request: JsDialogRequest)
    fun launchFileChooser(intent: Intent, onResult: (resultCode: Int, data: Intent?) -> Unit): Boolean
    fun requestAndroidPermissions(permissions: List<String>, onResult: (Map<String, Boolean>) -> Unit)
    fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback)
    fun hideCustomView()
    fun startExternal(intent: Intent): Boolean
    fun onDownload(info: DownloadRequestInfo)
    fun showLinkMenu(request: LinkMenuRequest)
    fun toast(message: String)
}
