package com.minibrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minibrowser.BrowserViewModel
import com.minibrowser.LinkAction
import com.minibrowser.data.DownloadRequestInfo
import com.minibrowser.privacy.SitePermissionType
import com.minibrowser.web.JsDialogKind

/** Усі модальні діалоги, що їх просить рушій. Показуються поверх будь-якого екрана. */
@Composable
fun BrowserDialogs(vm: BrowserViewModel) {
    val permission by vm.permissionPrompt.collectAsState()
    val js by vm.jsDialog.collectAsState()
    val download by vm.downloadConfirm.collectAsState()
    val link by vm.linkMenu.collectAsState()

    permission?.let { p -> PermissionPromptDialog(vm, p) }
    js?.let { d -> JsDialogView(vm, d) }
    download?.let { d -> DownloadConfirmDialog(vm, d) }
    link?.let { l -> LinkMenuSheet(vm, l) }
}

@Composable
private fun PermissionPromptDialog(vm: BrowserViewModel, p: com.minibrowser.web.SitePermissionPrompt) {
    val c = BrowserTheme.colors
    var rememberChoice by remember(p.id) { mutableStateOf(!p.isPrivate) }
    val names = p.types.joinToString(", ") { it.label.lowercase() }
    val isDownload = p.types.singleOrNull() == SitePermissionType.AUTO_DOWNLOADS
    AlertDialog(
        onDismissRequest = { vm.dismissPermission() },
        containerColor = c.panel, titleContentColor = c.text, textContentColor = c.secondary,
        title = { Text(p.host.ifEmpty { "Сайт" }) },
        text = {
            Column {
                Text(
                    if (isDownload) "Сайт намагається почати завантаження без вашої дії."
                    else "Сайт просить дозвіл: $names.",
                    color = c.text,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().pressable(onClick = { rememberChoice = !rememberChoice }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = rememberChoice, onCheckedChange = null,
                        colors = CheckboxDefaults.colors(checkedColor = c.accent, uncheckedColor = c.secondary),
                    )
                    Text(
                        if (p.isPrivate) "Запам'ятати до кінця приватної сесії" else "Запам'ятати рішення для цього сайту",
                        color = c.text, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Text("Змінити рішення можна в розділі «Дозволи сайтів».", color = c.secondary, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.answerPermission(p.types.toSet(), rememberChoice) }) {
                Text("Дозволити", color = c.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.answerPermission(emptySet(), rememberChoice) }) { Text("Заборонити", color = c.danger) }
        },
    )
}

@Composable
private fun JsDialogView(vm: BrowserViewModel, d: com.minibrowser.web.JsDialogRequest) {
    val c = BrowserTheme.colors
    var text by remember(d) { mutableStateOf(d.defaultValue) }
    AlertDialog(
        onDismissRequest = { vm.answerJsDialog(false, null) },
        containerColor = c.panel, titleContentColor = c.text, textContentColor = c.text,
        // Заголовок завжди показує, від кого повідомлення, щоб сторінка не могла видати його за системне.
        title = { Text(if (d.host.isNotEmpty()) "Сторінка ${d.host}" else "Повідомлення сторінки", fontSize = 16.sp) },
        text = {
            Column {
                Text(d.message.take(2000), color = c.text)
                if (d.kind == JsDialogKind.PROMPT) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = text, onValueChange = { text = it }, singleLine = true,
                        colors = fieldColors(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.answerJsDialog(true, if (d.kind == JsDialogKind.PROMPT) text else null) }) {
                Text("OK", color = c.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            if (d.kind != JsDialogKind.ALERT) {
                TextButton(onClick = { vm.answerJsDialog(false, null) }) { Text("Скасувати", color = c.secondary) }
            }
        },
    )
}

@Composable
private fun DownloadConfirmDialog(vm: BrowserViewModel, info: DownloadRequestInfo) {
    val c = BrowserTheme.colors
    val size = if (info.contentLength > 0) formatBytes(info.contentLength) else "розмір невідомий"
    AlertDialog(
        onDismissRequest = { vm.confirmDownload(false) },
        containerColor = c.panel, titleContentColor = c.text, textContentColor = c.secondary,
        icon = { Icon(Icons.Filled.Download, null, tint = c.accent) },
        title = { Text("Завантажити файл?") },
        text = {
            Column {
                Text(info.fileName, color = c.text, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text("${info.host} · $size", color = c.secondary, fontSize = 13.sp)
                if (info.isPrivate) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Файл потрапить у загальну папку «Завантаження» і залишиться там після закриття приватної вкладки.",
                        color = c.warning, fontSize = 12.sp,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.confirmDownload(true) }) { Text("Завантажити", color = c.accent, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = { vm.confirmDownload(false) }) { Text("Скасувати", color = c.secondary) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkMenuSheet(vm: BrowserViewModel, req: com.minibrowser.web.LinkMenuRequest) {
    val c = BrowserTheme.colors
    ModalBottomSheet(
        onDismissRequest = { vm.dismissLinkMenu() },
        containerColor = c.panel, contentColor = c.text,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 20.dp)) {
            Text(
                req.url, color = c.secondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
            if (!req.isImage) {
                MenuItem(Icons.Filled.OpenInNew, "Відкрити в новій вкладці") { vm.linkAction(req, LinkAction.OPEN_NEW_TAB) }
                if (!req.isPrivate) MenuItem(Icons.Filled.VisibilityOff, "Відкрити в приватній вкладці") { vm.linkAction(req, LinkAction.OPEN_PRIVATE) }
            }
            if (req.isImage) MenuItem(Icons.Filled.Download, "Зберегти зображення") { vm.linkAction(req, LinkAction.DOWNLOAD_IMAGE) }
            MenuItem(Icons.Filled.ContentCopy, "Копіювати адресу") { vm.linkAction(req, LinkAction.COPY) }
            MenuItem(Icons.Filled.Share, "Поділитися") { vm.linkAction(req, LinkAction.SHARE) }
        }
    }
}

@Composable
private fun MenuItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val c = BrowserTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).pressable(onClick = onClick).padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(icon, null, tint = c.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = c.text, fontSize = 16.sp)
    }
}

internal fun formatBytes(b: Long): String {
    if (b < 1024) return "$b Б"
    val kb = b / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.US, "%.0f КБ", kb)
    val mb = kb / 1024
    if (mb < 1024) return String.format(java.util.Locale.US, "%.1f МБ", mb)
    return String.format(java.util.Locale.US, "%.2f ГБ", mb / 1024)
}
