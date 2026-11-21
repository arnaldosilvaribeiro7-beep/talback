package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Talkback Brand & Status Tokens (Discord-inspired slate & moderate blurple, zero neon)
val TalkbackBlurple = Color(0xFF5865F2)
val TalkbackBlurpleDark = Color(0xFF4752C4)
val TalkbackBlurpleContainer = Color(0xFF3C45A5)

val StatusOnline = Color(0xFF23A55A)
val StatusIdle = Color(0xFFF0B232)
val StatusDnd = Color(0xFFF23F43)
val StatusOffline = Color(0xFF80848E)

// Dark Theme Surface Hierarchy
val DarkRailBg = Color(0xFF1E1F22)
val DarkSidebarBg = Color(0xFF2B2D31)
val DarkChatBg = Color(0xFF313338)
val DarkElevatedBg = Color(0xFF383A40)
val DarkOutline = Color(0xFF3F4147)
val DarkTextPrimary = Color(0xFFF2F3F5)
val DarkTextSecondary = Color(0xFFB5BAC1)
val DarkTextMuted = Color(0xFF949BA4)

// Light Theme Surface Hierarchy
val LightRailBg = Color(0xFFE3E5E8)
val LightSidebarBg = Color(0xFFF2F3F5)
val LightChatBg = Color(0xFFFFFFFF)
val LightElevatedBg = Color(0xFFEBEDEF)
val LightOutline = Color(0xFFD7D9DD)
val LightTextPrimary = Color(0xFF060607)
val LightTextSecondary = Color(0xFF4E5058)
val LightTextMuted = Color(0xFF6D6F78)

val ProfileColorPresets: List<Pair<Long, Long>> = listOf(
    0xFF5865F2L to 0xFF4752C4L, // Blurple clássico
    0xFF23A55AL to 0xFF1A7A42L, // Verde Esmeralda
    0xFF3498DBL to 0xFF21618CL, // Azul Ardósia
    0xFF9B59B6L to 0xFF5B2C6FL, // Ameixa Moderado
    0xFFE67E22L to 0xFFA04000L, // Âmbar Quente
    0xFFE74C3CL to 0xFF922B21L, // Carmesim
    0xFF34495EL to 0xFF1B2631L  // Grafite Profundo
)

val ProfileSingleSwatches: List<Long> = listOf(
    0xFF5865F2L, 0xFF4752C4L, 0xFF23A55AL, 0xFF3498DBL,
    0xFF9B59B6L, 0xFFE67E22L, 0xFFE74C3CL, 0xFF34495EL
)

