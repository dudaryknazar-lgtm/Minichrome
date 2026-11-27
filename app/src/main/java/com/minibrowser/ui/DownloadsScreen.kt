package com.minibrowser.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minibrowser.BrowserViewModel
import com.minibrowser.data.DownloadItem
import com.minibrowser.data.DownloadStatus
import com.minibrowser.data.UrlUtils
import kotlinx.coroutines.delay

private fun formatSize(b: Long): String = when {
    b <= 0 -> "—"
    b < 1024 -> "$b Б"
    b < 1024 * 1024 -> "${b / 1024} КБ"
    b < 1024L * 1024 * 1024 -> String.format("%.1f МБ", b / 1048576.0)
    else -> String.format("%.2f ГБ", b / 1073741824.0)
}

@Composable
fun DownloadsScreen(vm: BrowserViewModel) {
    val c = BrowserTheme.colors
    val items by vm.downloads.items.collectAsState()
    val records by vm.downloads.records.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }

    // Стан завантажень береться з системного DownloadManager; поки екран відкритий — оновлюємо щосекунди.
    LaunchedEffect(records.size) {
        while (true) {
            vm.downloads.refresh()
            delay(1000)
        }
    }

    ScreenScaffold(
        title = "Завантаження",
        onBack = { vm.closeScreen() },
        actions = {
            IconButton(onClick = { confirmClear = true }, enabled = records.isNotEmpty()) {
                Icon(Icons.Filled.DeleteSweep, "Очистити список", tint = if (records.isNotEmpty()) c.text else c.secondary.copy(alpha = 0.4f))
            }
        },
    ) {
        InfoCard("Файли зберігаються у системній папці «Завантаження». Очищення списку не видаляє файли.")
        if (items.isEmpty()) {
            EmptyState(Icons.Filled.Download, "Завантажень ще немає")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { it.record.id }) { item -> DownloadRow(vm, item) }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Очистити список?",
            text = "Записи зникнуть зі списку. Завантажені файли залишаться на пристрої.",
            confirmLabel = "Очистити",
            onConfirm = {
                confirmClear = false
                vm.downloads.clearList()
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun DownloadRow(vm: BrowserViewModel, item: DownloadItem) {
    val c = BrowserTheme.colors
    val done = item.status == DownloadStatus.SUCCESS
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick = {
                if (done) vm.downloads.open(item)?.let { vm.toast(it) } else vm.toast("Файл ще не завантажено")
            })
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.FileDownload, null, tint = if (done) c.success else c.accent, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.record.fileName, color = c.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val status = when (item.status) {
                DownloadStatus.PENDING -> "В черзі"
                DownloadStatus.RUNNING -> "Завантаження: ${formatSize(item.bytes)} / ${formatSize(item.total)}"
                DownloadStatus.PAUSED -> "Призупинено (очікування мережі)"
                DownloadStatus.SUCCESS -> "Завершено · ${formatSize(item.total)}"
                DownloadStatus.FAILED -> "Помилка завантаження"
                DownloadStatus.MISSING -> "Файл видалено або недоступний"
            }
            Text(
                status + if (item.record.isPrivate) " · приватне" else "",
                color = if (item.status == DownloadStatus.FAILED) c.danger else c.secondary, fontSize = 12.sp,
            )
            Text(UrlUtils.displayHost(item.record.url), color = c.secondary.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 1)
            if (item.status == DownloadStatus.RUNNING && item.total > 0) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { (item.bytes.toFloat() / item.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(), color = c.accent, trackColor = c.card,
                )
            }
        }
        IconButton(onClick = { vm.downloads.remove(item.record.id, deleteFile = !done) }) {
            Icon(Icons.Filled.Close, if (done) "Прибрати зі списку" else "Скасувати", tint = c.secondary, modifier = Modifier.size(20.dp))
        }
    }
}
