package com.bharatsecure.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Brand colours: deep "trust" blue, saffron accent, clear red/amber/green for verdicts. */
object Brand {
    val Navy = Color(0xFF0D2B5E)
    val Blue = Color(0xFF1A56B8)
    val Saffron = Color(0xFFF08C1A)
    val Background = Color(0xFFF4F6FB)
    val Safe = Color(0xFF2E7D32)
    val Warning = Color(0xFFEF6C00)
    val Danger = Color(0xFFC62828)
}

private val Colors = lightColorScheme(
    primary = Brand.Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FA),
    onPrimaryContainer = Brand.Navy,
    secondary = Brand.Saffron,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE8CC),
    onSecondaryContainer = Color(0xFF5A3300),
    background = Brand.Background,
    surface = Color.White,
    surfaceVariant = Color(0xFFE8ECF4),
    onSurfaceVariant = Color(0xFF44474F),
    error = Brand.Danger,
)

@Composable
fun BharatSecureTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
