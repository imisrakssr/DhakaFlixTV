package com.israk.dhakaflix.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.israk.dhakaflix.data.db.ItemEntity
import com.israk.dhakaflix.data.tmdb.TmdbImages
import com.israk.dhakaflix.ui.theme.LocalAccent
import com.israk.dhakaflix.ui.theme.LocalEdge
import com.israk.dhakaflix.ui.theme.LocalIsTv
import com.israk.dhakaflix.ui.theme.Surface2
import com.israk.dhakaflix.ui.theme.TextDim
import java.util.Locale

/** Remembers which card opened a detail page so focus can return to it (TV back navigation). */
object FocusMemory {
    var restoreId: String? = null
}

fun ItemEntity.posterUrl(): String? = TmdbImages.url(posterPath, "w342") ?: localPoster
fun ItemEntity.backdropUrl(large: Boolean): String? =
    TmdbImages.url(backdropPath, if (large) "w1280" else "w780") ?: TmdbImages.url(posterPath, "w780") ?: localPoster

/**
 * Apple-TV style focus container: springs up in scale, gains an accent rim and lifts above neighbours.
 */
@Composable
fun FocusFrame(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    focusedScale: Float = 1.08f,
    focusRequester: FocusRequester? = null,
    onFocusChange: (Boolean) -> Unit = {},
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (focused) focusedScale else 1f,
        spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow), label = "scale",
    )
    val accent = LocalAccent.current
    val rim by animateColorAsState(if (focused) accent else Color.Transparent, label = "rim")
    Box(
        modifier
            .zIndex(if (focused) 2f else 0f)
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                shadowElevation = if (focused) 28f else 0f
                this.shape = shape
                clip = false
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                if (focused != it.isFocused) {
                    focused = it.isFocused
                    onFocusChange(it.isFocused)
                }
            }
            .border(2.5.dp, rim, shape)
            .clip(shape)
            .clickable(onClick = onClick),
    ) { content(focused) }
}

@Composable
fun PosterCard(
    item: ItemEntity,
    width: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    watched: Boolean = false,
    onFocused: () -> Unit = {},
    showTitle: Boolean = true,
) {
    val fr = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(item.id) {
        if (FocusMemory.restoreId == item.id) {
            FocusMemory.restoreId = null
            runCatching { fr.requestFocus() }
        }
    }
    Column(modifier.width(width)) {
        FocusFrame(
            onClick = { FocusMemory.restoreId = item.id; onClick() },
            focusRequester = fr,
            onFocusChange = { focused = it; if (it) onFocused() },
        ) {
            PosterImage(item, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
            if (watched) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp)
                        .background(LocalAccent.current, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(15.dp)) }
            }
            item.quality?.let {
                if (it == "1080p" || it == "2160p") {
                    Text(
                        if (it == "2160p") "4K" else "HD",
                        Modifier.align(Alignment.TopStart).padding(6.dp)
                            .background(Color.Black.copy(alpha = .65f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                        color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        if (showTitle) {
            Spacer(Modifier.height(8.dp))
            Text(
                item.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
                color = if (focused) Color.White else TextDim,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                listOfNotNull(item.displayYear?.toString(), item.rating?.takeIf { it > 0 }?.let { "★ " + fmtRating(it) })
                    .joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall, color = TextDim.copy(alpha = .7f), maxLines = 1,
            )
        }
    }
}

@Composable
fun PosterImage(item: ItemEntity, modifier: Modifier) {
    val ctx = LocalContext.current
    Box(
        modifier.background(
            Brush.linearGradient(listOf(Surface2, Color(0xFF0E1630)))
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            item.displayTitle, Modifier.padding(10.dp), color = Color.White.copy(alpha = .75f),
            style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 4,
        )
        val url = item.posterUrl()
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(url).size(342, 513).crossfade(true).build(),
                contentDescription = item.displayTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Landscape card used for Continue Watching. */
@Composable
fun LandscapeCard(
    item: ItemEntity,
    label: String?,
    progress: Float,
    width: Dp,
    onClick: () -> Unit,
    onFocused: () -> Unit = {},
) {
    val ctx = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    val memKey = "cw:" + item.id
    LaunchedEffect(item.id) {
        if (FocusMemory.restoreId == memKey) {
            FocusMemory.restoreId = null
            runCatching { fr.requestFocus() }
        }
    }
    Column(Modifier.width(width)) {
        FocusFrame(
            onClick = { FocusMemory.restoreId = memKey; onClick() },
            focusRequester = fr,
            onFocusChange = { focused = it; if (it) onFocused() },
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Surface2)) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(item.backdropUrl(false)).size(500, 281).crossfade(true).build(),
                    contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = .85f))
                    )
                )
                Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                    Text(item.displayTitle, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    if (label != null) Text(label, color = TextDim, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    Spacer(Modifier.height(6.dp))
                    ProgressLine(progress)
                }
            }
        }
    }
}

@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .25f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(LocalAccent.current))
    }
}

@Composable
fun <T> MediaRow(
    title: String,
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    itemContent: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) return
    val edge = LocalEdge.current
    Column(modifier.fillMaxWidth()) {
        Text(
            title, Modifier.padding(start = edge, bottom = 2.dp),
            style = MaterialTheme.typography.titleMedium, color = Color.White,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = edge, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(if (LocalIsTv.current) 18.dp else 12.dp),
        ) {
            items(items, key = key) { itemContent(it) }
        }
    }
}

/** Rounded pill button: translucent at rest, solid white when focused (tvOS style). */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    compact: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val accent = LocalAccent.current
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, spring(dampingRatio = 0.7f), label = "pill")
    val bg by animateColorAsState(
        when {
            focused -> Color.White
            primary -> accent
            selected -> accent.copy(alpha = .38f)
            else -> Color.White.copy(alpha = .12f)
        }, label = "pillBg",
    )
    val fg = if (focused) Color.Black else Color.White
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 14.dp else 20.dp, vertical = if (compact) 7.dp else 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(if (compact) 16.dp else 20.dp))
            if (text.isNotEmpty()) Spacer(Modifier.width(8.dp))
        }
        if (text.isNotEmpty()) {
            Text(
                text, color = fg, maxLines = 1,
                style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Horizontal chip selector row. */
@Composable
fun <T> ChipRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues? = null,
) {
    val edge = LocalEdge.current
    LazyRow(
        modifier,
        contentPadding = contentPadding ?: PaddingValues(horizontal = edge, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(options.size) { i ->
            val o = options[i]
            PillButton(label(o), onClick = { onSelect(o) }, selected = o == selected, compact = true)
        }
    }
}

@Composable
fun MetaLine(item: ItemEntity, extra: List<String> = emptyList()) {
    val parts = buildList {
        item.displayYear?.let { add(it.toString()) }
        item.runtime?.takeIf { it > 0 }?.let { add("${it / 60}h ${it % 60}m".removePrefix("0h ")) }
        item.quality?.let { add(it) }
        item.tags?.split(',')?.filter { it.contains("Audio", true) }?.forEach { add(it) }
        addAll(extra)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        item.rating?.takeIf { it > 0 }?.let {
            Icon(Icons.Filled.Star, null, tint = Color(0xFFFFC53D), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(fmtRating(it), color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(12.dp))
        }
        Text(parts.joinToString("   ·   "), color = TextDim, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

fun fmtRating(r: Double): String = String.format(Locale.US, "%.1f", r)

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
}

@Composable
fun CenterMessage(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, color = TextDim, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            if (action != null) { Spacer(Modifier.height(18.dp)); action() }
        }
    }
}
