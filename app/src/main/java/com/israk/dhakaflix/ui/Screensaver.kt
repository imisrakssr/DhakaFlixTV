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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
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
    /** Bumped when the tab that is already open is picked again: the page scrolls back to the top. */
    val scrollTop = MutableStateFlow(0)
}

/** Runs [toTop] when the current tab is picked again in the menu. */
@Composable
fun OnTabReselect(toTop: suspend () -> Unit) {
    val tick by AppEvents.scrollTop.collectAsState()
    val seen = androidx.compose.runtime.remember { intArrayOf(tick) }
    LaunchedEffect(tick) {
        if (tick != seen[0]) { seen[0] = tick; runCatching { toTop() } }
    }
}

/** Info shown over a screensaver slide: title logo, IMDb / Rotten Tomatoes, run time. */
private data class SaverInfo(val item: ItemEntity, val logo: String?, val ratings: List<com.israk.dhakaflix.data.accounts.ExtRating>)

/** Slideshow of backdrops (each pans slowly to the right until the next one) with the time, after a few idle minutes. */
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
        val ctx = LocalContext.current
        var items by remember { mutableStateOf<List<ItemEntity>>(emptyList()) }
        LaunchedEffect(Unit) {
            // keep trying until the library has something to show (it may still be filling)
            while (items.isEmpty()) {
                items = runCatching { app.db.items().saverSlides(40) }.getOrDefault(emptyList())
                    .let { l -> l.filter { it.backdropPath != null }.ifEmpty { l } }.shuffled()
                if (items.isEmpty()) delay(30_000)
            }
        }
        var idx by remember { mutableIntStateOf(0) }
        // logo, ratings and run time are fetched for the current slide and the next one, so they never pop in late
        val info = remember { androidx.compose.runtime.mutableStateMapOf<String, SaverInfo>() }
        suspend fun load(it: ItemEntity) {
            if (info.containsKey(it.id)) return
            runCatching { app.library.ensureDetails(it.id) }
            val full = app.db.items().get(it.id) ?: it
            val logo = runCatching { app.library.discovery.logoUrl(full) }.getOrNull()
            if (logo != null) coil.Coil.imageLoader(ctx).execute(ImageRequest.Builder(ctx).data(logo).build())
            coil.Coil.imageLoader(ctx).execute(ImageRequest.Builder(ctx).data(slideUrl(full)).build())
            val ratings = full.tmdbId?.let { id ->
                runCatching { app.library.mdblist.ratings(id, full.isSeries) }.getOrDefault(emptyList())
            }.orEmpty().filter { r -> r.source == "imdb" || r.source == "tomatoes" }
            info[it.id] = SaverInfo(full, logo, ratings)
        }
        LaunchedEffect(items) {
            if (items.isEmpty()) return@LaunchedEffect
            load(items[0])
            while (true) {
                items.getOrNull((idx + 1) % items.size)?.let { load(it) }
                delay(12_000)
                idx = (idx + 1) % items.size
            }
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
                if (it != null) SlideRight(it)
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = .8f))))
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = .45f), 0.55f to Color.Transparent)))
            val lines = clock.split("\n")
            Column(Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 56.dp), horizontalAlignment = Alignment.End) {
                Text(lines.getOrElse(0) { "" }, color = Color.White, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Light)
                Text(lines.getOrElse(1) { "" }, color = Color.White.copy(alpha = .75f), style = MaterialTheme.typography.titleMedium)
            }
            Crossfade(cur?.let { info[it.id] ?: SaverInfo(it, null, emptyList()) }, animationSpec = tween(1200), label = "saverInfo",
                modifier = Modifier.align(Alignment.BottomStart)) { si ->
                if (si != null) SaverDetails(si)
            }
        }
    }
}

@Composable
private fun SaverDetails(si: SaverInfo) {
    val it = si.item
    Column(Modifier.padding(start = 56.dp, bottom = 48.dp).widthIn(max = 620.dp)) {
        if (si.logo != null) {
            AsyncImage(
                model = si.logo, contentDescription = it.displayTitle, contentScale = ContentScale.Fit,
                alignment = Alignment.BottomStart,
                modifier = Modifier.height(96.dp).widthIn(max = 420.dp),
            )
        } else {
            Text(it.displayTitle, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            si.ratings.forEach { r ->
                val tint = if (r.source == "imdb") Color(0xFFF5C518) else Color(0xFFFA320A)
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = .55f)).padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(r.label, color = tint, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                    Spacer(Modifier.width(6.dp))
                    Text(r.value, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                Spacer(Modifier.width(10.dp))
            }
            val meta = listOfNotNull(
                it.displayYear?.toString(),
                runTime(it),
                it.genreList.take(2).joinToString(", ").ifBlank { null },
            ).joinToString("  ·  ")
            Text(meta, color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "2h 14m" for a film, "45m / episode" for a series (TMDB gives series an episode length). */
private fun runTime(it: ItemEntity): String? {
    val m = it.runtime?.takeIf { r -> r > 0 } ?: return null
    val t = if (m >= 60) "${m / 60}h ${m % 60}m".removeSuffix(" 0m") else "${m}m"
    return if (it.isSeries) "$t / episode" else t
}

/** The wide backdrop, or the poster when a title has none. */
private fun slideUrl(it: ItemEntity): String? =
    if (it.backdropPath != null) TmdbImages.url(it.backdropPath, "w1280") else TmdbImages.url(it.posterPath, "w780")

/** The backdrop, a little larger than the screen, drifting steadily to the right until the next slide. */
@Composable
private fun SlideRight(item: ItemEntity) {
    val ctx = LocalContext.current
    val p = remember(item.id) { Animatable(0f) }
    LaunchedEffect(item.id) { p.animateTo(1f, tween(14_500, easing = LinearEasing)) }
    AsyncImage(
        model = ImageRequest.Builder(ctx).data(slideUrl(item)).build(),
        contentDescription = null, contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().graphicsLayer {
            scaleX = 1.14f; scaleY = 1.14f
            // 14% larger leaves 7% spare on each side: travel from the left edge to the right one
            translationX = (p.value - .5f) * size.width * 0.13f
        },
    )
}
