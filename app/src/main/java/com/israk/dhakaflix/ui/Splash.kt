package com.israk.dhakaflix.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.israk.dhakaflix.R
import com.israk.dhakaflix.ui.theme.Bg
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Short animated intro: logo springs in, wordmark slides up, then the credit fades in. */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val logoScale = remember { Animatable(0.55f) }
    val logoAlpha = remember { Animatable(0f) }
    val glow = remember { Animatable(0f) }
    val word = remember { Animatable(0f) }
    val credit = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch { logoAlpha.animateTo(1f, tween(450)) }
        launch { glow.animateTo(1f, tween(1200, easing = FastOutSlowInEasing)) }
        logoScale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 180f))
        word.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
        credit.animateTo(1f, tween(450))
        delay(650)
        onDone()
    }

    // colours follow the app icon picked in Settings (same palettes as ci/logo.py)
    val variant = com.israk.dhakaflix.DhakaFlixApp.instance.settings.appIcon.value
    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun res(name: String, fallback: Int): Int =
        ctx.resources.getIdentifier("${name}_$variant", "drawable", ctx.packageName).takeIf { it != 0 } ?: fallback
    val markRes = remember(variant) { res("logo_mark", R.drawable.logo_mark) }
    val wordRes = remember(variant) { res("logo_wordmark", R.drawable.logo_wordmark) }
    val glowColor = when (variant) {
        "sunset" -> Color(0xFFFF7A28)
        "emerald" -> Color(0xFF10C878)
        "royal" -> Color(0xFF8C50FF)
        "crimson" -> Color(0xFFE50914)
        "gold" -> Color(0xFFFFBA28)
        "mono" -> Color(0xFFBEC8DC)
        else -> Color(0xFF2E9BFF)
    }
    Box(
        Modifier.fillMaxSize().background(Bg),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(520.dp).graphicsLayer { alpha = glow.value * 0.9f; scaleX = 0.6f + glow.value * 0.5f; scaleY = scaleX }
                .background(Brush.radialGradient(listOf(glowColor.copy(alpha = 0x55 / 255f), glowColor.copy(alpha = 0f))))
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Image(
                painterResource(markRes), null,
                Modifier.size(120.dp).graphicsLayer {
                    scaleX = logoScale.value; scaleY = logoScale.value; alpha = logoAlpha.value
                },
            )
            Spacer(Modifier.height(20.dp))
            Image(
                painterResource(wordRes), "DhakaFlix",
                Modifier.height(36.dp).graphicsLayer { alpha = word.value; translationY = (1f - word.value) * 30f },
            )
            Spacer(Modifier.height(36.dp))
            Text(
                "Made ❤️ by Israk Sisir",
                Modifier.graphicsLayer { alpha = credit.value; translationY = (1f - credit.value) * 16f },
                color = Color.White.copy(alpha = .85f), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium, letterSpacing = 1.sp,
            )
        }
    }
}
