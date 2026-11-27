package com.minibrowser.data

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Дані, передані WebView у DownloadListener. */
data class DownloadRequestInfo(
    val url: String,
    val userAgent: String?,
    val contentDisposition: String?,
    val mimeType: String?,
    val contentLength: Long,
    val referer: String?,
    val cookie: String?,
    val isPrivate: Boolean,
    /** false, якщо завантаження почалося без недавнього дотику користувача («автоматичне»). */
    val userInitiated: Boolean,
    val tabId: String,
) {
    val fileName: String by lazy {
        val guess = URLUtil.guessFileName(url, contentDisposition, mimeType)
        guess.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "download" }
    }
    val host: String get() = UrlUtils.hostOf(url).orEmpty()
}

data class DownloadRecord(
    val id: Long,
    val fileName: String,
    val url: String,
    val mimeType: String,
    val createdAt: Long,
    val isPrivate: Boolean,
)

enum class DownloadStatus { PENDING, RUNNING, PAUSED, SUCCESS, FAILED, MISSING }

data class DownloadItem(
    val record: DownloadRecord,
    val status: DownloadStatus,
    val bytes: Long,
    val total: Long,
)

/**
 * Завантаження через системний DownloadManager (файли потрапляють у «Завантаження»).
 * Обмеження: DownloadManager підтримує лише http(s) — blob:/data: посилання не завантажуються.
 * Записи приватних вкладок зберігаються лише в памʼяті й зникають після закриття приватної сесії.
 */
class DownloadsManager(
    context: Context,
    private val store: JsonFileStore,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
) {
    private val app = context.applicationContext
    private val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val file = "downloads.json"
    private val mutex = Mutex()

    private val _records = MutableStateFlow<List<DownloadRecord>>(emptyList())
    val records: StateFlow<List<DownloadRecord>> = _records.asStateFlow()

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    fun load() {
        val text = store.read(file) ?: return
        try {
            val arr = JSONArray(text)
            val list = ArrayList<DownloadRecord>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list += DownloadRecord(o.getLong("id"), o.getString("name"), o.optString("url"), o.optString("mime"), o.optLong("t"), false)
            }
            _records.value = list.sortedByDescending { it.createdAt }
        } catch (_: Exception) {
        }
    }

    /** Ставить файл у чергу. Повертає помилку для користувача або null, якщо все гаразд. */
    fun enqueue(info: DownloadRequestInfo): String? {
        val scheme = Uri.parse(info.url).scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return "Завантаження з посилань $scheme: не підтримується Android WebView."
        }
        return try {
            val req = DownloadManager.Request(Uri.parse(info.url))
                .setTitle(info.fileName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(!settings.value.downloadWifiOnly)
                .setAllowedOverRoaming(!settings.value.downloadWifiOnly)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, info.fileName)
            if (!info.mimeType.isNullOrBlank()) req.setMimeType(info.mimeType)
            if (!info.cookie.isNullOrBlank()) req.addRequestHeader("Cookie", info.cookie)
            if (!info.userAgent.isNullOrBlank()) req.addRequestHeader("User-Agent", info.userAgent)
            if (!info.referer.isNullOrBlank()) req.addRequestHeader("Referer", info.referer)
            val id = dm.enqueue(req)
            val rec = DownloadRecord(id, info.fileName, info.url, info.mimeType.orEmpty(), System.currentTimeMillis(), info.isPrivate)
            _records.value = listOf(rec) + _records.value
            persist()
            null
        } catch (e: Exception) {
            "Не вдалося почати завантаження: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Оновлює стан завантажень із DownloadManager. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val recs = _records.value
        if (recs.isEmpty()) {
            _items.value = emptyList()
            return@withContext
        }
        val byId = HashMap<Long, DownloadItem>()
        try {
            val q = DownloadManager.Query().setFilterById(*recs.map { it.id }.toLongArray())
            dm.query(q)?.use { c ->
                val iId = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val iStatus = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                val iBytes = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val iTotal = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val rec = recs.firstOrNull { it.id == id } ?: continue
                    val st = when (c.getInt(iStatus)) {
                        DownloadManager.STATUS_PENDING -> DownloadStatus.PENDING
                        DownloadManager.STATUS_RUNNING -> DownloadStatus.RUNNING
                        DownloadManager.STATUS_PAUSED -> DownloadStatus.PAUSED
                        DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.SUCCESS
                        else -> DownloadStatus.FAILED
                    }
                    byId[id] = DownloadItem(rec, st, c.getLong(iBytes), c.getLong(iTotal))
                }
            }
        } catch (_: Exception) {
        }
        _items.value = recs.map { byId[it.id] ?: DownloadItem(it, DownloadStatus.MISSING, 0, 0) }
    }

    /** Відкриває завантажений файл у відповідному застосунку. Повертає текст помилки або null. */
    fun open(item: DownloadItem): String? {
        val uri = dm.getUriForDownloadedFile(item.record.id) ?: return "Файл недоступний."
        val mime = dm.getMimeTypeForDownloadedFile(item.record.id) ?: item.record.mimeType.ifBlank { "*/*" }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            app.startActivity(intent)
            null
        } catch (e: ActivityNotFoundException) {
            "Немає застосунку для відкриття цього типу файлів."
        } catch (e: Exception) {
            "Не вдалося відкрити файл: ${e.message}"
        }
    }

    /** Скасовує/видаляє завантаження. deleteFile=true також видаляє файл з пристрою. */
    fun remove(id: Long, deleteFile: Boolean) {
        try {
            if (deleteFile) dm.remove(id)
        } catch (_: Exception) {
        }
        _records.value = _records.value.filterNot { it.id == id }
        _items.value = _items.value.filterNot { it.record.id == id }
        persist()
    }

    fun clearList() {
        _records.value = emptyList()
        _items.value = emptyList()
        persist()
    }

    /** Забуває записи приватної сесії (файли залишаються на пристрої). */
    fun forgetPrivate() {
        _records.value = _records.value.filterNot { it.isPrivate }
        _items.value = _items.value.filterNot { it.record.isPrivate }
    }

    private fun persist() {
        val snapshot = _records.value.filterNot { it.isPrivate }
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                val arr = JSONArray()
                for (r in snapshot) {
                    arr.put(JSONObject().put("id", r.id).put("name", r.fileName).put("url", r.url).put("mime", r.mimeType).put("t", r.createdAt))
                }
                try {
                    store.write(file, arr.toString())
                } catch (_: Exception) {
                }
            }
        }
    }
}
