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

    Box(
        Modifier.fillMaxSize().background(Bg),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(520.dp).graphicsLayer { alpha = glow.value * 0.9f; scaleX = 0.6f + glow.value * 0.5f; scaleY = scaleX }
                .background(Brush.radialGradient(listOf(Color(0x552E9BFF), Color(0x0022D3EE))))
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Image(
                painterResource(R.drawable.logo_mark), null,
                Modifier.size(120.dp).graphicsLayer {
                    scaleX = logoScale.value; scaleY = logoScale.value; alpha = logoAlpha.value
                },
            )
            Spacer(Modifier.height(20.dp))
            Image(
                painterResource(R.drawable.logo_wordmark), "DhakaFlix",
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
