package com.novaagent.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Color tokens from the OLED-dark developer palette (deep slate + run green),
 * with warm accents so code blocks and the send action stay distinguishable.
 * Everything below is semantic: screens never hardcode a hex value.
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF7DD3A0),
    onPrimary = Color(0xFF07210F),
    primaryContainer = Color(0xFF1B3A29),
    onPrimaryContainer = Color(0xFFB9F5CE),
    secondary = Color(0xFF94A3B8),
    onSecondary = Color(0xFF0F172A),
    secondaryContainer = Color(0xFF243044),
    onSecondaryContainer = Color(0xFFD8E1EE),
    tertiary = Color(0xFFE0A87B),
    onTertiary = Color(0xFF2A170A),
    background = Color(0xFF0F172A),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF0F172A),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1B2336),
    onSurfaceVariant = Color(0xFFA9B6C8),
    surfaceContainer = Color(0xFF161E30),
    surfaceContainerHigh = Color(0xFF1B2336),
    surfaceContainerHighest = Color(0xFF222C42),
    outline = Color(0xFF3E4C63),
    outlineVariant = Color(0xFF2A3448),
    error = Color(0xFFF87171),
    onError = Color(0xFF2A0808),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF166534),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCFCE7),
    onPrimaryContainer = Color(0xFF052E16),
    secondary = Color(0xFF475569),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE2E8F0),
    onSecondaryContainer = Color(0xFF1E293B),
    tertiary = Color(0xFF9A5B2C),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF8FAFC),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFEDF1F6),
    onSurfaceVariant = Color(0xFF475569),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF1F5F9),
    surfaceContainerHighest = Color(0xFFE6EBF2),
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFFCBD5E1),
    error = Color(0xFFB91C1C),
)

/**
 * Type scale: body default 16sp / 1.5 line height (readability floor from the
 * design rules), heading tighter, mono for code and tool output.
 */
@Composable
private fun novaTypography(): Typography {
    val context = LocalContext.current
    val body = AppFonts.family(context, AppFonts.ROLE_BODY)
    val heading = AppFonts.family(context, AppFonts.ROLE_HEADING)
    val mono = AppFonts.family(context, AppFonts.ROLE_MONO)

    val base = Typography()
    return base.copy(
        displaySmall = base.displaySmall.copy(fontFamily = heading, fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontFamily = heading, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
        titleLarge = base.titleLarge.copy(fontFamily = heading, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
        titleMedium = base.titleMedium.copy(fontFamily = heading, fontWeight = FontWeight.Medium, fontSize = 16.sp),
        titleSmall = base.titleSmall.copy(fontFamily = heading, fontWeight = FontWeight.Medium, fontSize = 14.sp),
        bodyLarge = base.bodyLarge.copy(fontFamily = body, fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = base.bodyMedium.copy(fontFamily = body, fontSize = 15.sp, lineHeight = 22.sp),
        bodySmall = base.bodySmall.copy(fontFamily = body, fontSize = 13.sp, lineHeight = 19.sp),
        labelLarge = base.labelLarge.copy(fontFamily = body, fontWeight = FontWeight.Medium, fontSize = 14.sp),
        labelMedium = base.labelMedium.copy(fontFamily = body, fontSize = 12.sp),
        labelSmall = base.labelSmall.copy(fontFamily = body, fontSize = 11.sp, lineHeight = 15.sp),
    )
}

/** Mono style for code, tool rows and paths. Not part of Typography: it is opt-in per composable. */
@Composable
fun monoStyle(size: Int = 13, weight: FontWeight = FontWeight.Normal): TextStyle {
    val context = LocalContext.current
    return TextStyle(
        fontFamily = AppFonts.family(context, AppFonts.ROLE_MONO),
        fontSize = size.sp,
        fontWeight = weight,
        lineHeight = (size * 1.45).sp,
    )
}

/** Shared motion token: every transition uses these so timing feels deliberate, not random. */
object Motion {
    const val FAST = 140
    const val NORMAL = 220
    const val SLOW = 320
}

@Composable
fun NovaAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = novaTypography(),
        content = content,
    )
}

/** 4/8dp spacing rhythm, named so screens stop inventing paddings. */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}
