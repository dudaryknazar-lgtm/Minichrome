package com.minibrowser.ui

import android.os.Build
import android.webkit.WebSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.webkit.WebViewCompat
import com.minibrowser.BrowserViewModel
import java.util.Locale
import java.util.TimeZone

private enum class Control(val label: String) {
    CONTROLLED("Контролюється"),
    PARTIAL("Частково"),
    REPORT_ONLY("Лише виявлення"),
    NOT_CHANGED("Не змінюється"),
}

private class Item(val title: String, val value: String?, val control: Control, val note: String)

/**
 * Прозора таблиця: що саме WebView розкриває сайтам і що MiniBrowser із цим робить.
 * Значення читаються з пристрою в момент відкриття екрана — нічого не вигадується.
 */
@Composable
fun ExposureScreen(vm: BrowserViewModel) {
    val c = BrowserTheme.colors
    val ctx = LocalContext.current
    val items = remember {
        val dm = ctx.resources.displayMetrics
        val ua = try {
            WebSettings.getDefaultUserAgent(ctx)
        } catch (e: Exception) {
            "недоступно"
        }
        val wv = try {
            WebViewCompat.getCurrentWebViewPackage(ctx)?.let { "${it.packageName} ${it.versionName}" } ?: "невідомо"
        } catch (e: Exception) {
            "невідомо"
        }
        listOf(
            Item(
                "Cookie, localStorage, IndexedDB", null, Control.CONTROLLED,
                "Сторонні cookie вимикаються (за налаштуванням або суворим рівнем), дані сайту можна очистити вручну й автоматично, " +
                    "приватні вкладки ізольовані (якщо WebView підтримує профілі). Кеш за сайтами WebView видаляти не вміє.",
            ),
            Item(
                "Сторонні запити й трекери", null, Control.CONTROLLED,
                "Блокуються за EasyList/EasyPrivacy/URLhaus; суворий рівень блокує всі сторонні скрипти, iframe, XHR/fetch. " +
                    "Не охоплено: POST-тіла, WebSocket, частина Service Worker.",
            ),
            Item("Протокол", null, Control.CONTROLLED, "Автоматичне оновлення http → https; відкриття без шифрування лише за вашим підтвердженням."),
            Item(
                "Камера, мікрофон, геолокація", null, Control.CONTROLLED,
                "Кожен запит проходить через діалог і менеджер дозволів із можливістю відкликання. Сповіщення WebView не підтримує.",
            ),
            Item(
                "Canvas, WebGL, аудіо, список пристроїв, батарея", null, Control.REPORT_ONLY,
                "Відомі fingerprinting-скрипти блокуються за списком; спроби зчитування показуються в панелі приватності. " +
                    "Значення не підміняються: підміна API ламає сайти, виявляється самими сайтами і суперечить принципу чесних сигналів.",
            ),
            Item("User-Agent", ua, Control.NOT_CHANGED, "WebView вже передає зменшений User-Agent. MiniBrowser його не змінює."),
            Item("Версія WebView", wv, Control.NOT_CHANGED, "Входить у User-Agent і Client Hints; змінити без підміни неможливо."),
            Item("Мова", Locale.getDefault().toLanguageTag(), Control.NOT_CHANGED, "Заголовок Accept-Language і navigator.language. Не підміняється."),
            Item("Часовий пояс", TimeZone.getDefault().id, Control.NOT_CHANGED, "Доступний через Intl/Date. Не підміняється."),
            Item(
                "Екран", "${dm.widthPixels}×${dm.heightPixels}, ${dm.densityDpi} dpi", Control.NOT_CHANGED,
                "Розмір екрана й щільність пікселів доступні сайтам. Не підміняються.",
            ),
            Item("Система", "Android ${Build.VERSION.RELEASE}", Control.NOT_CHANGED, "Версію Android містить User-Agent."),
            Item(
                "IP-адреса", null, Control.NOT_CHANGED,
                "Видна кожному серверу, до якого ви підключаєтесь, і вашому оператору. Браузер не може її приховати — для цього потрібен VPN.",
            ),
            Item(
                "Referer", null, Control.PARTIAL,
                "WebView сам вирішує, що надсилати (за Referrer-Policy сайту). Змінити заголовок із застосунку не можна; " +
                    "http-адреси не надсилають Referer на https-сайти.",
            ),
            Item(
                "Стан вкладки: видимість, фокус, згортання", null, Control.NOT_CHANGED,
                "Сайти бачать справжні події visibilitychange, focus і blur. MiniBrowser їх не приховує й не підробляє — це свідоме рішення.",
            ),
        )
    }
    ScreenScaffold(title = "Що бачать сайти", onBack = { vm.closeScreen() }) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            InfoCard(
                "Перелік того, що Android WebView розкриває сайтам, і що браузер може законно обмежити. " +
                    "Усе, що позначено «Не змінюється», передається як є: підміна таких сигналів — це обман сайтів, а не захист.",
            )
            items.forEach { it ->
                val tint = when (it.control) {
                    Control.CONTROLLED -> c.success
                    Control.PARTIAL -> c.warning
                    Control.REPORT_ONLY -> c.accent
                    Control.NOT_CHANGED -> c.secondary
                }
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp)
                        .clip(RoundedCornerShape(18.dp)).background(c.card).padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(it.title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Pill(it.control.label, tint)
                    }
                    if (it.value != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(it.value, color = c.text, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(it.note, color = c.secondary, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }
    }
}
