package com.vaultforge.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// —— VaultForge 墨绿冷灰配色 ——
val Brand = Color(0xFF2F5D62)
val BrandSoft = Color(0x1A2F5D62)
val VaultBg = Color(0xFFFAFAFA)
val CardBg = Color(0xFFFFFFFF)
val LineColor = Color(0xFFDDE1E4)
val Text1 = Color(0xFF1F2933)
val Text2 = Color(0xFF5C6B73)
val Text3 = Color(0xFF8A959C)
val StatusUp = Color(0xFF2E7D5B)
val StatusWarn = Color(0xFFB7791F)
val StatusDown = Color(0xFFC0453B)
val StatusUnknown = Color(0xFF9AA5AB)

private val VaultColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4E3),
    onPrimaryContainer = Color(0xFF0C2426),
    background = VaultBg,
    onBackground = Text1,
    surface = CardBg,
    onSurface = Text1,
    surfaceVariant = Color(0xFFECEEF0),
    onSurfaceVariant = Text2,
    outline = LineColor,
    error = StatusDown,
)

@Composable
fun VaultForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VaultColors,
        content = content,
    )
}