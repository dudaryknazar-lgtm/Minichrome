package com.minibrowser.privacy

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

enum class ListFormat { ABP, DOMAINS }

data class FilterListSpec(
    val id: String,
    val title: String,
    val description: String,
    val category: ListCategory,
    val format: ListFormat,
    val assetName: String,
    /** Адреси оновлення; пробуються по черзі. Порожньо — оновлення недоступне. */
    val updateUrls: List<String>,
)

data class FilterListState(
    val spec: FilterListSpec,
    val loaded: Boolean = false,
    val blockRules: Int = 0,
    val allowRules: Int = 0,
    val unsupportedRules: Int = 0,
    /** 0 — використовується вбудований знімок. */
    val lastUpdatedMs: Long = 0L,
    val error: String? = null,
    val updating: Boolean = false,
)

/** Скомпільовані списки в порядку пріоритету категорій. */
class FilterMatcher(val sets: List<Pair<FilterListSpec, RuleSet>>) {
    /** Повертає всі списки, правила яких збіглися із запитом (зазвичай — жодного). */
    fun match(ctx: RequestContext): List<FilterListSpec> {
        var out: ArrayList<FilterListSpec>? = null
        for ((spec, rules) in sets) {
            if (rules.matches(ctx)) {
                if (out == null) out = ArrayList(2)
                out.add(spec)
            }
        }
        return out ?: emptyList()
    }

    fun isMalwareHost(host: String): Boolean {
        val ctx = RequestContext("http://$host/", host, null, false, 0)
        return sets.any { it.first.category == ListCategory.MALWARE && it.second.matches(ctx) }
    }

    companion object {
        val EMPTY = FilterMatcher(emptyList())
    }
}

/**
 * Локальне зберігання та оновлення списків фільтрів (EasyList, EasyPrivacy, шкідливі домени).
 *
 *  - вбудований знімок лежить в assets/filters/, оновлений — у filesDir/filters/;
 *  - оновлення — прямий HTTPS GET без cookie та ідентифікаторів;
 *  - помилки оновлення зберігаються й показуються користувачу.
 */
