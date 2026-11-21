package com.israk.dhakaflix.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.israk.dhakaflix.DhakaFlixApp
import com.israk.dhakaflix.data.db.ItemEntity
import com.israk.dhakaflix.data.tmdb.TmdbImages
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Cross-screen signals from the activity (key presses, the remote's search button). */
object AppEvents {
    @Volatile var lastInput = SystemClock.elapsedRealtime()
    /** Bumped when the remote's SEARCH / mic key is pressed. */
    val voiceSearch = MutableStateFlow(0)
    var voiceHandled = 0
    var navHandled = 0
    val screensaverOn = MutableStateFlow(false)
}

/** Slow Ken-Burns slideshow of backdrops with the time, after a few idle minutes. */
@Composable
fun Screensaver() {
    val app = DhakaFlixApp.instance
    val minutes by app.settings.screensaver.collectAsState()
    val on by AppEvents.screensaverOn.collectAsState()
    LaunchedEffect(minutes) {
        AppEvents.lastInput = SystemClock.elapsedRealtime()
        while (minutes > 0) {
            delay(5_000)
            if (!AppEvents.screensaverOn.value && SystemClock.elapsedRealtime() - AppEvents.lastInput > minutes * 60_000L) {
                AppEvents.screensaverOn.value = true
            }
        }
    }
    AnimatedVisibility(on, enter = fadeIn(tween(1500)), exit = fadeOut(tween(400))) {
        var items by remember { mutableStateOf<List<ItemEntity>>(emptyList()) }
        LaunchedEffect(Unit) {
            items = app.db.items().latest(null, 120).first().filter { it.backdropPath != null }.shuffled().take(40)
        }
        var idx by remember { mutableIntStateOf(0) }
        LaunchedEffect(items) {
            while (items.isNotEmpty()) { delay(12_000); idx = (idx + 1) % items.size }
        }
        var clock by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            val f = java.text.SimpleDateFormat("h:mm", java.util.Locale.getDefault())
            val d = java.text.SimpleDateFormat("EEEE, d MMMM", java.util.Locale.getDefault())
            while (true) { clock = f.format(java.util.Date()) + "\n" + d.format(java.util.Date()); delay(20_000) }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val cur = items.getOrNull(idx)
            Crossfade(cur, animationSpec = tween(2000), label = "saver") { it ->
                if (it != null) KenBurns(it)
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = .75f))))
            val lines = clock.split("\n")
            Column(Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 56.dp), horizontalAlignment = Alignment.End) {
                Text(lines.getOrElse(0) { "" }, color = Color.White, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Light)
                Text(lines.getOrElse(1) { "" }, color = Color.White.copy(alpha = .75f), style = MaterialTheme.typography.titleMedium)
            }
            if (cur != null) Column(Modifier.align(Alignment.BottomStart).padding(start = 56.dp, bottom = 48.dp).widthIn(max = 620.dp)) {
                Text(cur.displayTitle, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(listOfNotNull(cur.displayYear?.toString(), cur.genreList.take(2).joinToString(", ").ifBlank { null }).joinToString("  ·  "),
                    color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun KenBurns(item: ItemEntity) {
    val ctx = LocalContext.current
    val z = remember(item.id) { Animatable(1f) }
    LaunchedEffect(item.id) { z.animateTo(1.12f, tween(14_000, easing = LinearEasing)) }
    val dir = remember(item.id) { if (item.id.hashCode() % 2 == 0) 1f else -1f }
    AsyncImage(
        model = ImageRequest.Builder(ctx).data(TmdbImages.url(item.backdropPath, "w1280")).build(),
        contentDescription = null, contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().graphicsLayer {
            scaleX = z.value; scaleY = z.value
            translationX = dir * (z.value - 1f) * size.width * 0.35f
        },
    )
}
