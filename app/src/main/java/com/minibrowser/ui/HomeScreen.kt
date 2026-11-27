package com.minibrowser.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minibrowser.BrowserViewModel
import com.minibrowser.Screen
import com.minibrowser.data.UrlUtils
import com.minibrowser.web.BrowserTab

/** Стартова сторінка: логотип, великий пошук, Google, улюблені та нещодавні сайти. */
@Composable
fun HomeScreen(vm: BrowserViewModel, tab: BrowserTab, modifier: Modifier = Modifier) {
    val c = BrowserTheme.colors
    val bookmarks by vm.bookmarks.bookmarks.collectAsState()
    val history by vm.history.entries.collectAsState()
    val favorites = remember(bookmarks) { bookmarks.filter { it.favorite }.take(12) }
    val recents = remember(history, tab.isPrivate) { if (tab.isPrivate) emptyList() else vm.history.recentSites(6) }

    Column(
        modifier
            .fillMaxSize()
            .background(c.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(36.dp))
        Logo()
        Spacer(Modifier.height(28.dp))
        BigSearchBar(onSubmit = { vm.submitAddress(it) })
        Spacer(Modifier.height(18.dp))

        // Швидкий доступ до Google
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(c.card)
                    .pressable(onClick = { vm.navigate("https://www.google.com") })
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SiteAvatar("google.com", size = 28.dp)
                Spacer(Modifier.width(10.dp))
                Text("Google", color = c.text, fontWeight = FontWeight.SemiBold)
            }
        }

        if (tab.isPrivate) {
            Spacer(Modifier.height(22.dp))
            PrivateBanner(onInfo = { vm.open(Screen.PRIVATE_INFO) })
        }

        Spacer(Modifier.height(26.dp))
        Text(
            "Улюблені сайти", color = c.secondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        if (favorites.isEmpty()) {
            InfoCard("Додайте сайт у закладки й позначте зірочкою в розділі «Закладки» — він з'явиться тут.", Modifier.padding(horizontal = 0.dp))
        } else {
            favorites.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEach { b ->
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(vertical = 6.dp)
                                .pressable(
                                    onClick = { vm.navigate(b.url) },
                                    onLongClick = {
                                        vm.bookmarks.setFavorite(b.id, false)
                                        vm.toast("«${b.title}» прибрано з улюблених (залишилось у закладках)")
                                    },
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            SiteAvatar(UrlUtils.displayHost(b.url), size = 56.dp)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                b.title, color = c.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 2.dp),
                            )
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Text(
                "Утримуйте іконку, щоб прибрати з улюблених", color = c.secondary.copy(alpha = 0.7f), fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (recents.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(
                "Нещодавно відвідані", color = c.secondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            recents.forEach { e ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(c.card)
                        .pressable(onClick = { vm.navigate(e.url) })
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SiteAvatar(UrlUtils.displayHost(e.url), size = 38.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            e.title.ifBlank { UrlUtils.displayHost(e.url) }, color = c.text, fontSize = 15.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(UrlUtils.displayHost(e.url), color = c.secondary, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Logo() {
    val c = BrowserTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(72.dp)) {
            val stroke = Stroke(width = size.minDimension * 0.07f)
            val r = size.minDimension / 2f - stroke.width
            drawCircle(c.accent, radius = r, style = stroke)
            // меридіан і екватор
            drawOval(
                c.accent,
                topLeft = Offset(size.width / 2f - r * 0.42f, size.height / 2f - r),
                size = Size(r * 0.84f, r * 2f),
                style = Stroke(width = stroke.width * 0.7f),
            )
            drawLine(
                c.accent, Offset(size.width / 2f - r, size.height / 2f), Offset(size.width / 2f + r, size.height / 2f),
                strokeWidth = stroke.width * 0.7f,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = c.text)) { append("Mini") }
                withStyle(SpanStyle(color = c.accent)) { append("Browser") }
            },
            fontSize = 30.sp, fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun BigSearchBar(onSubmit: (String) -> Unit) {
    val c = BrowserTheme.colors
    val focus = LocalFocusManager.current
    var text by remember { mutableStateOf("") }
    Row(
        Modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp)
            .height(60.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(c.card)
            .padding(start = 22.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = TextStyle(color = c.text, fontSize = 17.sp),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, keyboardType = KeyboardType.Uri),
            keyboardActions = KeyboardActions(onSearch = {
                if (text.isNotBlank()) {
                    focus.clearFocus()
                    onSubmit(text)
                }
            }),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth()) {
                    if (text.isEmpty()) Text("Шукайте або введіть адресу", color = c.secondary, fontSize = 17.sp, maxLines = 1)
                    inner()
                }
            },
        )
        IconButton(
            onClick = {
                if (text.isNotBlank()) {
                    focus.clearFocus()
                    onSubmit(text)
                }
            },
            modifier = Modifier.size(48.dp).clip(CircleShape).background(c.accent),
        ) {
            Icon(Icons.Filled.Search, "Знайти", tint = androidx.compose.ui.graphics.Color.White)
        }
    }
}

@Composable
private fun PrivateBanner(onInfo: () -> Unit) {
    val c = BrowserTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(c.accent.copy(alpha = 0.14f))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.VisibilityOff, null, tint = c.accent)
            Spacer(Modifier.width(10.dp))
            Text("Приватна вкладка", color = c.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "MiniBrowser не записує історію й видаляє cookie та дані сайтів після закриття приватних вкладок. " +
                "Сайти та мережевий оператор усе ще можуть бачити ваш трафік.",
            color = c.secondary, fontSize = 13.sp, lineHeight = 19.sp,
        )
        TextButton(onClick = onInfo) { Text("Що зберігається, а що ні", color = c.accent) }
    }
}
