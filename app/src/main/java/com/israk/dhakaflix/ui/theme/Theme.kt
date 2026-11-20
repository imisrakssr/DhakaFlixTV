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
fun DhakaFlixTheme(accentIndex: Int, isTv: Boolean, edge: Dp, content: @Composable () -> Unit) {
    val target = Accents[accentIndex.coerceIn(0, Accents.lastIndex)].color
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
