package com.minibrowser.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minibrowser.BrowserViewModel
import com.minibrowser.Screen
import com.minibrowser.privacy.ListCategory
import com.minibrowser.privacy.PermissionDecision
import com.minibrowser.privacy.PrivacySnapshot

private fun ListCategory.title(): String = when (this) {
    ListCategory.AD -> "Реклама"
    ListCategory.TRACKER -> "Трекери та аналітика"
    ListCategory.MALWARE -> "Шкідливі домени"
    ListCategory.FINGERPRINT -> "Fingerprinting-скрипти"
}

/** Панель приватності поточної вкладки. Усі числа — справжні лічильники поточної сторінки. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyDashboardSheet(vm: BrowserViewModel, onDismiss: () -> Unit) {
    val c = BrowserTheme.colors
    val tab by vm.activeTab.collectAsState()
    val changes by vm.dashboard.changes.collectAsState()
    val settings by vm.settings.state.collectAsState()
    val snap: PrivacySnapshot = remember(changes, tab?.id) { vm.dashboard.snapshot(tab?.id) }
    var protectionOff by remember(tab?.host) { mutableStateOf(vm.isSiteProtectionOff()) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val host = tab?.host.orEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.panel,
        contentColor = c.text,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Приватність сторінки", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                if (host.isEmpty()) "Немає відкритої сторінки" else host,
                color = c.secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(14.dp))

            val protectionAny = settings.trackingProtection || settings.adBlocking || settings.suspiciousDomainWarning || settings.fingerprintProtection
            Row(verticalAlignment = Alignment.CenterVertically) {
                Ring(snap.blockedRequests, snap.totalRequests, c.accent, c.card)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Pill(
                        when {
                            host.isEmpty() -> "Немає сторінки"
                            protectionOff -> "Захист для сайту вимкнено"
                            !protectionAny -> "Захист вимкнено в налаштуваннях"
                            else -> "Захист активний"
                        },
                        when {
                            host.isEmpty() -> c.secondary
                            protectionOff || !protectionAny -> c.warning
                            else -> c.success
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Запитів на сторінці: ${snap.totalRequests}", color = c.text, fontSize = 14.sp)
                    Text("Сторонніх: ${snap.thirdPartyRequests}", color = c.secondary, fontSize = 13.sp)
                }
            }
            Text(
                "Лічильники враховують лише запити, які WebView передає застосунку (без POST-тіл, WebSocket, частини Service Worker).",
                color = c.secondary, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 8.dp),
            )

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Заблоковано", snap.blockedRequests.toString(), c.accent, Modifier.weight(1f))
                StatTile("Сторонні cookie*", snap.cookieSuppressedRequests.toString(), c.warning, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Рекламних доменів", snap.blockedDomains(ListCategory.AD).toString(), c.accent, Modifier.weight(1f))
                StatTile("Трекерів виявлено", snap.detectedDomains(ListCategory.TRACKER).toString(), c.accent, Modifier.weight(1f))
            }
            Text(
                "* Кількість сторонніх запитів, надісланих при вимкнених сторонніх cookie. WebView не повідомляє, скільки окремих cookie було відхилено.",
                color = c.secondary, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 6.dp),
            )

            // --- категорії
            SheetHeader("За категоріями")
            val maxCat = ListCategory.entries.maxOf { snap.blocked(it) }.coerceAtLeast(1)
            ListCategory.entries.forEach { cat ->
                val blocked = snap.blocked(cat)
                val detected = snap.detectedDomains(cat)
                CategoryBar(cat.title(), blocked, detected, blocked.toFloat() / maxCat)
            }

            // --- найчастіше блоковані
            SheetHeader("Найчастіше заблоковано")
            if (snap.topBlocked.isEmpty()) {
                Muted("Блокувань на цій сторінці ще не було.")
            } else {
                snap.topBlocked.take(8).forEach { b ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(b.host, color = c.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Pill(b.category.title(), c.secondary)
                        Spacer(Modifier.width(8.dp))
                        Text("×${b.count}", color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // --- підозрілі
            SheetHeader("Підозрілі запити")
            if (snap.suspicious.isEmpty()) {
                Muted("Не виявлено.")
            } else {
                snap.suspicious.take(10).forEach { s ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(s.host, color = c.warning, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.reason, color = c.secondary, fontSize = 12.sp)
                    }
                }
            }

            // --- fingerprinting
            SheetHeader("Спроби зчитування fingerprint")
            when {
                !settings.fingerprintProtection -> Muted("Захист від fingerprinting вимкнено в налаштуваннях.")
                !vm.fingerprintObserverSupported -> Muted("Цей WebView не підтримує детектор (потрібен DOCUMENT_START_SCRIPT). Працює лише блокування відомих скриптів.")
                snap.fingerprintAttempts.isEmpty() -> Muted("Спроб не зафіксовано. Значення сайтам не підміняються — лише реєструється звернення.")
                else -> {
                    val grouped = snap.fingerprintAttempts.groupBy { it.kind }
                    grouped.forEach { (kind, list) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(kind, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(
                                list.mapNotNull { it.frameHost }.distinct().take(2).joinToString(),
                                color = c.secondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            )
                            Text("×${list.size}", color = c.warning, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // --- дозволи
            SheetHeader("Використані дозволи")
            if (snap.permissionEvents.isEmpty()) {
                Muted("Сайт не запитував дозволів.")
            } else {
                snap.permissionEvents.takeLast(8).forEach { e ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(e.type.label, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Pill(
                            e.decision.label,
                            when (e.decision) {
                                PermissionDecision.ALLOW -> c.success
                                PermissionDecision.DENY -> c.danger
                                PermissionDecision.ASK -> c.secondary
                            },
                        )
                    }
                }
            }

            // --- керування
            SheetHeader("Керування")
            if (host.isNotEmpty()) {
                SwitchRow(
                    "Захист на цьому сайті",
                    "Вимкніть, якщо сайт працює некоректно. Перезавантажте сторінку, щоб застосувати.",
                    !protectionOff,
                    { on ->
                        vm.setSiteProtection(on)
                        protectionOff = !on
                    },
                )
            }
            NavRow(
                Icons.Filled.Security, "Налаштування приватності", null,
                onClick = {
                    onDismiss()
                    vm.open(Screen.PRIVACY)
                },
            )
        }
    }
}

@Composable
private fun SheetHeader(text: String) {
    Text(
        text, color = BrowserTheme.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun Muted(text: String) {
    Text(text, color = BrowserTheme.colors.secondary, fontSize = 13.sp, lineHeight = 18.sp)
}

@Composable
private fun StatTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    val c = BrowserTheme.colors
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(c.card).padding(14.dp)) {
        Text(value, color = color, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(label, color = c.secondary, fontSize = 12.sp)
    }
}

@Composable
private fun CategoryBar(title: String, blocked: Int, detected: Int, fraction: Float) {
    val c = BrowserTheme.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(
                if (detected > blocked && blocked == 0) "виявлено: $detected" else "заблоковано: $blocked",
                color = c.secondary, fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.card)) {
            if (fraction > 0f) {
                Box(Modifier.fillMaxWidth(fraction.coerceIn(0.03f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.accent))
            }
        }
    }
}

/** Кільцева діаграма частки заблокованих запитів. Порожнє кільце, якщо запитів ще немає. */
@Composable
private fun Ring(blocked: Int, total: Int, color: Color, track: Color, size: Dp = 96.dp) {
    val c = BrowserTheme.colors
    val fraction = if (total <= 0) 0f else (blocked.toFloat() / total).coerceIn(0f, 1f)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            if (fraction > 0f) {
                drawArc(color, -90f, 360f * fraction, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$blocked", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(if (total > 0) "${(fraction * 100).toInt()}%" else "—", color = c.secondary, fontSize = 11.sp)
        }
    }
}
