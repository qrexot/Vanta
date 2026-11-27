package com.stenograf.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp
import com.stenograf.app.R
import com.stenograf.app.data.prefs.ThemeMode

private val VantaSansFontFamily = FontFamily.SansSerif

val VantaSerifFontFamily = FontFamily(
    Font(R.font.ibm_plex_serif_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_serif_semibold, FontWeight.SemiBold)
)

@Immutable
data class StenografColors(
    val isDark: Boolean,
    val bg: Color,
    val surfaceRecessed: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceHighlight: Color,
    val border: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val liveRed: Color,
    val liveRedSoft: Color,
    val destructive: Color,
    val destructiveSoft: Color,
    val onDestructive: Color,
    val warning: Color,
    val warningSoft: Color,
    val onWarning: Color,
    val success: Color,
    val successSoft: Color,
    val highlightWord: Color
)

private val DarkPalette = StenografColors(
    isDark = true,
    bg = Color(0xFF151E27),
    surfaceRecessed = Color(0xFF111820),
    surface = Color(0xFF1D2733),
    surfaceElevated = Color(0xFF24303F),
    surfaceHighlight = Color(0xFF2B394A),
    border = Color(0xFF10171E),
    borderStrong = Color(0xFF334354),
    textPrimary = Color(0xFFF5F7FA),
    textSecondary = Color(0xFF7F91A4),
    textMuted = Color(0xFF5D6E80),
    accent = Color(0xFF5EA3E8),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFF1E344A),
    liveRed = Color(0xFFFF5252),
    liveRedSoft = Color(0x26FF5252),
    destructive = Color(0xFFF05252),
    destructiveSoft = Color(0x24F05252),
    onDestructive = Color(0xFFFFFFFF),
    warning = Color(0xFFF59E0B),
    warningSoft = Color(0x22F59E0B),
    onWarning = Color(0xFF1B1302),
    success = Color(0xFF3DD68C),
    successSoft = Color(0x1E3DD68C),
    highlightWord = Color(0x555EA3E8)
)

private val LightPalette = StenografColors(
    isDark = false,
    bg = Color(0xFFF0F2F5),
    surfaceRecessed = Color(0xFFF1F3F5),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFFFFFFF),
    surfaceHighlight = Color(0xFFE9EDF2),
    border = Color(0xFFE4E6E9),
    borderStrong = Color(0xFFCFD5DC),
    textPrimary = Color(0xFF222222),
    textSecondary = Color(0xFF707579),
    textMuted = Color(0xFF99A1A8),
    accent = Color(0xFF3390EC),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFE8F3FD),
    liveRed = Color(0xFFE53935),
    liveRedSoft = Color(0x18E53935),
    destructive = Color(0xFFD93025),
    destructiveSoft = Color(0x16D93025),
    onDestructive = Color(0xFFFFFFFF),
    warning = Color(0xFFD97706),
    warningSoft = Color(0x18D97706),
    onWarning = Color(0xFFFFFFFF),
    success = Color(0xFF1E9E52),
    successSoft = Color(0x181E9E52),
    highlightWord = Color(0x333390EC)
)

@Immutable
object VantaType {
    val body = TextStyle(
        fontFamily = VantaSansFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp
    )
    val cardTitle = TextStyle(
        fontFamily = VantaSansFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 21.sp
    )
    val bodySmall = TextStyle(
        fontFamily = VantaSansFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    )
    val pageTitle = TextStyle(
        fontFamily = VantaSansFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp
    )
    val docTitle = TextStyle(
        fontFamily = VantaSansFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp
    )
    val sheetTitle = TextStyle(
        fontFamily = VantaSansFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp
    )
    val rowLabel = TextStyle(
        fontFamily = VantaSansFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp
    )
    val sectionLabel = TextStyle(
        fontFamily = VantaSansFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp
    )
    val monoMetric = TextStyle(
        fontFamily = VantaSansFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = "tnum"
    )
    val monoBadge = TextStyle(
        fontFamily = VantaSansFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontFeatureSettings = "tnum"
    )
    val heroTimer = TextStyle(
        fontFamily = VantaSansFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        fontFeatureSettings = "tnum"
    )

    fun transcriptStyle(
        serif: Boolean = false,
        sizeTier: Int = 1,
        teleprompter: Boolean = false
    ): TextStyle {
        val family = if (serif) VantaSerifFontFamily else VantaSansFontFamily
        val (size, lineHeight) = when {
            teleprompter && sizeTier <= 0 -> 21.sp to 31.sp
            teleprompter && sizeTier == 1 -> 24.sp to 35.sp
            teleprompter -> 28.sp to 40.sp
            sizeTier <= 0 -> 15.sp to 19.5.sp
            sizeTier == 1 -> 17.sp to 22.1.sp
            else -> 20.sp to 26.sp
        }
        return TextStyle(
            fontFamily = family,
            fontWeight = FontWeight.Normal,
            fontSize = size,
            lineHeight = lineHeight,
            textDirection = TextDirection.Content
        )
    }
}

val LocalStenografColors = staticCompositionLocalOf { DarkPalette }

private fun StenografColors.toMaterial3(): ColorScheme {
    return if (isDark) {
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = surfaceElevated,
            onPrimaryContainer = accent,
            surfaceContainer = surface,
            surfaceContainerHigh = surfaceElevated,
            surfaceContainerLowest = surfaceRecessed,
            background = bg,
            onBackground = textPrimary,
            surface = surface,
            onSurface = textPrimary,
            surfaceVariant = surfaceElevated,
            onSurfaceVariant = textSecondary,
            outline = borderStrong,
            outlineVariant = border,
            error = destructive,
            onError = onDestructive
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = surfaceElevated,
            onPrimaryContainer = accent,
            surfaceContainer = surface,
            surfaceContainerHigh = surfaceElevated,
            surfaceContainerLowest = surfaceRecessed,
            background = bg,
            onBackground = textPrimary,
            surface = surface,
            onSurface = textPrimary,
            surfaceVariant = surfaceElevated,
            onSurfaceVariant = textSecondary,
            outline = borderStrong,
            outlineVariant = border,
            error = destructive,
            onError = onDestructive
        )
    }
}

private val StenografTypography = Typography(
    headlineMedium = VantaType.pageTitle,
    titleLarge = VantaType.sheetTitle,
    titleMedium = VantaType.cardTitle,
    bodyLarge = VantaType.body,
    bodyMedium = VantaType.body,
    bodySmall = VantaType.bodySmall,
    labelMedium = VantaType.sectionLabel,
    labelSmall = VantaType.monoBadge
)

@Composable
fun StenografTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val palette = if (isDark) DarkPalette else LightPalette
    CompositionLocalProvider(LocalStenografColors provides palette) {
        MaterialTheme(
            colorScheme = palette.toMaterial3(),
            typography = StenografTypography,
            content = content
        )
    }
}
