package com.minibrowser.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
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
import com.minibrowser.BuildConfig
import com.minibrowser.Screen
import com.minibrowser.data.SearchEngines
import com.minibrowser.data.ThemeMode
import com.minibrowser.data.UrlUtils
import com.minibrowser.privacy.DataType

@Composable
fun SettingsScreen(vm: BrowserViewModel) {
    val c = BrowserTheme.colors
    val s by vm.settings.state.collectAsState()
    val ctx = LocalContext.current
    var dialogEngine by remember { mutableStateOf(false) }
    var dialogHome by remember { mutableStateOf(false) }
    var dialogClear by remember { mutableStateOf(false) }

    ScreenScaffold(title = "Налаштування", onBack = { vm.closeScreen() }) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SectionHeader("Вигляд")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(18.dp)).background(c.card).padding(4.dp),
            ) {
                listOf(ThemeMode.SYSTEM to "Системна", ThemeMode.DARK to "Темна", ThemeMode.LIGHT to "Світла").forEach { (mode, label) ->
                    val sel = s.theme == mode
                    Row(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                            .background(if (sel) c.accent else Color.Transparent)
                            .pressable(onClick = { vm.updateSettings { it.copy(theme = mode) } })
                            .padding(vertical = 11.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(label, color = if (sel) Color.White else c.secondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            SwitchRow(
                "Темні вебсторінки", "Примусово затемнює сторінки (алгоритмічне затемнення WebView). Працює, якщо його підтримує встановлена версія WebView.",
                s.forceDarkWeb, { v -> vm.updateSettings { it.copy(forceDarkWeb = v) } }, icon = Icons.Filled.Palette,
            )

            SectionHeader("Пошук і головна")
            NavRow(Icons.Filled.Search, "Пошукова система", s.searchEngine.name, onClick = { dialogEngine = true })
            NavRow(
                Icons.Filled.Home, "Домашня сторінка",
                if (s.homepage.isBlank()) "Стартова сторінка MiniBrowser" else s.homepage,
                onClick = { dialogHome = true },
            )
            SwitchRow(
                "JavaScript", "Вимкнення JavaScript ламає більшість сучасних сайтів, але зменшує поверхню атаки.",
                s.javascriptEnabled, { v -> vm.updateSettings { it.copy(javascriptEnabled = v) } },
            )

            SectionHeader("Приватність")
            NavRow(Icons.Filled.Security, "Приватність і захист", "Трекери, реклама, cookie, дозволи, приватний режим", onClick = { vm.open(Screen.PRIVACY) })

            SectionHeader("Завантаження")
            SwitchRow(
                "Запитувати перед завантаженням", "Показувати підтвердження з назвою файлу та сайтом.",
                s.askBeforeDownload, { v -> vm.updateSettings { it.copy(askBeforeDownload = v) } },
            )
            SwitchRow(
                "Лише через Wi-Fi", "Не завантажувати файли через мобільний інтернет.",
                s.downloadWifiOnly, { v -> vm.updateSettings { it.copy(downloadWifiOnly = v) } },
            )
            NavRow(Icons.Filled.Download, "Завантаження", "Файли зберігаються у папці «Завантаження»", onClick = { vm.open(Screen.DOWNLOADS) })

            SectionHeader("Дані")
            NavRow(Icons.Filled.DeleteSweep, "Очистити дані перегляду", "Історія, cookie, кеш, дані сайтів", onClick = { dialogClear = true })

            SectionHeader("Про програму")
            InfoCard(aboutText(ctx))
        }
    }

    if (dialogEngine) {
        AlertDialog(
            onDismissRequest = { dialogEngine = false },
            containerColor = c.panel, titleContentColor = c.text,
            title = { Text("Пошукова система") },
            text = {
                Column {
                    SearchEngines.all.forEach { e ->
                        Row(
                            Modifier.fillMaxWidth().pressable(onClick = {
                                vm.updateSettings { it.copy(searchEngineId = e.id) }
                                dialogEngine = false
                            }).padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = s.searchEngineId == e.id, onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = c.accent, unselectedColor = c.secondary),
                            )
                            Text(e.name, color = c.text, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialogEngine = false }) { Text("Закрити", color = c.accent) } },
        )
    }

    if (dialogHome) {
        var text by remember { mutableStateOf(s.homepage) }
        val ok = text.isBlank() || UrlUtils.isHttp(text.trim())
        AlertDialog(
            onDismissRequest = { dialogHome = false },
            containerColor = c.panel, titleContentColor = c.text,
            title = { Text("Домашня сторінка") },
            text = {
                Column {
                    Text("Залиште порожнім, щоб використовувати стартову сторінку MiniBrowser.", color = c.secondary, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = text, onValueChange = { text = it }, singleLine = true, label = { Text("https://…") },
                        isError = !ok, colors = fieldColors(),
                        supportingText = { if (!ok) Text("Потрібна адреса http:// або https://") },
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = ok, onClick = {
                    vm.updateSettings { it.copy(homepage = text.trim()) }
                    dialogHome = false
                }) { Text("Зберегти", color = if (ok) c.accent else c.secondary) }
            },
            dismissButton = { TextButton(onClick = { dialogHome = false }) { Text("Скасувати", color = c.secondary) } },
        )
    }

    if (dialogClear) ClearDataDialog(vm) { dialogClear = false }
}

private fun aboutText(ctx: Context): String {
    val webView = try {
        WebViewCompat.getCurrentWebViewPackage(ctx)?.let { "${it.packageName} ${it.versionName}" } ?: "невідомо"
    } catch (e: Exception) {
        "невідомо"
    }
    return "MiniBrowser ${BuildConfig.VERSION_NAME}\n" +
        "Android WebView: $webView\n\n" +
        "Застосунок не збирає телеметрію й не має серверної частини. Єдині мережеві запити поза переглядом сайтів — " +
        "оновлення списків фільтрів (за вашою командою) та, якщо ввімкнено, перевірка Google Safe Browsing.\n\n" +
        "Списки фільтрів: EasyList та EasyPrivacy (easylist.to, GPLv3+ / CC BY-SA 3.0), URLhaus (abuse.ch, CC0)."
}

/** Діалог вибору даних для очищення (спільний для налаштувань і розділу приватності). */
@Composable
fun ClearDataDialog(vm: BrowserViewModel, onDismiss: () -> Unit) {
    val c = BrowserTheme.colors
    val selected = remember { mutableStateListOf(DataType.COOKIES, DataType.CACHE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.panel, titleContentColor = c.text,
        title = { Text("Очистити дані") },
        text = {
            Column {
                DataType.entries.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().pressable(onClick = {
                            if (t in selected) selected.remove(t) else selected.add(t)
                        }).padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = t in selected, onCheckedChange = null,
                            colors = CheckboxDefaults.colors(checkedColor = c.accent, uncheckedColor = c.secondary),
                        )
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(t.label, color = c.text)
                            Text(t.description, color = c.secondary, fontSize = 12.sp)
                        }
                    }
                }
                if (DataType.COOKIES in selected) {
                    Spacer(Modifier.height(8.dp))
                    Text("Ви вийдете з акаунтів на сайтах.", color = c.warning, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selected.isNotEmpty(), onClick = {
                vm.clearData(selected.toSet())
                onDismiss()
            }) { Text("Очистити", color = if (selected.isNotEmpty()) c.danger else c.secondary, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати", color = c.secondary) } },
    )
}
