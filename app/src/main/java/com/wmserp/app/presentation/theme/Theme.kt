package com.wmserp.app.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColorScheme: ColorScheme = lightColorScheme(
    primary = Purple,
    onPrimary = Color.White,
    primaryContainer = PurpleContainer,
    onPrimaryContainer = PurpleDeep,
    secondary = Blue,
    onSecondary = Color.White,
    secondaryContainer = BlueContainer,
    onSecondaryContainer = BlueDeep,
    tertiary = Teal,
    onTertiary = Color.White,
    tertiaryContainer = TealContainer,
    onTertiaryContainer = Color(0xFF00382A),
    background = Background,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = InkMuted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAF9FE),
    surfaceContainer = Color(0xFFF3F2FA),
    surfaceContainerHigh = Color(0xFFEDECF5),
    surfaceContainerHighest = SurfaceVariantLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    error = Coral,
    onError = Color.White,
    errorContainer = CoralContainer,
    onErrorContainer = Color(0xFF7A1C1F),
    inverseSurface = Ink,
    inverseOnSurface = Color.White,
    inversePrimary = PurpleDark,
)

private val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = PurpleDark,
    onPrimary = Color(0xFF23145C),
    primaryContainer = PurpleContainerDark,
    onPrimaryContainer = Color(0xFFE9E3FF),
    secondary = BlueDark,
    onSecondary = Color(0xFF00284A),
    secondaryContainer = BlueContainerDark,
    onSecondaryContainer = Color(0xFFDCEBFF),
    tertiary = Color(0xFF4FD8B5),
    onTertiary = Color(0xFF00382A),
    tertiaryContainer = Color(0xFF16483C),
    onTertiaryContainer = Color(0xFFD5F5EC),
    background = BackgroundDark,
    onBackground = InkDark,
    surface = SurfaceDark,
    onSurface = InkDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = InkMutedDark,
    surfaceContainerLowest = Color(0xFF0E0E1A),
    surfaceContainerLow = Color(0xFF171728),
    surfaceContainer = SurfaceDark,
    surfaceContainerHigh = Color(0xFF222236),
    surfaceContainerHighest = SurfaceVariantDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    error = Color(0xFFFF8A8E),
    onError = Color(0xFF5A0F12),
    errorContainer = Color(0xFF5A2224),
    onErrorContainer = Color(0xFFFFDAD9),
    inverseSurface = InkDark,
    inverseOnSurface = BackgroundDark,
    inversePrimary = Purple,
)

val WmsShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

val LocalWmsColors = staticCompositionLocalOf { LightExtendedColors }

object WmsTheme {
    val colors: WmsExtendedColors
        @Composable get() = LocalWmsColors.current
}

@Composable
fun WmsErpTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extended = if (darkTheme) DarkExtendedColors else LightExtendedColors
    CompositionLocalProvider(LocalWmsColors provides extended) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = WmsTypography,
            shapes = WmsShapes,
            content = content,
        )
    }
}
