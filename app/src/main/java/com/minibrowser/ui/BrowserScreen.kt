@file:OptIn(ExperimentalMaterial3Api::class)

package com.minibrowser.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.minibrowser.BrowserViewModel
import com.minibrowser.Screen
import com.minibrowser.data.UrlUtils
import com.minibrowser.web.BrowserTab

/** Стан інтерфейсу, що потрібен і BrowserScreen, і кореневому обробнику «Назад». */
@Stable
class BrowserUiState {
    var addressFocused by mutableStateOf(false)
    var showMenu by mutableStateOf(false)
    var showDashboard by mutableStateOf(false)
}

/**
 * Головний екран: верхня панель (адреса), вміст (WebView або стартова сторінка), нижня навігація.
 *
 * Безпечні зони: верхня панель відступає на висоту статус-бара/вирізу, нижня — на висоту
 * системної панелі жестів/кнопок; жодних фіксованих координат — лише WindowInsets.
 */
@Composable
fun BrowserScreen(vm: BrowserViewModel, ui: BrowserUiState, modifier: Modifier = Modifier) {
    val c = BrowserTheme.colors
    val tab by vm.activeTab.collectAsState()
    val tabs by vm.tabs.collectAsState()
    val fullscreen by vm.fullscreen.collectAsState()
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val focusManager = LocalFocusManager.current
    var addressText by remember { mutableStateOf("") }

    Column(
        modifier
            .fillMaxSize()
            .background(c.bg)
            .imePadding(),
    ) {
        AnimatedVisibility(
            visible = !fullscreen,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            TopBar(vm, tab, ui, onAddressText = { addressText = it })
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {
            val t = tab
            if (t == null) {
                Box(Modifier.fillMaxSize().background(c.bg))
            } else if (t.isHome) {
                HomeScreen(vm, t)
            } else {
                WebViewHost(vm, t, Modifier.fillMaxSize())
            }

            if (ui.addressFocused && addressText.isNotBlank()) {
                SuggestionsPanel(
                    vm = vm,
                    query = addressText,
                    onPick = { url ->
                        focusManager.clearFocus()
                        vm.navigate(url)
                    },
                    onSearch = {
                        focusManager.clearFocus()
                        vm.submitAddress(addressText)
                    },
                )
            }

            if (fullscreen) {
                IconButton(
                    onClick = { vm.exitFullscreen() },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        .padding(8.dp)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f)),
                ) {
                    Icon(Icons.Filled.FullscreenExit, "Вийти з повноекранного режиму", tint = Color.White)
                }
            }
        }

        AnimatedVisibility(
            visible = !fullscreen && !imeVisible,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            BottomBar(vm, tab, tabs.count { it.isPrivate == (tab?.isPrivate == true) }, ui)
        }
    }

    if (ui.showMenu) MenuSheet(vm, tab, onDismiss = { ui.showMenu = false }, onDashboard = { ui.showDashboard = true })
    if (ui.showDashboard) PrivacyDashboardSheet(vm, onDismiss = { ui.showDashboard = false })
}

// ---------------------------------------------------------------------------------- WebView

@Composable
private fun WebViewHost(vm: BrowserViewModel, tab: BrowserTab, modifier: Modifier) {
    androidx.compose.runtime.key(tab.id, tab.generation) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                FrameLayout(ctx).apply {
                    val wv = vm.webViewFor(tab)
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
            },
            onRelease = { it.removeAllViews() },
        )
    }
}

// ---------------------------------------------------------------------------------- верхня панель

