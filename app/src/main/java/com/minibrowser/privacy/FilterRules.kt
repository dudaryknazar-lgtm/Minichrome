package com.minibrowser.privacy

import com.minibrowser.data.UrlUtils

/** Категорія списку фільтрів — визначає, який перемикач у налаштуваннях керує блокуванням. */
enum class ListCategory { AD, TRACKER, MALWARE, FINGERPRINT }

/** Типи ресурсів (бітова маска). 0 у RequestContext означає «тип невідомий». */
object ResourceType {
    const val SCRIPT = 1
    const val IMAGE = 2
    const val STYLE = 4
    const val XHR = 8
    const val SUBDOC = 16
    const val OBJECT = 32
    const val MEDIA = 64
    const val FONT = 128
    const val PING = 256
    const val WEBSOCKET = 512
    const val OTHER = 1024
    const val ALL = 2047
}

/** Описує один мережевий запит для зіставлення з правилами. Усі рядки — у нижньому регістрі. */
class RequestContext(
    val url: String,
    val host: String,
    val pageHost: String?,
    val thirdParty: Boolean,
    val type: Int,
) {
    /** Індекс у url одразу після імені хоста (для правил `||domain...`). */
    val hostEnd: Int = run {
        val schemeEnd = url.indexOf("://").let { if (it < 0) 0 else it + 3 }
        val idx = url.indexOf(host, schemeEnd)
        if (idx < 0) schemeEnd + host.length else idx + host.length
    }
}

