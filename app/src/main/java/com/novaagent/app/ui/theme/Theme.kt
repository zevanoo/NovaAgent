package com.novaagent.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Material 3, unmodified: stock baseline schemes, upgraded to dynamic colour on
 * Android 12+. NovaAgent draws stock M3 components (TopAppBar, Card, Chip,
 * FilledTonalButton, FilledTonalIconButton), so a hand-rolled palette only made
 * them look wrong.
 */
@Composable
fun NovaAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val base = Typography()
    val typography = base.copy(
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
    )

    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}

/**
 * Monospace style for code, paths and tool output. Uses the system font family,
 * like everything else: the app ships no font and never asks for a font file.
 */
@Composable
fun monoStyle(size: Int = 13, weight: FontWeight = FontWeight.Normal): TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = size.sp,
    fontWeight = weight,
    lineHeight = (size * 1.45).sp,
)

/** 4/8dp rhythm, named so screens stop inventing paddings. */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}
