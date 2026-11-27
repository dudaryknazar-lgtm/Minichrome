package com.minibrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdsClick
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minibrowser.BrowserViewModel
import com.minibrowser.Screen
import com.minibrowser.data.UrlUtils
import com.minibrowser.privacy.PermissionDecision
import com.minibrowser.privacy.ProtectionLevel
import com.minibrowser.privacy.SitePermissionType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================================================================ Налаштування приватності

@Composable
fun PrivacySettingsScreen(vm: BrowserViewModel) {
    val c = BrowserTheme.colors
    val s by vm.settings.state.collectAsState()
    val lifetime by vm.dashboard.lifetimeBlocked.collectAsState()
    var dialogClear by remember { mutableStateOf(false) }
    var showLimits by remember { mutableStateOf(false) }

    ScreenScaffold(title = "Приватність", onBack = { vm.closeScreen() }) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            InfoCard(
                "Усі перемикачі реально змінюють поведінку браузера. Рахуються лише справжні події; " +
                    "блокування працює в межах можливостей Android WebView (див. «Обмеження» внизу).",
            )

            SectionHeader("Захист від стеження")
            SwitchRow(
                "Tracking Protection",
                "Блокує сторонню аналітику, трекінгові пікселі й трекери зі списку EasyPrivacy.",
                s.trackingProtection, { v -> vm.updateSettings { it.copy(trackingProtection = v) } },
                icon = Icons.Filled.Visibility,
            )
            SwitchRow(
                "Ad Blocking",
                "Блокує рекламні домени та скрипти зі списку EasyList.",
                s.adBlocking, { v -> vm.updateSettings { it.copy(adBlocking = v) } },
                icon = Icons.Filled.AdsClick,
            )
            SwitchRow(
                "Third-Party Cookie Protection",
                "Забороняє сторонні cookie в усіх вкладках (CookieManager.setAcceptThirdPartyCookies). Деякі сайти зі спільним входом можуть працювати гірше.",
                s.thirdPartyCookieProtection, { v -> vm.updateSettings { it.copy(thirdPartyCookieProtection = v) } },
                icon = Icons.Filled.Storage,
            )
            SwitchRow(
                "Fingerprinting Protection",
                "Блокує відомі fingerprinting-скрипти та показує спроби читання canvas, WebGL, аудіо, списку пристроїв і батареї. " +
                    "User-Agent, часовий пояс та інші сигнали браузера не підміняються." +
                    if (vm.fingerprintObserverSupported) "" else "\nДетектор спроб недоступний у цій версії WebView — працює лише блокування за списками.",
                s.fingerprintProtection, { v -> vm.updateSettings { it.copy(fingerprintProtection = v) } },
                icon = Icons.Filled.Fingerprint,
            )
            SwitchRow(
                "Suspicious Domain Warning",
                "Попереджає перед відкриттям доменів зі списку шкідливих (URLhaus) і блокує такі підресурси.",
                s.suspiciousDomainWarning, { v -> vm.updateSettings { it.copy(suspiciousDomainWarning = v) } },
                icon = Icons.Filled.Warning,
            )

            SectionHeader("Режими та дані")
            SwitchRow(
                "Private Browsing",
                "Відкривати нові вкладки приватними за замовчуванням. Приватна вкладка завжди доступна й через меню.",
                s.privateByDefault, { v -> vm.updateSettings { it.copy(privateByDefault = v) } },
                icon = Icons.Filled.VisibilityOff,
            )
            SwitchRow(
                "Automatic Data Cleanup",
                "Видаляє вибрані дані при виході із застосунку та перед наступним запуском. Сайти зі списку «зберігати» не чіпаються.",
                s.autoCleanup, { v -> vm.updateSettings { it.copy(autoCleanup = v) } },
                icon = Icons.Filled.DeleteSweep,
            )
            if (s.autoCleanup) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(18.dp)).background(c.card).padding(8.dp),
                ) {
                    CleanupCheck("Cookie", s.cleanupCookies) { v -> vm.updateSettings { it.copy(cleanupCookies = v) } }
                    CleanupCheck("Кеш", s.cleanupCache) { v -> vm.updateSettings { it.copy(cleanupCache = v) } }
                    CleanupCheck("Історія", s.cleanupHistory) { v -> vm.updateSettings { it.copy(cleanupHistory = v) } }
                    Text(
                        "Сайти, для яких cookie зберігаються, налаштовуються в «Менеджері cookie».",
                        color = c.secondary, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                    )
                }
            }

            SectionHeader("Додатково")
            SwitchRow(
                "Google Safe Browsing",
                "Перевіряє сайти службою Google: на сервер надсилаються хеш-префікси адрес. Вимкніть, якщо не бажаєте цього.",
                s.safeBrowsing, { v -> vm.updateSettings { it.copy(safeBrowsing = v) } },
                icon = Icons.Filled.Lock,
            )
            SwitchRow(
                "Приховувати вміст приватних вкладок",
                "Забороняє знімки екрана та прев'ю в списку недавніх застосунків, поки відкрита приватна вкладка.",
                s.blockScreenshotsInPrivate, { v -> vm.updateSettings { it.copy(blockScreenshotsInPrivate = v) } },
                icon = Icons.Filled.VisibilityOff,
            )

            SectionHeader("Керування")
            NavRow(Icons.Filled.Storage, "Менеджер cookie", "Перегляд, видалення, автоочищення", onClick = { vm.open(Screen.COOKIES) })
            NavRow(Icons.Filled.Language, "Дозволи сайтів", "Камера, мікрофон, геолокація та інші", onClick = { vm.open(Screen.PERMISSIONS) })
            NavRow(Icons.Filled.Visibility, "Що бачать сайти", "Які дані про пристрій і браузер доступні сайтам", onClick = { vm.open(Screen.EXPOSURE) })
            NavRow(Icons.Filled.Tune, "Списки фільтрів", "EasyList, EasyPrivacy, шкідливі домени — оновлення", onClick = { vm.open(Screen.FILTERS) })
            NavRow(Icons.Filled.VisibilityOff, "Приватний перегляд: що зберігається", "Що не записується, а що бачать сайти й мережа", onClick = { vm.open(Screen.PRIVATE_INFO) })
            NavRow(Icons.Filled.DeleteSweep, "Очистити дані перегляду", "Історія, cookie, кеш, дані сайтів", onClick = { dialogClear = true })

            SectionHeader("Рівні захисту сайтів")
            SwitchRow(
                "Суворий рівень за замовчуванням",
                "Для всіх сайтів без власного рівня: блок усіх сторонніх скриптів/iframe/XHR, без сторонніх cookie, лише https, очищення даних сайту після виходу. Частина сайтів працюватиме некоректно — для них у панелі приватності можна обрати інший рівень.",
                s.strictByDefault, { v -> vm.updateSettings { it.copy(strictByDefault = v) } },
                icon = Icons.Filled.Security,
            )
            SwitchRow(
                "Автоматично відкривати через https",
                "Змінює http на https (крім IP-адрес і локальних імен). Якщо сайт не відповідає по https, застосунок запитає, чи відкривати без шифрування.",
                s.httpsUpgrade, { v -> vm.updateSettings { it.copy(httpsUpgrade = v) } },
                icon = Icons.Filled.Lock,
            )
            val overrides = remember(s.protectionOffSites, s.strictSites, s.standardSites) {
                (s.protectionOffSites.map { it to ProtectionLevel.OFF } +
                    s.strictSites.map { it to ProtectionLevel.STRICT } +
                    s.standardSites.map { it to ProtectionLevel.STANDARD }).sortedBy { it.first }
            }
            if (overrides.isEmpty()) {
                InfoCard("Власних рівнів немає. Рівень окремого сайту обирається в панелі приватності (іконка щита в адресному рядку).")
            } else {
                overrides.forEach { (site, lvl) ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(18.dp)).background(c.card).padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(site, color = c.text)
                            Text(lvl.label, color = if (lvl == ProtectionLevel.OFF) c.warning else c.secondary, fontSize = 12.sp)
                        }
                        TextButton(onClick = { vm.resetSiteLevel(site) }) { Text("За замовчуванням", color = c.accent) }
                    }
                }
            }

            SectionHeader("Статистика")
            InfoCard("Усього заблоковано запитів на цьому пристрої: $lifetime. Лічильник зберігається лише локально й нікуди не надсилається.")

            SectionHeader("Обмеження Android WebView")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(18.dp)).background(c.card)
                    .pressable(onClick = { showLimits = !showLimits }).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Security, null, tint = c.accent)
                Spacer(Modifier.width(12.dp))
                Text(if (showLimits) "Сховати" else "Показати, що саме WebView не дозволяє", color = c.text, modifier = Modifier.weight(1f))
            }
            if (showLimits) InfoCard(WEBVIEW_LIMITATIONS)
        }
    }

    if (dialogClear) ClearDataDialog(vm) { dialogClear = false }
}

