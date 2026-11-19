package com.novaagent.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD97757),
    background = Color(0xFF1A1915),
    surface = Color(0xFF1A1915),
    onBackground = Color(0xFFE8E4DC),
    onSurface = Color(0xFFE8E4DC),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFB4532F),
    background = Color(0xFFFAF9F5),
    surface = Color(0xFFFAF9F5),
)

@Composable
fun NovaAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