@Composable
private fun TopBar(vm: BrowserViewModel, tab: BrowserTab?, ui: BrowserUiState, onAddressText: (String) -> Unit) {
    val c = BrowserTheme.colors
    val tick by vm.dashboard.changes.collectAsState()
    val snap = remember(tick, tab?.id) { vm.dashboard.snapshot(tab?.id) }
    val settings by vm.settings.state.collectAsState()
    val protectionOff = tab != null && tab.host.isNotEmpty() && vm.privacy.isProtectionOffFor(tab.host) && settings.protectionOffSites.isNotEmpty()

    Column(
        Modifier
            .fillMaxWidth()
            .background(c.panel)
            .windowInsetsPadding(
                WindowInsets.statusBars.union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Кнопка панелі приватності з реальним лічильником заблокованих запитів
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                IconButton(onClick = { ui.showDashboard = true }) {
                    Icon(
                        Icons.Filled.Security, "Панель приватності",
                        tint = if (protectionOff) c.secondary else c.accent,
                    )
                }
                if (snap.blockedRequests > 0) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 4.dp, end = 2.dp)
                            .clip(CircleShape)
                            .background(c.accent)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        Text(
                            if (snap.blockedRequests > 99) "99+" else snap.blockedRequests.toString(),
                            color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            AddressBar(
                tab = tab,
                modifier = Modifier.weight(1f),
                onSubmit = { vm.submitAddress(it) },
                onFocusChange = { ui.addressFocused = it },
                onTextChange = onAddressText,
            )

            Crossfade(targetState = tab?.isLoading == true, label = "reloadStop") { loading ->
                BarIconButton(
                    icon = if (loading) Icons.Filled.Close else Icons.Filled.Refresh,
                    description = if (loading) "Зупинити завантаження" else "Оновити сторінку",
                    onClick = { vm.reloadOrStop() },
                    enabled = tab != null && !tab.isHome,
                )
            }
        }
        // Індикатор завантаження резервує постійні 2 dp, тож панель не «стрибає».
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            val p by animateFloatAsState((tab?.progress ?: 0) / 100f, label = "progress")
            if (tab?.isLoading == true) {
                LinearProgressIndicator(
                    progress = { p },
                    modifier = Modifier.fillMaxWidth(),
                    color = c.accent,
                    trackColor = Color.Transparent,
                )
            }
        }
    }
}

@Composable
private fun AddressBar(
    tab: BrowserTab?,
    modifier: Modifier,
    onSubmit: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onTextChange: (String) -> Unit,
) {
    val c = BrowserTheme.colors
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf(TextFieldValue("")) }
    val shown = when {
        tab == null || tab.isHome -> ""
        else -> UrlUtils.displayHost(tab.url)
    }
    // Коли поле не в фокусі, показуємо чисту назву хоста; у фокусі — повну адресу.
    androidx.compose.runtime.LaunchedEffect(tab?.id, tab?.url, focused) {
        if (!focused) value = TextFieldValue(shown)
    }

    Row(
        modifier
            .padding(horizontal = 4.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(c.card)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val leading: ImageVector
        val leadingTint: Color
        when {
            focused -> { leading = Icons.Filled.Search; leadingTint = c.secondary }
            tab == null || tab.isHome -> { leading = Icons.Filled.Search; leadingTint = c.secondary }
            tab.isErrorPage -> { leading = Icons.Filled.Warning; leadingTint = c.warning }
            tab.isSecure -> { leading = Icons.Filled.Lock; leadingTint = c.success }
            else -> { leading = Icons.Filled.LockOpen; leadingTint = c.warning }
        }
        Icon(leading, null, tint = leadingTint, modifier = Modifier.size(18.dp))
        if (tab?.isPrivate == true && !focused) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.VisibilityOff, "Приватна вкладка", tint = c.accent, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = value,
            onValueChange = {
                value = it
                onTextChange(it.text)
            },
            singleLine = true,
            textStyle = TextStyle(color = c.text, fontSize = 15.sp),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri),
            keyboardActions = KeyboardActions(onGo = {
                onSubmit(value.text)
                focusManager.clearFocus()
            }),
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { f ->
                    if (f.isFocused && !focused) {
                        focused = true
                        val full = tab?.url.orEmpty()
                        value = TextFieldValue(full, TextRange(0, full.length))
                        onTextChange("")
                    } else if (!f.isFocused && focused) {
                        focused = false
                    }
                    onFocusChange(f.isFocused)
                },
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth()) {
                    if (value.text.isEmpty()) Text("Пошук або адреса", color = c.secondary, fontSize = 15.sp, maxLines = 1)
                    inner()
                }
            },
        )
        if (focused && value.text.isNotEmpty()) {
            IconButton(onClick = {
                value = TextFieldValue("")
                onTextChange("")
            }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Close, "Очистити", tint = c.secondary, modifier = Modifier.size(18.dp))
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }
    }
}

// ---------------------------------------------------------------------------------- підказки

