package com.minibrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minibrowser.BrowserViewModel
import com.minibrowser.data.HistoryEntry
import com.minibrowser.data.UrlUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private sealed interface HistoryRow {
    data class Header(val label: String) : HistoryRow
    data class Entry(val entry: HistoryEntry) : HistoryRow
}

private fun dayLabel(ts: Long): String {
    val cal = Calendar.getInstance()
    val today = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
    cal.timeInMillis = ts
    val day = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
    return when {
        day == today -> "Сьогодні"
        day == today - 1 -> "Вчора"
        else -> SimpleDateFormat("d MMMM yyyy", Locale("uk")).format(Date(ts))
    }
}

@Composable
fun HistoryScreen(vm: BrowserViewModel) {
    val c = BrowserTheme.colors
    val all by vm.history.entries.collectAsState()
    var query by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val rows = remember(all, query) {
        val filtered = vm.history.search(query)
        val out = ArrayList<HistoryRow>()
        var last = ""
        for (e in filtered) {
            val label = dayLabel(e.visitedAt)
            if (label != last) {
                out += HistoryRow.Header(label)
                last = label
            }
            out += HistoryRow.Entry(e)
        }
        out
    }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    ScreenScaffold(
        title = "Історія",
        onBack = { vm.closeScreen() },
        actions = {
            IconButton(onClick = { confirmClear = true }, enabled = all.isNotEmpty()) {
                Icon(Icons.Filled.DeleteSweep, "Очистити історію", tint = if (all.isNotEmpty()) c.text else c.secondary.copy(alpha = 0.4f))
            }
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(c.card)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, null, tint = c.secondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = c.text, fontSize = 15.sp),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    androidx.compose.foundation.layout.Box {
                        if (query.isEmpty()) Text("Пошук в історії", color = c.secondary, fontSize = 15.sp)
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { query = "" }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Filled.Close, "Очистити пошук", tint = c.secondary, modifier = Modifier.size(18.dp))
                }
            }
        }

        if (rows.isEmpty()) {
            EmptyState(Icons.Filled.History, if (query.isEmpty()) "Історія порожня" else "Нічого не знайдено")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { r ->
                    when (r) {
                        is HistoryRow.Header -> "h:" + r.label
                        is HistoryRow.Entry -> "e:" + r.entry.id
                    }
                }) { r ->
                    when (r) {
                        is HistoryRow.Header -> Text(
                            r.label, color = c.accent, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
                        )
                        is HistoryRow.Entry -> {
                            val e = r.entry
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .pressable(onClick = { vm.openUrlHere(e.url) })
                                    .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SiteAvatar(UrlUtils.displayHost(e.url), size = 38.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        e.title.ifBlank { UrlUtils.displayHost(e.url) }, color = c.text, fontSize = 15.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        UrlUtils.display(e.url), color = c.secondary, fontSize = 12.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(timeFmt.format(Date(e.visitedAt)), color = c.secondary, fontSize = 12.sp)
                                IconButton(onClick = { vm.history.remove(e.id) }) {
                                    Icon(Icons.Filled.Close, "Видалити запис", tint = c.secondary, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Очистити історію?",
            text = "Усі записи про відвідані сторінки буде видалено без можливості відновлення.",
            confirmLabel = "Очистити",
            destructive = true,
            onConfirm = {
                confirmClear = false
                vm.history.clear()
            },
            onDismiss = { confirmClear = false },
        )
    }
}
