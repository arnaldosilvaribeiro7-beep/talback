package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.data.model.ThemeMode

data class TalkbackExtendedColors(
    val railBackground: Color,
    val sidebarBackground: Color,
    val chatBackground: Color,
    val elevatedBackground: Color,
    val subtleBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val online: Color = StatusOnline,
    val idle: Color = StatusIdle,
    val dnd: Color = StatusDnd,
    val offline: Color = StatusOffline
)

private val DarkExtended = TalkbackExtendedColors(
    railBackground = DarkRailBg,
    sidebarBackground = DarkSidebarBg,
    chatBackground = DarkChatBg,
    elevatedBackground = DarkElevatedBg,
    subtleBorder = DarkOutline,
    textPrimary = DarkTextPrimary,
    textSecondary = DarkTextSecondary,
    textMuted = DarkTextMuted
)

private val LightExtended = TalkbackExtendedColors(
    railBackground = LightRailBg,
    sidebarBackground = LightSidebarBg,
    chatBackground = LightChatBg,
    elevatedBackground = LightElevatedBg,
    subtleBorder = LightOutline,
    textPrimary = LightTextPrimary,
    textSecondary = LightTextSecondary,
    textMuted = LightTextMuted
)

val LocalTalkbackColors = staticCompositionLocalOf { DarkExtended }

private val TalkbackDarkColorScheme = darkColorScheme(
    primary = TalkbackBlurple,
    onPrimary = Color.White,
    primaryContainer = TalkbackBlurpleContainer,
    onPrimaryContainer = Color.White,
    secondary = StatusOnline,
    onSecondary = Color.White,
    background = DarkChatBg,
    onBackground = DarkTextPrimary,
    surface = DarkSidebarBg,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkElevatedBg,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkOutline,
    outlineVariant = DarkOutline,
    error = StatusDnd,
    onError = Color.White
)

private val TalkbackLightColorScheme = lightColorScheme(
    primary = TalkbackBlurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E3FF),
    onPrimaryContainer = Color(0xFF1A237E),
    secondary = StatusOnline,
    onSecondary = Color.White,
    background = LightChatBg,
    onBackground = LightTextPrimary,
    surface = LightSidebarBg,
    onSurface = LightTextPrimary,
    surfaceVariant = LightElevatedBg,
    onSurfaceVariant = LightTextSecondary,
    outline = LightOutline,
    outlineVariant = LightOutline,
    error = StatusDnd,
    onError = Color.White
)

private val TalkbackShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun TalkbackTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = if (useDark) TalkbackDarkColorScheme else TalkbackLightColorScheme
    val extended = if (useDark) DarkExtended else LightExtended

    CompositionLocalProvider(LocalTalkbackColors provides extended) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = TalkbackTypography,
            shapes = TalkbackShapes,
            content = content
        )
    }
}
