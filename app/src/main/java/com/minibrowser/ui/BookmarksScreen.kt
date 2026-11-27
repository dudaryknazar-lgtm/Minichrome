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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minibrowser.BrowserViewModel
import com.minibrowser.data.Bookmark
import com.minibrowser.data.UrlUtils

@Composable
fun BookmarksScreen(vm: BrowserViewModel) {
    val c = BrowserTheme.colors
    val list by vm.bookmarks.bookmarks.collectAsState()
    var editing by remember { mutableStateOf<Bookmark?>(null) }
    var deleting by remember { mutableStateOf<Bookmark?>(null) }

    ScreenScaffold(title = "Закладки", onBack = { vm.closeScreen() }) {
        if (list.isEmpty()) {
            EmptyState(Icons.Filled.Bookmark, "Закладок ще немає.\nДодайте сторінку через меню браузера.")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { b ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .pressable(onClick = { vm.openUrlHere(b.url) })
                            .padding(start = 16.dp, end = 0.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SiteAvatar(UrlUtils.displayHost(b.url), size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(b.title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(UrlUtils.display(b.url), color = c.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { vm.bookmarks.setFavorite(b.id, !b.favorite) }) {
                            Icon(
                                if (b.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                                if (b.favorite) "Прибрати з улюблених" else "Додати до улюблених",
                                tint = if (b.favorite) c.warning else c.secondary,
                            )
                        }
                        IconButton(onClick = { editing = b }) { Icon(Icons.Filled.Edit, "Редагувати", tint = c.secondary, modifier = Modifier.size(20.dp)) }
                        IconButton(onClick = { deleting = b }) { Icon(Icons.Filled.Delete, "Видалити", tint = c.secondary, modifier = Modifier.size(20.dp)) }
                    }
                }
            }
        }
    }

    editing?.let { b ->
        var title by remember(b.id) { mutableStateOf(b.title) }
        var url by remember(b.id) { mutableStateOf(b.url) }
        val urlOk = UrlUtils.isHttp(url)
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = c.panel,
            titleContentColor = c.text,
            title = { Text("Редагувати закладку") },
            text = {
                Column {
                    OutlinedTextField(
                        value = title, onValueChange = { title = it }, singleLine = true, label = { Text("Назва") },
                        colors = fieldColors(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = url, onValueChange = { url = it }, singleLine = true, label = { Text("Адреса") },
                        isError = !urlOk, colors = fieldColors(),
                        supportingText = { if (!urlOk) Text("Адреса має починатися з http:// або https://") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = urlOk,
                    onClick = {
                        vm.bookmarks.update(b.id, title.trim(), url.trim())
                        editing = null
                    },
                ) { Text("Зберегти", color = if (urlOk) c.accent else c.secondary) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Скасувати", color = c.secondary) } },
        )
    }

    deleting?.let { b ->
        ConfirmDialog(
            title = "Видалити закладку?",
            text = b.title,
            confirmLabel = "Видалити",
            destructive = true,
            onConfirm = {
                vm.bookmarks.remove(b.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = BrowserTheme.colors.text,
    unfocusedTextColor = BrowserTheme.colors.text,
    focusedBorderColor = BrowserTheme.colors.accent,
    unfocusedBorderColor = BrowserTheme.colors.secondary.copy(alpha = 0.5f),
    focusedLabelColor = BrowserTheme.colors.accent,
    unfocusedLabelColor = BrowserTheme.colors.secondary,
    cursorColor = BrowserTheme.colors.accent,
    errorBorderColor = BrowserTheme.colors.danger,
    errorLabelColor = BrowserTheme.colors.danger,
    errorSupportingTextColor = BrowserTheme.colors.danger,
)
