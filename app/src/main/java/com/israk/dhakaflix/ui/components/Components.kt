@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.israk.dhakaflix.ui.components

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.basicMarquee

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import com.israk.dhakaflix.ui.theme.LocalStrings
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.foundation.gestures.animateScrollBy
import kotlinx.coroutines.launch
import androidx.compose.ui.input.key.type
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clipToBounds
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
    private var id: String? = null
    private var at = 0L
    /** Only restores right after returning from a page (stale ids must never steal focus). */
    var restoreId: String?
        get() = if (android.os.SystemClock.elapsedRealtime() - at < 120_000) id else null
        set(v) { id = v; at = android.os.SystemClock.elapsedRealtime() }
}

fun ItemEntity.posterUrl(): String? = TmdbImages.url(posterPath, "w342") ?: localPoster
fun ItemEntity.backdropUrl(large: Boolean): String? =
    TmdbImages.url(backdropPath, if (large) "w1280" else "w780") ?: TmdbImages.url(posterPath, "w780") ?: localPoster

/**
 * Apple-TV style focus container: springs up in scale, gains an accent rim and lifts above neighbours.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FocusFrame(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    focusedScale: Float = 1.06f,
    focusRequester: FocusRequester? = null,
    onFocusChange: (Boolean) -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    if (!LocalIsTv.current) {
        // phone: touch never focuses a card, so skip the focus animations, rim and layer.
        // Every card that scrolls into view is then much cheaper to build -> smoother scrolling.
        val dragged = remember { booleanArrayOf(false) }
        val tap = { if (!dragged[0]) onClick() }
        Box(
            modifier
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .trackDrag(dragged)
                .clip(shape)
                .then(
                    if (onLongClick == null) Modifier.clickable(onClick = tap)
                    else Modifier.combinedClickable(onClick = tap, onLongClick = onLongClick)
                ),
        ) { content(false) }
        return
    }
    var focused by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    // short tween (no spring overshoot) keeps fast D-pad scrolling calm
    val scale by animateFloatAsState(
        if (focused) focusedScale else 1f,
        androidx.compose.animation.core.tween(170), label = "scale",
    )
    val accent = LocalAccent.current
    val rim by animateColorAsState(if (focused) accent else Color.Transparent, label = "rim")
    Box(
        modifier
            .zIndex(if (focused) 2f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                if (focused != it.isFocused) {
                    focused = it.isFocused
                    onFocusChange(it.isFocused)
                }
            }
            // hold OK on the remote = long press (Android repeats the key-down while it's held)
            .then(if (onLongClick == null) Modifier else Modifier.onPreviewKeyEvent { e ->
                val k = e.nativeKeyEvent
                val ok = k.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || k.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                    k.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                when {
                    !ok -> false
                    k.action == android.view.KeyEvent.ACTION_DOWN && k.repeatCount == 0 -> { longFired = false; false }
                    k.action == android.view.KeyEvent.ACTION_DOWN && k.repeatCount > 0 -> {
                        if (!longFired) { longFired = true; onLongClick() }
                        true
                    }
                    k.action == android.view.KeyEvent.ACTION_UP && longFired -> { longFired = false; true }
                    else -> false
                }
            })
            .border(2.5.dp, rim, shape)
            .clip(shape)
            .then(
                if (onLongClick == null) Modifier.clickable(onClick = onClick)
                else Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
            ),
    ) { content(focused) }
}

/**
 * One-line title that slowly scrolls when it doesn't fit and [active] (the focused card).
 * Home-made instead of Modifier.basicMarquee: the animation is a plain coroutine that stops the
 * moment the card leaves the row, and the movement is applied at placement time, never in a layer.
 */
