package com.wmserp.app.presentation.theme

import androidx.compose.ui.graphics.Color

// Brand palette: purple + blue on white
val Purple = Color(0xFF6C4CF1)
val PurpleDeep = Color(0xFF3F2AA6)
val PurpleContainer = Color(0xFFE9E3FF)
val PurpleSoft = Color(0xFFF3F0FF)
val Blue = Color(0xFF2F80ED)
val BlueDeep = Color(0xFF14479A)
val BlueContainer = Color(0xFFDCEBFF)
val Teal = Color(0xFF00B894)
val TealContainer = Color(0xFFD5F5EC)
val Amber = Color(0xFFF5A623)
val AmberContainer = Color(0xFFFFF0D6)
val Coral = Color(0xFFE5484D)
val CoralContainer = Color(0xFFFFE0E1)

val Ink = Color(0xFF1B1B2F)
val InkMuted = Color(0xFF5C5C78)
val Background = Color(0xFFF6F5FC)
val SurfaceVariantLight = Color(0xFFEEEDF7)
val OutlineLight = Color(0xFFD6D5E5)
val OutlineVariantLight = Color(0xFFE8E7F2)

// Dark palette
val PurpleDark = Color(0xFFB4A3FF)
val PurpleContainerDark = Color(0xFF3C2E8A)
val BlueDark = Color(0xFF8DBBFF)
val BlueContainerDark = Color(0xFF1B3A6B)
val BackgroundDark = Color(0xFF121220)
val SurfaceDark = Color(0xFF1B1B2E)
val SurfaceVariantDark = Color(0xFF26263B)
val InkDark = Color(0xFFECEAF7)
val InkMutedDark = Color(0xFFA9A8C2)
val OutlineDark = Color(0xFF3B3B55)
val OutlineVariantDark = Color(0xFF2E2E45)

/** Semantic colours that Material's ColorScheme does not model. */
data class WmsExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val info: Color,
    val infoContainer: Color,
    val danger: Color,
    val dangerContainer: Color,
    val gradientStart: Color,
    val gradientEnd: Color,
    val kpiPurple: Color,
    val kpiBlue: Color,
    val kpiTeal: Color,
    val kpiAmber: Color,
)

val LightExtendedColors = WmsExtendedColors(
    success = Teal,
    onSuccess = Color.White,
    successContainer = TealContainer,
    warning = Amber,
    warningContainer = AmberContainer,
    info = Blue,
    infoContainer = BlueContainer,
    danger = Coral,
    dangerContainer = CoralContainer,
    gradientStart = Purple,
    gradientEnd = Blue,
    kpiPurple = Purple,
    kpiBlue = Blue,
    kpiTeal = Teal,
    kpiAmber = Amber,
)

val DarkExtendedColors = WmsExtendedColors(
    success = Color(0xFF4FD8B5),
    onSuccess = Color(0xFF00382A),
    successContainer = Color(0xFF16483C),
    warning = Color(0xFFFFC76A),
    warningContainer = Color(0xFF4A3A16),
    info = BlueDark,
    infoContainer = BlueContainerDark,
    danger = Color(0xFFFF8A8E),
    dangerContainer = Color(0xFF5A2224),
    gradientStart = Color(0xFF7C5CFF),
    gradientEnd = Color(0xFF3B8BFF),
    kpiPurple = PurpleDark,
    kpiBlue = BlueDark,
    kpiTeal = Color(0xFF4FD8B5),
    kpiAmber = Color(0xFFFFC76A),
)