@Composable
private fun SuggestionsPanel(vm: BrowserViewModel, query: String, onPick: (String) -> Unit, onSearch: () -> Unit) {
    val c = BrowserTheme.colors
    val history by vm.history.entries.collectAsState()
    val bookmarks by vm.bookmarks.bookmarks.collectAsState()
    val items = remember(query, history, bookmarks) {
        val fromBookmarks = vm.bookmarks.suggestions(query, 4).map { Triple(it.title, it.url, true) }
        val fromHistory = vm.history.search(query).take(12).map { Triple(it.title, it.url, false) }
        (fromBookmarks + fromHistory).distinctBy { it.second }.take(6)
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier.fillMaxWidth().pressable(onClick = onSearch).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, null, tint = c.accent)
            Spacer(Modifier.width(14.dp))
            Text(
                "Шукати «$query»",
                color = c.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        items.forEach { (title, url, isBookmark) ->
            Row(
                Modifier.fillMaxWidth().pressable(onClick = { onPick(url) }).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SiteAvatar(UrlUtils.displayHost(url), size = 36.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title.ifBlank { UrlUtils.displayHost(url) },
                        color = c.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(UrlUtils.display(url), color = c.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(
                    if (isBookmark) Icons.Filled.Bookmark else Icons.Filled.History, null,
                    tint = c.secondary, modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------- нижня панель

@Composable
private fun BottomBar(vm: BrowserViewModel, tab: BrowserTab?, tabCount: Int, ui: BrowserUiState) {
    val c = BrowserTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.panel)
            .windowInsetsPadding(
                WindowInsets.navigationBars.union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            )
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        BarIconButton(
            Icons.AutoMirrored.Filled.ArrowBack, "Назад",
            onClick = { vm.goBack() },
            enabled = tab != null && (tab.canGoBack || !tab.isHome),
        )
        BarIconButton(
            Icons.AutoMirrored.Filled.ArrowForward, "Вперед",
            onClick = { vm.goForward() },
            enabled = tab?.canGoForward == true,
        )
        BarIconButton(Icons.Filled.Add, "Нова вкладка", onClick = { vm.newTab() })
        IconButton(onClick = { vm.open(Screen.TABS) }) {
            Box(
                Modifier
                    .size(26.dp)
                    .border(2.dp, c.text, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (tabCount > 99) ":)" else tabCount.toString(),
                    color = c.text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
        BarIconButton(Icons.Filled.MoreVert, "Меню", onClick = { ui.showMenu = true })
    }
}

// ---------------------------------------------------------------------------------- меню

@Composable
private fun MenuSheet(vm: BrowserViewModel, tab: BrowserTab?, onDismiss: () -> Unit, onDashboard: () -> Unit) {
    val c = BrowserTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val bookmarks by vm.bookmarks.bookmarks.collectAsState()
    val bookmarked = tab != null && bookmarks.any { it.url == tab.url }
    val canActOnPage = tab != null && UrlUtils.isHttp(tab.url)

    fun act(block: () -> Unit) {
        onDismiss()
        block()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.panel,
        contentColor = c.text,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MenuTile(Icons.Filled.Add, "Нова вкладка", Modifier.weight(1f)) { act { vm.newTab(isPrivate = false) } }
                MenuTile(Icons.Filled.VisibilityOff, "Приватна вкладка", Modifier.weight(1f)) { act { vm.newTab(isPrivate = true) } }
            }
            Spacer(Modifier.height(8.dp))
            MenuItem(Icons.Filled.Home, "Головна сторінка") { act { vm.goHome() } }
            MenuItem(
                if (bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                if (bookmarked) "Видалити із закладок" else "Додати в закладки",
                enabled = canActOnPage,
            ) { act { vm.toggleBookmark() } }
            MenuItem(Icons.Filled.Share, "Поділитися", enabled = canActOnPage) { act { tab?.let { vm.shareUrl(it.url) } } }
            MenuItem(Icons.Filled.Fullscreen, "Повноекранний режим") { act { vm.toggleFullscreen() } }
            MenuItem(Icons.Filled.PrivacyTip, "Панель приватності сторінки") { act { onDashboard() } }
            MenuItem(Icons.Filled.Bookmark, "Закладки") { act { vm.open(Screen.BOOKMARKS) } }
            MenuItem(Icons.Filled.History, "Історія") { act { vm.open(Screen.HISTORY) } }
            MenuItem(Icons.Filled.Download, "Завантаження") { act { vm.open(Screen.DOWNLOADS) } }
            MenuItem(Icons.Filled.Storage, "Менеджер cookie") { act { vm.open(Screen.COOKIES) } }
            MenuItem(Icons.Filled.Language, "Дозволи сайтів") { act { vm.open(Screen.PERMISSIONS) } }
            MenuItem(Icons.Filled.Security, "Приватність") { act { vm.open(Screen.PRIVACY) } }
            MenuItem(Icons.Filled.Settings, "Налаштування") { act { vm.open(Screen.SETTINGS) } }
        }
    }
}

@Composable
private fun MenuTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val c = BrowserTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(c.card)
            .pressable(onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = c.accent, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = BrowserTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick, enabled = enabled)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (enabled) c.text else c.secondary.copy(alpha = 0.5f), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, color = if (enabled) c.text else c.secondary.copy(alpha = 0.5f), fontSize = 16.sp)
    }
}
