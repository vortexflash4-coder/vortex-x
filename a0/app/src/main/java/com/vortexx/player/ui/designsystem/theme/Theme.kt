package com.vortexx.player.ui.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Central design tokens for VORTEX X: Premium Dark Cinema + Pro Audio Workstation. */
object VortexColors {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF0B0C10)
    val Card = Color(0xFF121319)
    val CardRaised = Color(0xFF1A1C24)
    val Accent = Color(0xFFE71938)
    val AccentActive = Color(0xFFFF3452)
    val AccentContainer = Color(0xFF3A0B15)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFA5A8B5)
    val Divider = Color(0xFF262833)
    val Success = Color(0xFF38C98A)

    /** Used only for "limited / partial" states (never decorative). */
    val Caution = Color(0xFFF2B33D)

    val HeroGlow: Brush = Brush.verticalGradient(
        0f to Accent.copy(alpha = 0.22f),
        0.55f to Accent.copy(alpha = 0.05f),
        1f to Color.Transparent,
    )
    val ArtworkScrim: Brush = Brush.verticalGradient(
        0f to Color.Transparent,
        1f to Background.copy(alpha = 0.85f),
    )
}

object VortexSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** Minimum interactive size (Material accessibility guidance). */
    val touchTarget = 48.dp
}

object VortexRadius {
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val pill = 50
}

private val VortexColorScheme = darkColorScheme(
    primary = VortexColors.Accent,
    onPrimary = VortexColors.TextPrimary,
    primaryContainer = VortexColors.AccentContainer,
    onPrimaryContainer = VortexColors.AccentActive,
    secondary = VortexColors.AccentActive,
    onSecondary = VortexColors.TextPrimary,
    secondaryContainer = VortexColors.CardRaised,
    onSecondaryContainer = VortexColors.TextPrimary,
    tertiary = VortexColors.Success,
    onTertiary = VortexColors.Background,
    background = VortexColors.Background,
    onBackground = VortexColors.TextPrimary,
    surface = VortexColors.Surface,
    onSurface = VortexColors.TextPrimary,
    surfaceVariant = VortexColors.Card,
    onSurfaceVariant = VortexColors.TextSecondary,
    surfaceTint = VortexColors.Accent,
    surfaceContainerLowest = VortexColors.Background,
    surfaceContainerLow = Color(0xFF101117),
    surfaceContainer = VortexColors.Surface,
    surfaceContainerHigh = VortexColors.Card,
    surfaceContainerHighest = VortexColors.CardRaised,
    outline = VortexColors.Divider,
    outlineVariant = VortexColors.Divider,
    error = VortexColors.AccentActive,
    onError = VortexColors.TextPrimary,
    errorContainer = VortexColors.AccentContainer,
    onErrorContainer = VortexColors.AccentActive,
    scrim = Color(0xCC000000),
)

private val Sans = FontFamily.SansSerif

val VortexTypography = Typography(
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Black, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.25).sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.8.sp),
)

val VortexShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(VortexRadius.sm),
    medium = RoundedCornerShape(VortexRadius.md),
    large = RoundedCornerShape(VortexRadius.lg),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun VortexTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VortexColorScheme,
        typography = VortexTypography,
        shapes = VortexShapes,
        content = content,
    )
}
