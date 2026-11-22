package com.israk.dhakaflix.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.israk.dhakaflix.R

data class AccentOption(val name: String, val color: Color)

val Accents = listOf(
    AccentOption("Dhaka Blue", Color(0xFF2E9BFF)),
    AccentOption("Cyan", Color(0xFF22D3EE)),
    AccentOption("Crimson", Color(0xFFE5323B)),
    AccentOption("Emerald", Color(0xFF10B981)),
    AccentOption("Violet", Color(0xFF8B5CF6)),
    AccentOption("Amber", Color(0xFFF59E0B)),
    AccentOption("Rose", Color(0xFFF43F5E)),
    AccentOption("Lime", Color(0xFF84CC16)),
)

/** The accent colour follows the app icon picked in Settings (same palettes as ci/logo.py). */
fun iconAccent(appIcon: String): Color = when (appIcon) {
    "sunset" -> Color(0xFFFF7A28)
    "emerald" -> Color(0xFF10B981)
    "royal" -> Color(0xFF8C50FF)
    "crimson" -> Color(0xFFE5323B)
    "gold" -> Color(0xFFE0A21B)
    "mono" -> Color(0xFF8A94A8)
    else -> Color(0xFF2E9BFF)
}

val Bg = Color(0xFF07080B)
val Surface1 = Color(0xFF12141A)
val Surface2 = Color(0xFF1B1E26)
val TextDim = Color(0xFFA7ACB8)
val TextFaint = Color(0xFF6B7180)

val LocalAccent = staticCompositionLocalOf { Accents[0].color }

/** True on Android TV / leanback devices (D-pad first UI). */
val LocalIsTv = staticCompositionLocalOf { false }

/** Horizontal page padding. */
val LocalEdge = staticCompositionLocalOf { 48.dp }

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private fun TextStyle.inter() = copy(fontFamily = Inter)

private val BaseType = Typography()
private val AppTypography = Typography(
    displayLarge = BaseType.displayLarge.inter(),
    displayMedium = BaseType.displayMedium.inter(),
    displaySmall = BaseType.displaySmall.inter(),
    headlineLarge = BaseType.headlineLarge.inter().copy(fontWeight = FontWeight.Bold),
    headlineMedium = BaseType.headlineMedium.inter().copy(fontWeight = FontWeight.Bold),
    headlineSmall = BaseType.headlineSmall.inter().copy(fontWeight = FontWeight.SemiBold),
    titleLarge = BaseType.titleLarge.inter().copy(fontWeight = FontWeight.SemiBold),
    titleMedium = BaseType.titleMedium.inter().copy(fontWeight = FontWeight.SemiBold),
    titleSmall = BaseType.titleSmall.inter(),
    bodyLarge = BaseType.bodyLarge.inter(),
    bodyMedium = BaseType.bodyMedium.inter(),
    bodySmall = BaseType.bodySmall.inter(),
    labelLarge = BaseType.labelLarge.inter(),
    labelMedium = BaseType.labelMedium.inter(),
    labelSmall = BaseType.labelSmall.inter(),
)

@Composable
fun DhakaFlixTheme(appIcon: String, isTv: Boolean, edge: Dp, content: @Composable () -> Unit) {
    val target = iconAccent(appIcon)
    val accent by animateColorAsState(target, tween(600), label = "accent")
    val scheme = darkColorScheme(
        primary = accent,
        onPrimary = Color.White,
        secondary = accent,
        onSecondary = Color.White,
        background = Bg,
        onBackground = Color.White,
        surface = Surface1,
        onSurface = Color.White,
        surfaceVariant = Surface2,
        onSurfaceVariant = TextDim,
        outline = Color(0xFF3A3F4B),
    )
    CompositionLocalProvider(
        LocalAccent provides accent,
        LocalIsTv provides isTv,
        LocalEdge provides edge,
    ) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}

/**
 * Makes the layout look identical on every TV: everything is designed for a 960dp-wide canvas
 * and scaled to the real screen, then multiplied by the user's size preference.
 */
@Composable
fun ScaledDensity(isTv: Boolean, uiSize: Int, content: @Composable () -> Unit) {
    val cfg = LocalConfiguration.current
    val base = LocalDensity.current
    val user = when (uiSize) { 0 -> 0.88f; 2 -> 1.12f; else -> 1f }
    val factor = if (isTv) (cfg.screenWidthDp / 960f).coerceIn(0.6f, 2.4f) * user else user
    CompositionLocalProvider(LocalDensity provides Density(base.density * factor, base.fontScale)) {
        content()
    }
}