@Composable
private fun CleanupCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = BrowserTheme.colors
    Row(Modifier.fillMaxWidth().pressable(onClick = { onChange(!checked) }), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, null, colors = CheckboxDefaults.colors(checkedColor = c.accent, uncheckedColor = c.secondary))
        Text(label, color = c.text, modifier = Modifier.padding(start = 8.dp))
    }
}

const val WEBVIEW_LIMITATIONS =
    "• Запити: shouldInterceptRequest не отримує тіл POST-запитів, WebSocket і частини запитів Service Worker, тому вони не блокуються.\n" +
        "• Тип ресурсу (script/image/…) WebView не повідомляє — він визначається за розширенням, заголовками Accept та Sec-Fetch-Dest; правила з обмеженням типу можуть спрацьовувати неточно.\n" +
        "• Косметичні правила EasyList (приховування елементів), \$csp, \$redirect, \$removeparam та регулярні вирази не підтримуються — реклама, вбудована у ту саму домену, може лишитися.\n" +
        "• Сторонні cookie: WebView дозволяє лише увімкнути/вимкнути їх; кількість окремих відхилених cookie WebView не повідомляє. У панелі показано число сторонніх запитів, надісланих без cookie.\n" +
        "• Менеджер cookie бачить лише name=value для адреси сторінки — без домену, терміну, Secure/HttpOnly.\n" +
        "• Fingerprinting: User-Agent, часовий пояс, мова та інші сигнали навмисно не змінюються. Детектор лише повідомляє про звернення до canvas/WebGL/аудіо/пристроїв/батареї й потребує WebView з підтримкою DOCUMENT_START_SCRIPT.\n" +
        "• Приватний режим з повною ізоляцією cookie потребує WebView з підтримкою профілів (MULTI_PROFILE, Chrome/WebView 128+). Інакше cookie спільні зі звичайними вкладками (застосунок про це попередить).\n" +
        "• Дозволи: веб-сповіщення Android WebView не підтримує взагалі; доступ до камери, мікрофона та геолокації контролюється MiniBrowser окремо для кожного сайту.\n" +
        "• Завантаження blob: і data: посилань системний DownloadManager не підтримує.\n" +
        "• Мережевий оператор бачить, до яких серверів ви підключаєтесь (SNI/DNS), навіть якщо сайт заблоковано в браузері лише частково."

