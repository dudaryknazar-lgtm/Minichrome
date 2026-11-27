package com.minibrowser.data

enum class ThemeMode { SYSTEM, DARK, LIGHT }

data class SearchEngine(
    val id: String,
    val name: String,
    /** Шаблон пошукового запиту; %s замінюється на закодований запит. */
    val template: String,
    val homeUrl: String,
)

object SearchEngines {
    val all: List<SearchEngine> = listOf(
        SearchEngine("google", "Google", "https://www.google.com/search?q=%s", "https://www.google.com"),
        SearchEngine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s", "https://duckduckgo.com"),
        SearchEngine("bing", "Bing", "https://www.bing.com/search?q=%s", "https://www.bing.com"),
        SearchEngine("brave", "Brave Search", "https://search.brave.com/search?q=%s", "https://search.brave.com"),
        SearchEngine("startpage", "Startpage", "https://www.startpage.com/do/search?q=%s", "https://www.startpage.com"),
        SearchEngine("ecosia", "Ecosia", "https://www.ecosia.org/search?q=%s", "https://www.ecosia.org"),
    )

    fun byId(id: String): SearchEngine = all.firstOrNull { it.id == id } ?: all.first()
}

/** Улюблені сайти, що створюються під час першого запуску (користувач може їх змінити). */
object DefaultFavorites {
    val sites: List<Pair<String, String>> = listOf(
        "Wikipedia" to "https://uk.wikipedia.org",
        "YouTube" to "https://www.youtube.com",
        "GitHub" to "https://github.com",
        "Reddit" to "https://www.reddit.com",
        "Stack Overflow" to "https://stackoverflow.com",
        "DuckDuckGo" to "https://duckduckgo.com",
        "Hacker News" to "https://news.ycombinator.com",
        "BBC" to "https://www.bbc.com",
    )
}
