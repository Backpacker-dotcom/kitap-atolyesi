package com.ufuk.kitap.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Brown = Color(0xFF7A3E1D)
val Paper = Color(0xFFFBF7F0)

private val Light = lightColorScheme(
    primary = Brown,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF3DCC8),
    onPrimaryContainer = Color(0xFF3A1A08),
    secondary = Color(0xFFB7791F),
    secondaryContainer = Color(0xFFF7E6C4),
    background = Paper,
    surface = Paper,
    surfaceVariant = Color(0xFFF0E6D8),
    surfaceContainer = Color(0xFFF5EDE2),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFE8B98F),
    onPrimary = Color(0xFF3A1A08),
    primaryContainer = Color(0xFF5E2C12),
    secondary = Color(0xFFE8B04A),
)

@Composable
fun KitapTheme(dark: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}

/** Okuma temaları: arka plan, yazı */
enum class ReadTheme(val label: String, val bg: Color, val fg: Color, val css: Pair<String, String>) {
    LIGHT("Beyaz", Color(0xFFFFFFFF), Color(0xFF222222), "#ffffff" to "#222222"),
    SEPIA("Sepya", Color(0xFFF4ECD8), Color(0xFF5B4636), "#f4ecd8" to "#5b4636"),
    DARK("Gece", Color(0xFF141414), Color(0xFFD8D8D8), "#141414" to "#d8d8d8"),
}