// ============================================================================ Менеджер cookie

@Composable
fun CookieManagerScreen(vm: BrowserViewModel) {
    val c = BrowserTheme.colors
    val s by vm.settings.state.collectAsState()
    val tab by vm.activeTab.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var confirmAll by remember { mutableStateOf(false) }
    val (url, cookies) = remember(refresh, tab?.url, tab?.id) { vm.readCookies() }
    val host = url?.let { UrlUtils.hostOf(it) }
    val kept = host != null && vm.privacyManager.isKept(host)

    ScreenScaffold(title = "Менеджер cookie", onBack = { vm.closeScreen() }) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SectionHeader("Поточний сайт")
            if (host == null) {
                InfoCard("Відкрийте сайт у поточній вкладці, щоб побачити його cookie.")
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(18.dp)).background(c.card).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SiteAvatar(host, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(host, color = c.text, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Cookie: ${cookies.size}" + if (tab?.isPrivate == true) " · приватна вкладка" else "",
                            color = c.secondary, fontSize = 13.sp,
                        )
                    }
                }
                if (tab?.isPrivate == true && !vm.cookies.privateIsIsolated()) {
                    InfoCard("У цій версії WebView приватні вкладки діляться cookie зі звичайними.", accent = c.warning)
                }
                cookies.forEach { ck ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp)
                            .clip(RoundedCornerShape(14.dp)).background(c.card).padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(ck.name, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (ck.value.length > 40) ck.value.take(40) + "…" else ck.value.ifEmpty { "(порожнє значення)" },
                                color = c.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = {
                            vm.deleteCookie(ck.name)
                            refresh++
                        }) { Icon(Icons.Filled.Close, "Видалити cookie", tint = c.secondary, modifier = Modifier.size(20.dp)) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            vm.clearSiteCookies()
                            refresh++
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Очистити cookie й дані сайту", color = c.accent, fontSize = 13.sp) }
                    OutlinedButton(onClick = { confirmAll = true }, modifier = Modifier.weight(1f)) {
                        Text("Очистити всі cookie", color = c.danger, fontSize = 13.sp)
                    }
                }
                SwitchRow(
                    "Зберігати cookie цього сайту при автоочищенні",
                    "Вхід в акаунт на $host не буде втрачено, коли працює «Automatic Data Cleanup».",
                    kept,
                    { v ->
                        val key = host
                        vm.updateSettings { st ->
                            val others = st.cleanupKeepHosts.filterNot {
                                UrlUtils.registrableDomain(it) == UrlUtils.registrableDomain(key)
                            }.toSet()
                            st.copy(cleanupKeepHosts = if (v) others + key else others)
                        }
                    },
                )
            }

            SectionHeader("Правила")
            SwitchRow(
                "Обмежити сторонні cookie",
                "Забороняє сайтам ставити cookie від імені інших доменів (реклама, віджети, трекери).",
                s.thirdPartyCookieProtection, { v -> vm.updateSettings { it.copy(thirdPartyCookieProtection = v) } },
            )
            SwitchRow(
                "Автоматичне очищення даних",
                "Видаляє cookie (та інші вибрані дані) при виході; налаштування — у розділі «Приватність».",
                s.autoCleanup, { v -> vm.updateSettings { it.copy(autoCleanup = v) } },
            )
            if (s.cleanupKeepHosts.isNotEmpty()) {
                SectionHeader("Сайти, що зберігаються")
                s.cleanupKeepHosts.sorted().forEach { h ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp)
                            .clip(RoundedCornerShape(14.dp)).background(c.card).padding(start = 16.dp, top = 2.dp, bottom = 2.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(h, color = c.text, modifier = Modifier.weight(1f))
                        IconButton(onClick = { vm.updateSettings { it.copy(cleanupKeepHosts = it.cleanupKeepHosts - h) } }) {
                            Icon(Icons.Filled.Close, "Прибрати", tint = c.secondary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            SectionHeader("Розділення даних")
            InfoCard(
                "Приватні вкладки працюють в окремому профілі WebView з власними cookie й сховищем, якщо це підтримує WebView " +
                    "(зараз: ${if (vm.privateSession.isolationSupported) "підтримується" else "НЕ підтримується"}). " +
                    "Звичайні вкладки діляться одним сховищем — WebView не вміє розділяти дані різних сайтів. " +
                    "Менеджер бачить лише name=value (без домену, терміну дії та прапорців).",
            )
        }
    }

    if (confirmAll) {
        ConfirmDialog(
            title = "Видалити всі cookie?",
            text = "Ви вийдете з акаунтів на всіх сайтах. Це не можна скасувати.",
            confirmLabel = "Видалити все",
            destructive = true,
            onConfirm = {
                confirmAll = false
                vm.clearAllCookies()
                refresh++
            },
            onDismiss = { confirmAll = false },
        )
    }
}

// ============================================================================ Дозволи сайтів

@Composable
fun SitePermissionsScreen(vm: BrowserViewModel) {
    val c = BrowserTheme.colors
    val decisions by vm.permissions.decisions.collectAsState()
    val tab by vm.activeTab.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }
    val currentHost = tab?.host?.takeIf { it.isNotEmpty() }
    val hosts = remember(decisions, currentHost) {
        (decisions.keys + listOfNotNull(currentHost)).distinct().sortedWith(compareBy({ it != currentHost }, { it }))
    }

    ScreenScaffold(
        title = "Дозволи сайтів",
        onBack = { vm.closeScreen() },
        actions = {
            IconButton(onClick = { confirmReset = true }, enabled = decisions.isNotEmpty()) {
                Icon(Icons.Filled.DeleteSweep, "Скинути всі дозволи", tint = if (decisions.isNotEmpty()) c.text else c.secondary.copy(alpha = 0.4f))
            }
        },
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            InfoCard(
                "Тут видно, що дозволено кожному сайту, і можна відкликати дозволи. Системні дозволи Android (камера, мікрофон, " +
                    "геолокація) запитуються лише в момент, коли ви дозволили сайту їх використати.",
            )
            if (hosts.isEmpty()) {
                EmptyState(Icons.Filled.Language, "Збережених рішень ще немає")
            }
            hosts.forEach { h ->
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(20.dp)).background(c.card).padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SiteAvatar(h, size = 34.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(h, color = c.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (h == currentHost) Text("поточний сайт", color = c.accent, fontSize = 12.sp)
                        }
                        TextButton(
                            onClick = { vm.permissions.resetSite(h) },
                            enabled = decisions.containsKey(h),
                        ) { Text("Відкликати все", color = if (decisions.containsKey(h)) c.danger else c.secondary, fontSize = 13.sp) }
                    }
                    SitePermissionType.entries.forEach { type ->
                        val current = decisions[h]?.get(type) ?: type.defaultDecision
                        Spacer(Modifier.height(8.dp))
                        Text(type.label, color = c.text, fontSize = 14.sp)
                        if (!type.supportedByWebView) {
                            Text("Недоступно: Android WebView не підтримує веб-сповіщення.", color = c.secondary, fontSize = 12.sp)
                        } else {
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                PermissionDecision.entries.forEach { d ->
                                    val sel = current == d
                                    Box(
                                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                            .background(
                                                if (sel) when (d) {
                                                    PermissionDecision.ALLOW -> c.success
                                                    PermissionDecision.DENY -> c.danger
                                                    PermissionDecision.ASK -> c.accent
                                                } else c.panel,
                                            )
                                            .pressable(onClick = { vm.permissions.set(h, type, d, false) })
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(d.label, color = if (sel) Color.White else c.secondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmReset) {
        ConfirmDialog(
            title = "Скинути всі дозволи?",
            text = "Усі збережені рішення буде видалено — сайти знову запитуватимуть дозволи.",
            confirmLabel = "Скинути",
            destructive = true,
            onConfirm = {
                confirmReset = false
                vm.permissions.resetAll()
            },
            onDismiss = { confirmReset = false },
        )
    }
}

// ============================================================================ Списки фільтрів

@Composable
fun FilterListsScreen(vm: BrowserViewModel) {
    val c = BrowserTheme.colors
    val states by vm.filterLists.states.collectAsState()
    val updating by vm.updatingFilters.collectAsState()
    val fmt = remember { SimpleDateFormat("d MMM yyyy, HH:mm", Locale("uk")) }
    var confirmReset by remember { mutableStateOf(false) }

    ScreenScaffold(title = "Списки фільтрів", onBack = { vm.closeScreen() }) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            InfoCard(
                "Списки зберігаються локально. Спочатку використовується вбудований знімок, після оновлення — завантажена версія. " +
                    "Оновлення виконується лише за вашою командою прямим HTTPS-запитом без cookie.",
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { vm.updateFilterLists() },
                    enabled = !updating,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = Color.White),
                ) {
                    if (updating) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Оновлення…")
                    } else {
                        Icon(Icons.Filled.Sync, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Оновити списки")
                    }
                }
                OutlinedButton(onClick = { confirmReset = true }, enabled = !updating) { Text("Вбудовані", color = c.secondary) }
            }
            states.forEach { st ->
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(20.dp)).background(c.card).padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Block, null, tint = c.accent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(st.spec.title, color = c.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (st.updating) CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(st.spec.description, color = c.secondary, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (!st.loaded) "Завантаження…" else "Правил блокування: ${st.blockRules} · винятків: ${st.allowRules}",
                        color = c.text, fontSize = 13.sp,
                    )
                    if (st.unsupportedRules > 0) {
                        Text("Пропущено правил, що не підтримуються WebView: ${st.unsupportedRules}", color = c.secondary, fontSize = 12.sp)
                    }
                    Text(
                        when {
                            st.spec.updateUrls.isEmpty() -> "Оновлюється разом із застосунком"
                            st.lastUpdatedMs == 0L -> "Джерело: вбудований знімок"
                            else -> "Оновлено: ${fmt.format(Date(st.lastUpdatedMs))}"
                        },
                        color = c.secondary, fontSize = 12.sp,
                    )
                    if (st.error != null) {
                        Spacer(Modifier.height(6.dp))
                        Text("Помилка оновлення: ${st.error}", color = c.danger, fontSize = 12.sp)
                    }
                }
            }
            InfoCard(
                "Підтримується синтаксис Adblock Plus: правила ||domain^, шаблони з * і ^, винятки @@, опції third-party, domain=, " +
                    "типи ресурсів (наближено). Косметичні правила, регулярні вирази та спеціальні опції (\$csp, \$redirect…) пропускаються.",
            )
        }
    }

    if (confirmReset) {
        ConfirmDialog(
            title = "Повернути вбудовані списки?",
            text = "Завантажені версії буде видалено, застосунок використовуватиме вбудований знімок.",
            confirmLabel = "Повернути",
            onConfirm = {
                confirmReset = false
                vm.filterLists.resetToBundled()
            },
            onDismiss = { confirmReset = false },
        )
    }
}