@Composable
fun MarqueeText(
    text: String,
    active: Boolean,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    fontWeight: FontWeight? = null,
    modifier: Modifier = Modifier,
) {
    if (!active) {
        Text(text, modifier, maxLines = 1, overflow = TextOverflow.Ellipsis, style = style, color = color, fontWeight = fontWeight)
        return
    }
    var boxW by remember { mutableIntStateOf(0) }
    var textW by remember { mutableIntStateOf(0) }
    val shift = remember(text) { androidx.compose.animation.core.Animatable(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(text, boxW, textW) {
        shift.snapTo(0f)
        val over = textW - boxW
        if (boxW == 0 || over <= 0) return@LaunchedEffect
        val pxPerSec = with(density) { 40.dp.toPx() }
        while (true) {
            kotlinx.coroutines.delay(900)
            shift.animateTo(-over.toFloat(), androidx.compose.animation.core.tween(((over / pxPerSec) * 1000).toInt().coerceAtLeast(600), easing = androidx.compose.animation.core.LinearEasing))
            kotlinx.coroutines.delay(1200)
            shift.snapTo(0f)
        }
    }
    Box(modifier.fillMaxWidth().clipToBounds().onSizeChanged { boxW = it.width }) {
        Text(
            text, maxLines = 1, softWrap = false, style = style, color = color, fontWeight = fontWeight,
            modifier = Modifier
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .onSizeChanged { textW = it.width }
                .offset { androidx.compose.ui.unit.IntOffset(shift.value.toInt(), 0) },
        )
    }
}

/** Followed series with new episodes (provided once at the root). */
val LocalNewEpisodes = androidx.compose.runtime.compositionLocalOf<Set<String>> { emptySet() }

/** TMDB title logo (like Apple TV / Netflix) with the plain title as fallback. */
@Composable
fun TitleOrLogo(item: ItemEntity, maxHeight: Dp, style: androidx.compose.ui.text.TextStyle, maxLines: Int = 2) {
    // titles always stay in English (logo or text), whatever the app language
    var logo by remember(item.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(item.id) { logo = com.israk.dhakaflix.DhakaFlixApp.instance.library.discovery.logoUrl(item) }
    val title = item.displayTitle
    val l = logo
    if (l != null) {
        coil.compose.AsyncImage(
            model = l, contentDescription = title,
            // phone: keep big logos inside the screen width
            modifier = if (LocalIsTv.current) Modifier.heightIn(max = maxHeight).widthIn(max = 440.dp)
            else Modifier.heightIn(max = minOf(maxHeight, 84.dp)).fillMaxWidth(0.72f),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit, alignment = Alignment.CenterStart,
        )
    } else {
        Text(title, style = style, color = Color.White, fontWeight = FontWeight.Bold, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

/** Netflix-style "Top 10" card: a huge outlined rank number tucked behind the poster. */
@Composable
fun Top10Card(rank: Int, item: ItemEntity, width: Dp, onClick: () -> Unit, onFocused: () -> Unit, watched: Boolean = false) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val numSize = with(density) { (width * 1.05f).toSp() }
    Box(Modifier.width(width * (if (rank >= 10) 1.75f else 1.45f))) {
        Text(
            "$rank",
            Modifier.align(Alignment.TopStart).padding(top = width * 0.42f),
            style = androidx.compose.ui.text.TextStyle(
                fontSize = numSize, fontWeight = FontWeight.Black, letterSpacing = (-4).sp,
                color = Color.White.copy(alpha = .9f),
                drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = with(density) { 3.dp.toPx() }),
            ),
        )
        PosterCard(item, width, onClick = onClick, modifier = Modifier.align(Alignment.TopEnd), onFocused = onFocused, watched = watched)
    }
}

/** Poster width for a row of [available] width, honouring Settings → Posters per row (0 = auto). */
@Composable
fun posterWidth(available: Dp): Dp {
    val n by com.israk.dhakaflix.DhakaFlixApp.instance.settings.postersPerRow.collectAsState()
    val isTv = LocalIsTv.current
    if (n <= 0) return if (isTv) 112.dp else 104.dp
    val edge = LocalEdge.current
    val gap = if (isTv) 16.dp else 12.dp
    // a little of the next poster peeks in, so it's obvious the row scrolls
    return ((available - edge - gap * n) / (n + 0.25f)).coerceIn(60.dp, 260.dp)
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
    menu: CardMenu = CardMenu.DEFAULT,
) {
    val fr = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    if (showMenu) ItemMenu(item, menu) { showMenu = false }
    // only the one card that should get focus back pays for an effect (not every card in every row)
    if (LocalIsTv.current && FocusMemory.restoreId == item.id) {
        LaunchedEffect(item.id) {
            FocusMemory.restoreId = null
            runCatching { fr.requestFocus() }
        }
    }
    Column(modifier.width(width)) {
        FocusFrame(
            onClick = { FocusMemory.restoreId = item.id; onClick() },
            focusRequester = fr,
            onFocusChange = { focused = it; if (it) onFocused() },
            onLongClick = if (menu == CardMenu.NONE) null else ({ showMenu = true }),
        ) {
            PosterImage(item, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
            if (item.id in LocalNewEpisodes.current) {
                Text(
                    LocalStrings.current.newBadge,
                    Modifier.align(Alignment.BottomStart).padding(6.dp)
                        .background(LocalAccent.current, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                )
            }
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
            // Apple-TV style: on TV the title only appears under the focused poster. Unfocused cards
            // keep the space but build no text at all (fast left/right scrolling has far less to do).
            val isTv = LocalIsTv.current
            Spacer(Modifier.height(8.dp))
            if (isTv && !focused) {
                Spacer(Modifier.height(40.dp))
            } else {
                // fixed height on TV so the row never jumps when focus moves
                Column(if (isTv) Modifier.height(40.dp) else Modifier) {
                    MarqueeText(
                        item.displayTitle, active = focused,
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
    }
}

@Composable
fun PosterImage(item: ItemEntity, modifier: Modifier) {
    val ctx = LocalContext.current
    val url = item.posterUrl()
    var loaded by remember(url) { mutableStateOf(false) }
    Box(
        if (loaded) modifier else modifier.background(Brush.linearGradient(listOf(Surface2, Color(0xFF0E1630)))),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) Text(
            item.displayTitle, Modifier.padding(10.dp), color = Color.White.copy(alpha = .75f),
            style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 4,
        )
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(url).size(240, 360).build(),
                contentDescription = item.displayTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { loaded = true },
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
    var showMenu by remember { mutableStateOf(false) }
    if (showMenu) ItemMenu(item, CardMenu.CONTINUE) { showMenu = false }
    val memKey = "cw:" + item.id
    val tvCard = LocalIsTv.current
    LaunchedEffect(item.id) {
        if (tvCard && FocusMemory.restoreId == memKey) {
            FocusMemory.restoreId = null
            runCatching { fr.requestFocus() }
        }
    }
    Column(Modifier.width(width)) {
        FocusFrame(
            onClick = { FocusMemory.restoreId = memKey; onClick() },
            focusRequester = fr,
            onFocusChange = { focused = it; if (it) onFocused() },
            onLongClick = { showMenu = true },
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Surface2)) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(item.backdropUrl(false)).size(440, 248).build(),
                    contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = .85f))
                    )
                )
                Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                    MarqueeText(item.displayTitle, active = focused, style = MaterialTheme.typography.titleSmall, color = Color.White, fontWeight = FontWeight.SemiBold)
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

/** Explicit D-pad handling for a row inside a vertical list (TV). */
class RowNav(val focusRequester: FocusRequester, val onUp: () -> Unit, val onDown: () -> Unit)

@Composable
fun <T> MediaRow(
    title: String,
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    nav: RowNav? = null,
    itemContent: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) return
    val edge = LocalEdge.current
    var lastIdx by remember { mutableIntStateOf(0) }
    Column(modifier.fillMaxWidth()) {
        Text(
            title, Modifier.padding(start = edge, bottom = 2.dp),
            style = MaterialTheme.typography.titleMedium, color = Color.White,
        )
        val leftNav = com.israk.dhakaflix.DhakaFlixApp.instance.settings.navPosition.collectAsState().value == "left"
        PivotScroll(enabled = LocalIsTv.current, pivot = edge) {
        LazyRow(
            modifier = Modifier.onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionDown -> if (nav != null) { nav.onDown(); true } else false
                    Key.DirectionUp -> if (nav != null) { nav.onUp(); true } else false
                    // the row ends here: don't let focus escape into the top menu
                    Key.DirectionRight -> lastIdx >= items.lastIndex
                    Key.DirectionLeft -> lastIdx <= 0 && !leftNav
                    else -> false
                }
            },
            contentPadding = PaddingValues(horizontal = edge, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(if (LocalIsTv.current) 16.dp else 12.dp),
        ) {
            itemsIndexed(items, key = { _, it -> key(it) }) { i, it ->
                // derived so only the old/new "last focused" items recompose, not the whole row
                val isLast by remember(i) { androidx.compose.runtime.derivedStateOf { i == lastIdx.coerceAtMost(items.lastIndex) } }
                Box(
                    Modifier
                        .then(if (nav != null && isLast) Modifier.focusRequester(nav.focusRequester) else Modifier)
                        .onFocusChanged { f -> if (f.hasFocus && lastIdx != i) lastIdx = i }
                ) { itemContent(it) }
            }
        }
        }
    }
}

/**
 * TV rows scroll like Apple TV: the focused card glides to a fixed spot near the left edge on every
 * move, instead of the row sitting still and then jumping when focus reaches the screen edge.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PivotScroll(enabled: Boolean, pivot: Dp, content: @Composable () -> Unit) {
    if (!enabled) { content(); return }
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { pivot.toPx() }
    val spec = remember(px) {
        object : androidx.compose.foundation.gestures.BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - px
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides spec) {
        content()
    }
}

/** Frosted "liquid glass" surface (blur-free so it runs smoothly on low-end TV sticks). */
fun Modifier.glass(shape: Shape = RoundedCornerShape(18.dp), tint: Float = 0.10f): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = tint + 0.06f), Color.White.copy(alpha = tint * 0.45f))))
    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0.05f))), shape)

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
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, spring(dampingRatio = 0.75f), label = "pill")
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
            .then(
                if (!focused && !primary) Modifier.border(
                    1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = .30f), Color.White.copy(alpha = .05f))), RoundedCornerShape(50),
                ) else Modifier
            )
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
    firstItemModifier: Modifier = Modifier,
) {
    val edge = LocalEdge.current
    LazyRow(
        modifier,
        contentPadding = contentPadding ?: PaddingValues(horizontal = edge, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(options.size) { i ->
            val o = options[i]
            PillButton(
                label(o), onClick = { onSelect(o) }, selected = o == selected, compact = true,
                modifier = if (i == 0) firstItemModifier else Modifier,
            )
        }
    }
}

@Composable
fun MetaLine(item: ItemEntity, extra: List<String> = emptyList()) {
    val bn = isDhakaiya()
    val parts = buildList {
        item.displayYear?.let { add(if (bn) Bangla.digits(it.toString()) else it.toString()) }
        item.runtime?.takeIf { it > 0 }?.let { add(if (bn) Bangla.runtime(it) else "${it / 60}h ${it % 60}m".removePrefix("0h ")) }
        item.quality?.let { add(if (bn) Bangla.digits(it) else it) }
        item.tags?.split(',')?.filter { it.contains("Audio", true) }?.forEach {
            add(if (bn && it.trim().equals("Dual Audio", true)) "ডুয়াল অডিও" else it)
        }
        addAll(if (bn) extra.map { Bangla.genre(it) } else extra)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        item.rating?.takeIf { it > 0 }?.let {
            Icon(Icons.Filled.Star, null, tint = Color(0xFFFFC53D), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (bn) Bangla.digits(fmtRating(it)) else fmtRating(it), color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
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

/**
 * TV safety net: if the D-pad can't find anything up/down (item not composed yet),
 * scroll the list a bit and try again, so long lists and grids never get "stuck".
 */
@Composable
fun Modifier.dpadScrollFallback(state: androidx.compose.foundation.gestures.ScrollableState): Modifier {
    val fm = androidx.compose.ui.platform.LocalFocusManager.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return this.then(Modifier.onKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
        val down = e.key == Key.DirectionDown
        val up = e.key == Key.DirectionUp
        if (!down && !up) return@onKeyEvent false
        val dir = if (down) androidx.compose.ui.focus.FocusDirection.Down else androidx.compose.ui.focus.FocusDirection.Up
        if (fm.moveFocus(dir)) return@onKeyEvent true
        scope.launch {
            state.animateScrollBy(if (down) 360f else -360f)
            fm.moveFocus(dir)
        }
        true
    })
}


/** What the hold-OK menu offers for a card. */
enum class CardMenu { NONE, DEFAULT, LIBRARY, CONTINUE }

/** Hold-OK / long-press menu: mark watched + add to / remove from My Library. */
@Composable
fun ItemMenu(item: ItemEntity, mode: CardMenu, onDismiss: () -> Unit) {
    val app = com.israk.dhakaflix.DhakaFlixApp.instance
    val repo = app.library
    val s = com.israk.dhakaflix.ui.theme.LocalStrings.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val user by remember(item.id) { app.db.user().observe(item.id) }.collectAsState(initial = null)
    val watched = user?.watched == true
    val inList = user?.inWatchlist == true
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { first.requestFocus() } }

    val actions: List<Pair<String, suspend () -> Unit>> = buildList {
        add((if (watched) s.markUnwatched else s.markWatched) to suspend { repo.setWatched(item.id, !watched) })
        when (mode) {
            CardMenu.LIBRARY -> add(s.removeFromLibrary to suspend { repo.removeFromLibrary(item.id) })
            CardMenu.CONTINUE -> add(s.removeFromContinue to suspend { repo.removeFromContinue(item.id) })
            else -> add((if (inList) s.removeFromLibrary else s.addToLibrary) to suspend { repo.setInWatchlist(item.id, !inList) })
        }
    }
    // The OK button is still held when the menu opens: ignore its release, only a new press counts.
    var armed by remember { mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .onPreviewKeyEvent { e ->
                    val k = e.nativeKeyEvent
                    if (k.action == android.view.KeyEvent.ACTION_DOWN && k.repeatCount == 0) armed = true
                    !armed // swallow everything (the held OK repeats and its release) until a fresh press
                }
                .width(380.dp).glass(RoundedCornerShape(24.dp), tint = .12f)
                .background(com.israk.dhakaflix.ui.theme.Bg.copy(alpha = .85f)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PosterImage(item, Modifier.width(54.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(item.displayTitle, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    item.displayYear?.let { Text(it.toString(), color = TextDim, style = MaterialTheme.typography.labelMedium) }
                }
            }
            Spacer(Modifier.height(6.dp))
            actions.forEachIndexed { i, (label, act) ->
                FocusFrame(
                    onClick = { scope.launch { act(); onDismiss() } },
                    focusedScale = 1.03f, shape = RoundedCornerShape(14.dp),
                    focusRequester = if (i == 0) first else null,
                    modifier = Modifier.fillMaxWidth(),
                ) { f ->
                    Text(
                        label,
                        Modifier.fillMaxWidth().background(if (f) Color.White else Color.White.copy(alpha = .07f)).padding(horizontal = 16.dp, vertical = 13.dp),
                        color = if (f) Color.Black else Color.White, style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

/**
 * Phone: never scroll a list just because something inside it got focus. On touch screens a card that
 * takes focus (e.g. coming back from a page) made the list jump back to it while the finger was
 * scrolling — scrolling up felt stuck. Only the finger moves a phone list.
 */
val NoAutoScrollSpec = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/** [spec] on TV; on phone keep whatever is provided around (the no-auto-scroll spec). */
@Composable
fun tvScrollSpec(spec: androidx.compose.foundation.gestures.BringIntoViewSpec): androidx.compose.foundation.gestures.BringIntoViewSpec =
    if (LocalIsTv.current) spec else androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current

/** Scrolls only when the focused thing is (partly) off screen — no pivot fighting. */
val OnlyIfNeededSpec = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val end = offset + size
        return when {
            offset >= 0f && end <= containerSize -> 0f
            offset < 0f || size > containerSize -> offset
            else -> end - containerSize
        }
    }
}

/**
 * Calm vertical scrolling for TV grids/lists (same feel as Home): moving left/right never scrolls,
 * and the page only moves when the focused card gets close to the top menu or the bottom edge,
 * leaving a little of the next line visible. Android TV's default instead re-centres to a 30% pivot
 * on every move, which made Movies / Series / Search jitter while switching posters.
 */
@Composable
fun CalmVerticalScroll(topMargin: Dp, bottomMargin: Dp = 56.dp, content: @Composable () -> Unit) {
    if (!LocalIsTv.current) { content(); return }
    val d = androidx.compose.ui.platform.LocalDensity.current
    val top = with(d) { topMargin.toPx() }
    val bottom = with(d) { bottomMargin.toPx() }
    val spec = remember(top, bottom) {
        object : androidx.compose.foundation.gestures.BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val room = containerSize - top - bottom
                val end = offset + size
                return when {
                    size > room -> offset - top
                    offset < top -> offset - top
                    end > containerSize - bottom -> end - (containerSize - bottom)
                    else -> 0f
                }
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides spec) {
        content()
    }
}


/**
 * Touch: remembers whether the finger moved further than a tap allows, so the card can ignore the click.
 * It only watches — it never consumes anything. (The old version swallowed the finger's release, and the
 * list under it then lost its fling: lifting the finger stopped the scroll dead, so scrolling felt stuck.)
 */
fun Modifier.trackDrag(dragged: BooleanArray): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
        dragged[0] = false
        while (true) {
            val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
            if (!dragged[0] && (c.position - down.position).getDistance() > viewConfiguration.touchSlop) dragged[0] = true
            if (!c.pressed) break
        }
    }
}