class FilterListManager(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "filters").also { it.mkdirs() }
    private val prefs = app.getSharedPreferences("minibrowser_filters", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val readyLatch = CountDownLatch(1)

    val specs: List<FilterListSpec> = listOf(
        FilterListSpec(
            id = "malware",
            title = "Шкідливі домени (URLhaus)",
            description = "Відомі домени поширення шкідливого ПЗ. Використовується для попередження про підозрілі домени.",
            category = ListCategory.MALWARE,
            format = ListFormat.DOMAINS,
            assetName = "filters/malware.txt",
            updateUrls = listOf(
                "https://urlhaus.abuse.ch/downloads/hostfile/",
                "https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-online.txt",
            ),
        ),
        FilterListSpec(
            id = "fingerprinting",
            title = "Fingerprinting-скрипти (вбудований)",
            description = "Невеликий власний список відомих сервісів і бібліотек fingerprinting. Оновлюється разом із застосунком.",
            category = ListCategory.FINGERPRINT,
            format = ListFormat.ABP,
            assetName = "filters/fingerprinting.txt",
            updateUrls = emptyList(),
        ),
        FilterListSpec(
            id = "easyprivacy",
            title = "EasyPrivacy",
            description = "Аналітика, трекінгові пікселі та сторонні трекери.",
            category = ListCategory.TRACKER,
            format = ListFormat.ABP,
            assetName = "filters/easyprivacy.txt",
            updateUrls = listOf("https://easylist.to/easylist/easyprivacy.txt"),
        ),
        FilterListSpec(
            id = "easylist",
            title = "EasyList",
            description = "Рекламні домени, скрипти та банери.",
            category = ListCategory.AD,
            format = ListFormat.ABP,
            assetName = "filters/easylist.txt",
            updateUrls = listOf("https://easylist.to/easylist/easylist.txt"),
        ),
    )

    private val _states = MutableStateFlow(specs.map { FilterListState(it) })
    val states: StateFlow<List<FilterListState>> = _states.asStateFlow()

    @Volatile
    var matcher: FilterMatcher = FilterMatcher.EMPTY
        private set

    /** Блокує викликаючий потік (його має бути фоновий!) доки списки не завантажені. */
    fun awaitReady(timeoutMs: Long): Boolean = readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun isReady(): Boolean = readyLatch.count == 0L

    private fun file(spec: FilterListSpec) = File(dir, "${spec.id}.txt")

    /** Завантажує списки: оновлений файл, якщо він є, інакше — вбудований знімок. */
    fun load() {
        scope.launch(Dispatchers.Default) {
            mutex.withLock {
                val compiled = ArrayList<Pair<FilterListSpec, RuleSet>>()
                for (spec in specs) {
                    var error: String? = prefs.getString("${spec.id}_error", null)
                    var set: RuleSet? = null
                    val f = file(spec)
                    if (f.exists()) {
                        try {
                            set = parse(spec) { f.inputStream() }
                        } catch (e: Exception) {
                            error = "Збережений файл пошкоджено: ${e.message}"
                            f.delete()
                        }
                    }
                    if (set == null || set.blockCount == 0) {
                        try {
                            set = parse(spec) { app.assets.open(spec.assetName) }
                        } catch (e: Exception) {
                            error = "Не вдалося прочитати вбудований список: ${e.message}"
                            set = RuleSet()
                        }
                    }
                    val finalSet: RuleSet = set ?: RuleSet()
                    compiled += spec to finalSet
                    publish(spec, finalSet, if (f.exists()) prefs.getLong("${spec.id}_updated", 0L) else 0L, error)
                }
                matcher = FilterMatcher(compiled.sortedBy { priority(it.first.category) })
                readyLatch.countDown()
            }
        }
    }

    private fun priority(c: ListCategory) = when (c) {
        ListCategory.MALWARE -> 0
        ListCategory.FINGERPRINT -> 1
        ListCategory.TRACKER -> 2
        ListCategory.AD -> 3
    }

    private fun parse(spec: FilterListSpec, open: () -> java.io.InputStream): RuleSet {
        val set = RuleSet()
        open().bufferedReader().useLines { lines ->
            when (spec.format) {
                ListFormat.ABP -> FilterParser.parseAbp(lines, set)
                ListFormat.DOMAINS -> FilterParser.parseDomains(lines, set)
            }
        }
        return set
    }

    private fun publish(spec: FilterListSpec, set: RuleSet, updatedMs: Long, error: String?) {
        _states.update { list ->
            list.map {
                if (it.spec.id == spec.id) {
                    it.copy(
                        loaded = true,
                        blockRules = set.blockCount,
                        allowRules = set.allowCount,
                        unsupportedRules = set.unsupported,
                        lastUpdatedMs = updatedMs,
                        error = error,
                        updating = false,
                    )
                } else it
            }
        }
    }

    private fun setUpdating(id: String, updating: Boolean) {
        _states.update { list -> list.map { if (it.spec.id == id) it.copy(updating = updating) else it } }
    }

    /**
     * Завантажує свіжі версії всіх списків, що підтримують оновлення.
     * Повертає кількість успішно оновлених списків; помилки зберігаються у стані кожного списку.
     */
    suspend fun updateAll(): Int = withContext(Dispatchers.IO) {
        var ok = 0
        mutex.withLock {
            val compiled = ArrayList<Pair<FilterListSpec, RuleSet>>()
            val current = matcher.sets.associate { it.first.id to it.second }
            for (spec in specs) {
                var set = current[spec.id] ?: RuleSet()
                if (spec.updateUrls.isEmpty()) {
                    compiled += spec to set
                    continue
                }
                setUpdating(spec.id, true)
                var lastError: String? = null
                var success = false
                for (u in spec.updateUrls) {
                    try {
                        val tmp = File(dir, "${spec.id}.download")
                        download(u, tmp)
                        val parsed = parse(spec) { tmp.inputStream() }
                        if (parsed.blockCount < 50) throw IOException("У файлі замало правил (${parsed.blockCount})")
                        val dest = file(spec)
                        dest.delete()
                        if (!tmp.renameTo(dest)) throw IOException("Не вдалося зберегти файл")
                        val now = System.currentTimeMillis()
                        prefs.edit().putLong("${spec.id}_updated", now).remove("${spec.id}_error").apply()
                        set = parsed
                        success = true
                        ok++
                        publish(spec, parsed, now, null)
                        break
                    } catch (e: Exception) {
                        lastError = "${u.substringAfter("://").substringBefore('/')}: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
                if (!success) {
                    prefs.edit().putString("${spec.id}_error", lastError).apply()
                    _states.update { list ->
                        list.map { if (it.spec.id == spec.id) it.copy(error = lastError, updating = false) else it }
                    }
                }
                compiled += spec to set
            }
            matcher = FilterMatcher(compiled.sortedBy { priority(it.first.category) })
        }
        ok
    }

    /** Повертає вбудовані знімки (видаляє завантажені файли). */
    fun resetToBundled() {
        scope.launch(Dispatchers.IO) {
            for (spec in specs) {
                file(spec).delete()
                prefs.edit().remove("${spec.id}_updated").remove("${spec.id}_error").apply()
            }
            load()
        }
    }

    private fun download(url: String, dest: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 40_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "MiniBrowser-FilterUpdater/1.0")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            var total = 0L
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IOException("Файл завеликий")
                        out.write(buf, 0, n)
                    }
                }
            }
            val head = dest.inputStream().use { s ->
                val b = ByteArray(256)
                val n = s.read(b)
                if (n > 0) String(b, 0, n, Charsets.UTF_8).trimStart().lowercase() else ""
            }
            if (head.startsWith("<!doctype") || head.startsWith("<html")) {
                throw IOException("Сервер повернув HTML замість списку")
            }
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val MAX_BYTES = 30L * 1024 * 1024
    }
}
