package com.minibrowser.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minibrowser.BrowserViewModel
import com.minibrowser.data.UrlUtils
import com.minibrowser.web.BrowserTab

/** Перегляд відкритих вкладок у вигляді карток (звичайні та приватні — окремо). */
@Composable
fun TabSwitcherScreen(vm: BrowserViewModel) {
    val c = BrowserTheme.colors
    val tabs by vm.tabs.collectAsState()
    val activeId by vm.activeId.collectAsState()
    val activeIsPrivate = tabs.firstOrNull { it.id == activeId }?.isPrivate == true
    var privateMode by remember { mutableStateOf(activeIsPrivate) }
    var confirmCloseAll by remember { mutableStateOf(false) }
    val list = tabs.filter { it.isPrivate == privateMode }

    ScreenScaffold(
        title = if (privateMode) "Приватні вкладки" else "Вкладки",
        onBack = { vm.closeScreen() },
        actions = {
            IconButton(onClick = { confirmCloseAll = true }, enabled = list.isNotEmpty()) {
                Icon(Icons.Filled.DeleteSweep, "Закрити всі вкладки", tint = if (list.isNotEmpty()) c.text else c.secondary.copy(alpha = 0.4f))
            }
            IconButton(onClick = { vm.newTab(isPrivate = privateMode) }) {
                Icon(Icons.Filled.Add, "Нова вкладка", tint = c.accent)
            }
        },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(18.dp)).background(c.card).padding(4.dp),
        ) {
            ModeButton("Звичайні (${tabs.count { !it.isPrivate }})", !privateMode, Modifier.weight(1f)) { privateMode = false }
            ModeButton("Приватні (${tabs.count { it.isPrivate }})", privateMode, Modifier.weight(1f), Icons.Filled.VisibilityOff) { privateMode = true }
        }

        if (list.isEmpty()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Filled.Tab, null, tint = c.secondary.copy(alpha = 0.6f), modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                Text(if (privateMode) "Немає приватних вкладок" else "Немає відкритих вкладок", color = c.secondary)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { vm.newTab(isPrivate = privateMode) },
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = Color.White),
                ) { Text("Нова вкладка") }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list, key = { it.id }) { t ->
                    TabCard(
                        tab = t,
                        active = t.id == activeId,
                        onClick = { vm.selectTab(t.id) },
                        onClose = { vm.closeTab(t.id) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    if (confirmCloseAll) {
        ConfirmDialog(
            title = "Закрити всі вкладки?",
            text = if (privateMode) "Усі приватні вкладки буде закрито, а їхні дані — видалено." else "Усі звичайні вкладки буде закрито.",
            confirmLabel = "Закрити всі",
            destructive = true,
            onConfirm = {
                confirmCloseAll = false
                vm.closeAllTabs(privateMode)
            },
            onDismiss = { confirmCloseAll = false },
        )
    }
}

@Composable
private fun ModeButton(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    val c = BrowserTheme.colors
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) c.accent else Color.Transparent)
            .pressable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (selected) Color.White else c.secondary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = if (selected) Color.White else c.secondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TabCard(tab: BrowserTab, active: Boolean, onClick: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val c = BrowserTheme.colors
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .clip(shape)
            .background(c.card)
            .border(if (active) 2.dp else 0.dp, if (active) c.accent else Color.Transparent, shape)
            .pressable(onClick = onClick),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SiteAvatar(if (tab.isHome) "MiniBrowser" else tab.host, size = 22.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                tab.displayTitle, color = c.text, fontSize = 13.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Close, "Закрити вкладку", tint = c.secondary, modifier = Modifier.size(18.dp))
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.8f)
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(c.bg),
            contentAlignment = Alignment.Center,
        ) {
            val thumb = tab.thumbnail
            if (thumb != null && !tab.isHome) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SiteAvatar(if (tab.isHome) "MiniBrowser" else tab.host, size = 56.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (tab.isHome) "Нова вкладка" else UrlUtils.displayHost(tab.url),
                        color = c.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}
