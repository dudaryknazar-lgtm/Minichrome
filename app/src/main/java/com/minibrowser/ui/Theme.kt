package com.minibrowser.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Палітра MiniBrowser (див. специфікацію: фон #101116, панелі #191A23, картки #22232E, акцент #8875FF). */
@Immutable
class BrowserColors(
    val bg: Color,
    val panel: Color,
    val card: Color,
    val accent: Color,
    val text: Color,
    val secondary: Color,
    val danger: Color,
    val success: Color,
    val warning: Color,
    val isDark: Boolean,
    val isPrivate: Boolean,
)

private val DarkPalette = BrowserColors(
    bg = Color(0xFF101116), panel = Color(0xFF191A23), card = Color(0xFF22232E),
    accent = Color(0xFF8875FF), text = Color(0xFFFFFFFF), secondary = Color(0xFF92929F),
    danger = Color(0xFFFF6B7D), success = Color(0xFF4ADE80), warning = Color(0xFFFFB84D),
    isDark = true, isPrivate = false,
)

private val LightPalette = BrowserColors(
    bg = Color(0xFFF3F3F8), panel = Color(0xFFFFFFFF), card = Color(0xFFE9E9F2),
    accent = Color(0xFF6B58F2), text = Color(0xFF14141C), secondary = Color(0xFF5F5F70),
    danger = Color(0xFFD6334A), success = Color(0xFF1E9E55), warning = Color(0xFFB9710A),
    isDark = false, isPrivate = false,
)

/** Візуально відмінна палітра приватного режиму. */
private val PrivatePalette = BrowserColors(
    bg = Color(0xFF0C0A16), panel = Color(0xFF16112A), card = Color(0xFF211A3D),
    accent = Color(0xFFD16BFF), text = Color(0xFFFFFFFF), secondary = Color(0xFFA79ABF),
    danger = Color(0xFFFF6B7D), success = Color(0xFF4ADE80), warning = Color(0xFFFFB84D),
    isDark = true, isPrivate = true,
)

val LocalBrowserColors = staticCompositionLocalOf { DarkPalette }

object BrowserTheme {
    val colors: BrowserColors
        @Composable
        @ReadOnlyComposable
        get() = LocalBrowserColors.current
}

@Composable
private fun animated(target: Color): Color {
    val c by animateColorAsState(target, tween(280), label = "themeColor")
    return c
}

@Composable
fun MiniBrowserTheme(dark: Boolean, isPrivate: Boolean, content: @Composable () -> Unit) {
    val p = when {
        isPrivate -> PrivatePalette
        dark -> DarkPalette
        else -> LightPalette
    }
    val colors = BrowserColors(
        bg = animated(p.bg), panel = animated(p.panel), card = animated(p.card), accent = animated(p.accent),
        text = animated(p.text), secondary = animated(p.secondary), danger = p.danger, success = p.success,
        warning = p.warning, isDark = p.isDark, isPrivate = p.isPrivate,
    )
    val scheme = if (p.isDark) {
        darkColorScheme(
            primary = colors.accent, onPrimary = Color.White, secondary = colors.accent,
            background = colors.bg, onBackground = colors.text,
            surface = colors.panel, onSurface = colors.text,
            surfaceVariant = colors.card, onSurfaceVariant = colors.secondary,
            error = colors.danger, outline = colors.secondary,
        )
    } else {
        lightColorScheme(
            primary = colors.accent, onPrimary = Color.White, secondary = colors.accent,
            background = colors.bg, onBackground = colors.text,
            surface = colors.panel, onSurface = colors.text,
            surfaceVariant = colors.card, onSurfaceVariant = colors.secondary,
            error = colors.danger, outline = colors.secondary,
        )
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
        ),
    ) {
        CompositionLocalProvider(
            LocalBrowserColors provides colors,
            LocalContentColor provides colors.text,
            content = content,
        )
    }
}
