package com.minibrowser.web

import java.net.URLEncoder

/** Внутрішні HTML-сторінки (помилки, попередження). Усі динамічні значення екрануються. */
object InternalPages {

    const val SCHEME = "minibrowser"

    fun esc(s: String): String = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun page(title: String, accent: String, icon: String, body: String): String = """
<!DOCTYPE html><html lang="uk"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)}</title>
<style>
 html,body{margin:0;background:#101116;color:#fff;font-family:sans-serif;}
 .wrap{max-width:520px;margin:0 auto;padding:48px 22px;}
 .icon{width:64px;height:64px;border-radius:20px;background:#22232E;color:$accent;display:flex;
       align-items:center;justify-content:center;font-size:34px;margin-bottom:22px;}
 h1{font-size:22px;margin:0 0 10px;}
 p{color:#92929F;line-height:1.5;margin:0 0 12px;word-break:break-word;}
 code{background:#191A23;border-radius:8px;padding:2px 6px;color:#fff;}
 .btn{display:inline-block;margin:10px 10px 0 0;padding:12px 18px;border-radius:14px;background:#8875FF;
      color:#fff;text-decoration:none;font-weight:600;}
 .btn.alt{background:#22232E;color:#fff;}
 .btn.danger{background:#3A1F25;color:#FF8B9A;}
</style></head><body><div class="wrap"><div class="icon">$icon</div>$body</div></body></html>
""".trimIndent()

    fun errorPage(url: String, title: String, message: String, code: String?): String = page(
        title, "#8875FF", "!",
        """
<h1>${esc(title)}</h1>
<p>${esc(message)}</p>
<p>Адреса: <code>${esc(url)}</code></p>
${if (code != null) "<p>Код помилки: <code>${esc(code)}</code></p>" else ""}
<a class="btn" href="$SCHEME://retry">Спробувати ще раз</a>
<a class="btn alt" href="$SCHEME://back">Назад</a>
""",
    )

    fun sslErrorPage(url: String, host: String, details: String): String = page(
        "Небезпечне з'єднання", "#FF8B9A", "&#128274;",
        """
<h1>З'єднання не є приватним</h1>
<p>MiniBrowser не зміг перевірити сертифікат сайту <code>${esc(host)}</code>. Зловмисники можуть намагатися викрасти ваші дані.</p>
<p>${esc(details)}</p>
<a class="btn alt" href="$SCHEME://back">Повернутися</a>
<a class="btn danger" href="$SCHEME://ssl-proceed?url=${enc(url)}">Усе одно відкрити (небезпечно)</a>
""",
    )

    fun maliciousPage(url: String, host: String): String = page(
        "Підозрілий домен", "#FF8B9A", "&#9888;",
        """
<h1>Підозрілий або шкідливий домен</h1>
<p>Домен <code>${esc(host)}</code> є у списку доменів, що поширюють шкідливе ПЗ (URLhaus). Не вводьте тут паролі й не завантажуйте файли.</p>
<p>Список може містити застарілі записи. Попередження можна вимкнути в Налаштування → Приватність.</p>
<a class="btn alt" href="$SCHEME://back">Повернутися</a>
<a class="btn danger" href="$SCHEME://proceed?url=${enc(url)}">Усе одно відкрити</a>
""",
    )
}
