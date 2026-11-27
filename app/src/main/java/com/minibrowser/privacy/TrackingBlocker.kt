package com.minibrowser.privacy

import com.minibrowser.data.UrlUtils

/**
 * Рішення щодо одного запиту на основі списків фільтрів.
 * [matched] — категорії всіх списків, що збіглися (у порядку пріоритету).
 */
class BlockVerdict(val matched: List<FilterListSpec>) {
    val detected: ListCategory? get() = matched.firstOrNull()?.category

    /** Перша збіглася категорія, для якої блокування ввімкнене. */
    fun blockedBy(enabled: (ListCategory) -> Boolean): FilterListSpec? =
        matched.firstOrNull { enabled(it.category) }

    companion object {
        val NONE = BlockVerdict(emptyList())
    }
}

class TrackingBlocker(private val lists: FilterListManager) {

    fun evaluate(url: String, host: String, pageHost: String?, thirdParty: Boolean, type: Int): BlockVerdict {
        val matcher = lists.matcher
        if (matcher.sets.isEmpty()) return BlockVerdict.NONE
        val ctx = RequestContext(url.lowercase(), host, pageHost, thirdParty, type)
        val matched = matcher.match(ctx)
        return if (matched.isEmpty()) BlockVerdict.NONE else BlockVerdict(matched)
    }

    fun isMalwareHost(host: String): Boolean = lists.matcher.isMalwareHost(host)

    companion object {
        private val imageExt = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp", "avif")
        private val mediaExt = setOf("mp4", "webm", "mp3", "ogg", "m4a", "wav", "m3u8", "mpd", "ts")
        private val fontExt = setOf("woff", "woff2", "ttf", "otf", "eot")

        private fun header(headers: Map<String, String>, name: String): String? {
            for ((k, v) in headers) if (k.equals(name, ignoreCase = true)) return v
            return null
        }

        /**
         * WebView не повідомляє тип ресурсу, тому він наближено визначається за Sec-Fetch-Dest,
         * розширенням файлу та заголовком Accept. 0 = невідомо (правила з обмеженням типу застосовуються).
         */
        fun inferType(path: String?, headers: Map<String, String>): Int {
            when (header(headers, "Sec-Fetch-Dest")?.lowercase()) {
                "script", "worker", "sharedworker", "serviceworker" -> return ResourceType.SCRIPT
                "image" -> return ResourceType.IMAGE
                "style" -> return ResourceType.STYLE
                "iframe", "frame" -> return ResourceType.SUBDOC
                "font" -> return ResourceType.FONT
                "audio", "video", "track" -> return ResourceType.MEDIA
                "object", "embed" -> return ResourceType.OBJECT
                "empty" -> return ResourceType.XHR
            }
            val ext = path?.substringAfterLast('/', "")?.substringAfterLast('.', "")?.lowercase().orEmpty()
            when {
                ext == "js" || ext == "mjs" -> return ResourceType.SCRIPT
                ext == "css" -> return ResourceType.STYLE
                ext in imageExt -> return ResourceType.IMAGE
                ext in mediaExt -> return ResourceType.MEDIA
                ext in fontExt -> return ResourceType.FONT
            }
            val accept = header(headers, "Accept")?.lowercase().orEmpty()
            when {
                accept.startsWith("image/") -> return ResourceType.IMAGE
                accept.startsWith("text/css") -> return ResourceType.STYLE
                accept.startsWith("text/html") -> return ResourceType.SUBDOC
            }
            if (header(headers, "X-Requested-With") != null) return ResourceType.XHR
            return 0
        }

        fun hostIsSuspiciousLiteral(host: String) = UrlUtils.isIpAddress(host)
    }
}