// ============================================================================ Приватний режим: пояснення

@Composable
fun PrivateInfoScreen(vm: BrowserViewModel) {
    val c = BrowserTheme.colors
    ScreenScaffold(title = "Приватний перегляд", onBack = { vm.closeScreen() }) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            if (vm.privateSession.isolationSupported) {
                InfoCard("Ізоляція даних активна: приватні вкладки працюють в окремому профілі WebView, який видаляється після закриття останньої приватної вкладки.", accent = c.success)
            } else {
                InfoCard(
                    "Ця версія Android WebView не підтримує окремі профілі. Приватні вкладки не пишуть історію й не використовують кеш, " +
                        "але cookie ділять зі звичайними вкладками. Оновіть Android System WebView / Chrome, щоб увімкнути повну ізоляцію.",
                    accent = c.warning,
                )
            }
            SectionHeader("Що НЕ зберігається")
            vm.privateSession.notStored.forEach { InfoBullet(it, c.success) }
            SectionHeader("Що все ще може бути доступним")
            vm.privateSession.stillVisible.forEach { InfoBullet(it, c.warning) }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                Button(
                    onClick = {
                        vm.closeAllScreens()
                        vm.newTab(isPrivate = true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Відкрити приватну вкладку") }
            }
        }
    }
}

@Composable
private fun InfoBullet(text: String, color: Color) {
    val c = BrowserTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp)).background(c.card).padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
        Spacer(Modifier.width(12.dp))
        Text(text, color = c.text, fontSize = 14.sp, lineHeight = 20.sp)
    }
}