/** Одне правило ABP, яке не зводиться до простого домену. */
class Rule(
    val pattern: String,
    val startAnchor: Boolean,
    val endAnchor: Boolean,
    /** true: шаблон порівнюється з позиції одразу після хоста (`||host<pattern>`). */
    val hostAnchored: Boolean,
    /** 0 — будь-які, 1 — лише сторонні, 2 — лише першосторонні. */
    val party: Int,
    val typeMask: Int,
    val includeDomains: Array<String>?,
    val excludeDomains: Array<String>?,
) {
    fun optionsMatch(ctx: RequestContext): Boolean {
        if (party == 1 && !ctx.thirdParty) return false
        if (party == 2 && ctx.thirdParty) return false
        if (typeMask != ResourceType.ALL && ctx.type != 0 && (typeMask and ctx.type) == 0) return false
        val ph = ctx.pageHost
        if (excludeDomains != null && ph != null && excludeDomains.any { domainMatches(ph, it) }) return false
        if (includeDomains != null) {
            if (ph == null) return false
            if (includeDomains.none { domainMatches(ph, it) }) return false
        }
        return true
    }

    private fun domainMatches(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")

    fun patternMatches(ctx: RequestContext): Boolean {
        val url = ctx.url
        if (hostAnchored) return matchAt(pattern, 0, url, ctx.hostEnd, endAnchor)
        if (startAnchor) return matchAt(pattern, 0, url, 0, endAnchor)
        val first = pattern.firstOrNull()
        var i = 0
        while (i <= url.length) {
            if (first != null && first != '*' && first != '^') {
                i = url.indexOf(first, i)
                if (i < 0) return false
            }
            if (matchAt(pattern, 0, url, i, endAnchor)) return true
            i++
        }
        return false
    }

    companion object {
        fun isSeparator(c: Char): Boolean =
            !(c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' || c == '%')

        /** Порівняння ABP-шаблону (`*` — будь-що, `^` — роздільник або кінець) з позиції ui0. */
        fun matchAt(p: String, pi0: Int, u: String, ui0: Int, endAnchor: Boolean): Boolean {
            var pi = pi0
            var ui = ui0
            while (pi < p.length) {
                val c = p[pi]
                when (c) {
                    '*' -> {
                        while (pi < p.length && p[pi] == '*') pi++
                        if (pi == p.length) return true
                        var k = ui
                        while (k <= u.length) {
                            if (matchAt(p, pi, u, k, endAnchor)) return true
                            k++
                        }
                        return false
                    }
                    '^' -> {
                        if (ui == u.length) {
                            pi++
                        } else if (isSeparator(u[ui])) {
                            ui++
                            pi++
                        } else {
                            return false
                        }
                    }
                    else -> {
                        if (ui < u.length && u[ui] == c) {
                            ui++
                            pi++
                        } else {
                            return false
                        }
                    }
                }
            }
            return !endAnchor || ui == u.length
        }
    }
}

/** Індекс правил: прості домени (хеш), правила з прив'язкою до домену, правила за токеном та загальні. */
class RuleIndex {
    /** host -> party (0/1/2) для правил виду `||host^` та рядків hosts-файлів. */
    val simple = HashMap<String, Int>()
    val domainRules = HashMap<String, ArrayList<Rule>>()
    val tokenRules = HashMap<String, ArrayList<Rule>>()
    val generic = ArrayList<Rule>()

    fun addSimple(host: String, party: Int) {
        val prev = simple[host]
        simple[host] = if (prev == null || prev == party) party else 0
    }

    fun addRule(rule: Rule, hostKey: String?) {
        if (hostKey != null) {
            domainRules.getOrPut(hostKey) { ArrayList(2) }.add(rule)
            return
        }
        val token = bestToken(rule)
        if (token != null) tokenRules.getOrPut(token) { ArrayList(2) }.add(rule) else generic.add(rule)
    }

    private fun isTokenChar(c: Char) = (c in 'a'..'z') || (c in '0'..'9') || c == '%'

    private val weakTokens = setOf("http", "https", "www", "com")

    private fun bestToken(rule: Rule): String? {
        val p = rule.pattern
        var best: String? = null
        var i = 0
        while (i < p.length) {
            if (isTokenChar(p[i])) {
                var j = i
                while (j < p.length && isTokenChar(p[j])) j++
                val leftOk = if (i == 0) rule.startAnchor else p[i - 1] != '*'
                val rightOk = if (j == p.length) rule.endAnchor else p[j] != '*'
                val len = j - i
                if (leftOk && rightOk && len >= 3 && p.substring(i, j) !in weakTokens && (best == null || len > best.length)) {
                    best = p.substring(i, j)
                }
                i = j
            } else {
                i++
            }
        }
        return best
    }

    fun matches(ctx: RequestContext): Boolean {
        var h = ctx.host
        val ip = UrlUtils.isIpAddress(h)
        while (true) {
            val party = simple[h]
            if (party != null && !(party == 1 && !ctx.thirdParty) && !(party == 2 && ctx.thirdParty)) return true
            val rules = domainRules[h]
            if (rules != null) {
                for (r in rules) if (r.optionsMatch(ctx) && r.patternMatches(ctx)) return true
            }
            if (ip) break
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
            if (!h.contains('.')) break
        }
        if (tokenRules.isNotEmpty()) {
            val url = ctx.url
            var i = 0
            val n = url.length
            while (i < n) {
                if (isTokenChar(url[i])) {
                    var j = i
                    while (j < n && isTokenChar(url[j])) j++
                    if (j - i >= 3) {
                        val list = tokenRules[url.substring(i, j)]
                        if (list != null) {
                            for (r in list) if (r.optionsMatch(ctx) && r.patternMatches(ctx)) return true
                        }
                    }
                    i = j
                } else {
                    i++
                }
            }
        }
        for (r in generic) if (r.optionsMatch(ctx) && r.patternMatches(ctx)) return true
        return false
    }
}

/** Скомпільовані правила одного списку: блокуючі та винятки (`@@`). */
class RuleSet {
    val block = RuleIndex()
    val allow = RuleIndex()
    var blockCount = 0
    var allowCount = 0
    var unsupported = 0

    fun matches(ctx: RequestContext): Boolean = block.matches(ctx) && !allow.matches(ctx)
}

object FilterParser {

    private val typeNames = mapOf(
        "script" to ResourceType.SCRIPT,
        "image" to ResourceType.IMAGE,
        "stylesheet" to ResourceType.STYLE,
        "css" to ResourceType.STYLE,
        "xmlhttprequest" to ResourceType.XHR,
        "xhr" to ResourceType.XHR,
        "subdocument" to ResourceType.SUBDOC,
        "frame" to ResourceType.SUBDOC,
        "object" to ResourceType.OBJECT,
        "object-subrequest" to ResourceType.OBJECT,
        "media" to ResourceType.MEDIA,
        "font" to ResourceType.FONT,
        "ping" to ResourceType.PING,
        "websocket" to ResourceType.WEBSOCKET,
        "other" to ResourceType.OTHER,
    )
    private val ignoredOptions = setOf("important", "match-case", "all", "third-party", "3p", "first-party", "1p")

    fun parseAbp(lines: Sequence<String>, set: RuleSet) {
        for (raw in lines) parseAbpLine(raw, set)
    }

    /** Формат hosts / простий список доменів (рядки зі схемою ABP також приймаються). */
    fun parseDomains(lines: Sequence<String>, set: RuleSet) {
        for (raw in lines) {
            var line = raw.trim()
            if (line.isEmpty() || line[0] == '!' || line[0] == '#') continue
            if (line.startsWith("||") || line.startsWith("@@")) {
                parseAbpLine(line, set)
                continue
            }
            val hash = line.indexOf('#')
            if (hash > 0) line = line.substring(0, hash).trim()
            val tokens = line.split(' ', '\t').filter { it.isNotEmpty() }
            if (tokens.isEmpty()) continue
            var host = if (tokens.size >= 2 && UrlUtils.isIpAddress(tokens[0])) tokens[1] else tokens[0]
            host = host.lowercase().trimEnd('.')
            if (host == "localhost" || host == "0.0.0.0" || host.isEmpty()) continue
            if (!host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' || it == ':' }) continue
            set.block.addSimple(host, 0)
            set.blockCount++
        }
    }

    private fun looksLikeOptions(line: String, dollar: Int): Boolean {
        if (dollar >= line.length - 1) return false
        val opt = line.substring(dollar + 1)
        val first = opt.substringBefore(',').substringBefore('=').removePrefix("~")
        return first.isNotEmpty() && first.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }

    fun parseAbpLine(raw: String, set: RuleSet) {
        var line = raw.trim()
        if (line.isEmpty() || line[0] == '!' || line[0] == '[') return
        if (line.contains("##") || line.contains("#@#") || line.contains("#?#") ||
            line.contains("#$#") || line.contains("#%#")
        ) return

        var exception = false
        if (line.startsWith("@@")) {
            exception = true
            line = line.substring(2)
        }

        var party = 0
        var typeMask = ResourceType.ALL
        var include: Array<String>? = null
        var exclude: Array<String>? = null

        val dollar = line.lastIndexOf('$')
        if (dollar >= 0 && looksLikeOptions(line, dollar)) {
            val opts = line.substring(dollar + 1)
            line = line.substring(0, dollar)
            var positive = 0
            var negative = 0
            val inc = ArrayList<String>()
            val exc = ArrayList<String>()
            for (opt0 in opts.split(',')) {
                val opt = opt0.trim().lowercase()
                if (opt.isEmpty()) continue
                when {
                    opt == "third-party" || opt == "3p" -> party = 1
                    opt == "~third-party" || opt == "~3p" || opt == "first-party" || opt == "1p" -> party = 2
                    opt.startsWith("domain=") -> {
                        for (d in opt.substring(7).split('|')) {
                            if (d.isEmpty() || d.contains('*')) {
                                set.unsupported++
                                return
                            }
                            if (d.startsWith("~")) exc += d.substring(1) else inc += d
                        }
                    }
                    opt in ignoredOptions -> Unit
                    opt.startsWith("~") && typeNames.containsKey(opt.substring(1)) -> negative = negative or typeNames.getValue(opt.substring(1))
                    typeNames.containsKey(opt) -> positive = positive or typeNames.getValue(opt)
                    else -> {
                        // popup, document, csp, redirect, removeparam, ... — WebView цього не підтримує.
                        set.unsupported++
                        return
                    }
                }
            }
            typeMask = when {
                positive != 0 -> positive
                negative != 0 -> ResourceType.ALL and negative.inv()
                else -> ResourceType.ALL
            }
            if (inc.isNotEmpty()) include = inc.toTypedArray()
            if (exc.isNotEmpty()) exclude = exc.toTypedArray()
        }

        if (line.isEmpty()) return
        if (line.length >= 2 && line.first() == '/' && line.last() == '/') {
            set.unsupported++ // регулярні вирази не підтримуються
            return
        }

        var pattern = line.lowercase()
        var hostAnchored = false
        var startAnchor = false
        var endAnchor = false
        if (pattern.startsWith("||")) {
            hostAnchored = true
            pattern = pattern.substring(2)
        } else if (pattern.startsWith("|")) {
            startAnchor = true
            pattern = pattern.substring(1)
        }
        if (pattern.endsWith("|")) {
            endAnchor = true
            pattern = pattern.dropLast(1)
        }
        if (!hostAnchored && !startAnchor) pattern = pattern.trimStart('*')
        if (!endAnchor) pattern = pattern.trimEnd('*')
        if (pattern.count { it != '*' && it != '^' } < 3 && !hostAnchored) {
            set.unsupported++
            return
        }
        if (pattern.isEmpty()) return

        var hostKey: String? = null
        if (hostAnchored) {
            var j = 0
            while (j < pattern.length && (pattern[j].isLetterOrDigit() || pattern[j] == '.' || pattern[j] == '-')) j++
            val host = pattern.substring(0, j)
            if (host.contains('.') && !host.startsWith('.') && !host.endsWith('.')) {
                hostKey = host
                pattern = pattern.substring(j)
            } else {
                // `||*...` або хост без крапки — зберігаємо як загальний шаблон без прив'язки.
                hostAnchored = false
            }
        }

        val index = if (exception) set.allow else set.block
        if (hostKey != null && (pattern == "^" || pattern.isEmpty()) &&
            !endAnchor && typeMask == ResourceType.ALL && include == null && exclude == null
        ) {
            index.addSimple(hostKey, party)
        } else {
            index.addRule(Rule(pattern, startAnchor, endAnchor, hostAnchored, party, typeMask, include, exclude), hostKey)
        }
        if (exception) set.allowCount++ else set.blockCount++
    }
}
