@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.israk.dhakaflix.ui.home

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.israk.dhakaflix.DhakaFlixApp
import com.israk.dhakaflix.data.ContinueEntry
import com.israk.dhakaflix.data.RowKeys
import com.israk.dhakaflix.data.Source
import com.israk.dhakaflix.data.db.ItemEntity
import com.israk.dhakaflix.player.PlayerActivity
import com.israk.dhakaflix.ui.LocalEntryFocus
import com.israk.dhakaflix.ui.LocalTopInset
import com.israk.dhakaflix.ui.components.AppIcons
import com.israk.dhakaflix.ui.components.CenterMessage
import com.israk.dhakaflix.ui.components.LandscapeCard
import com.israk.dhakaflix.ui.components.MediaRow
import com.israk.dhakaflix.ui.components.MetaLine
import com.israk.dhakaflix.ui.components.PillButton
import com.israk.dhakaflix.ui.components.PosterCard
import com.israk.dhakaflix.ui.components.RowNav
import com.israk.dhakaflix.ui.components.backdropUrl
import com.israk.dhakaflix.ui.components.glass
import com.israk.dhakaflix.ui.theme.Bg
import com.israk.dhakaflix.ui.theme.LocalAccent
import com.israk.dhakaflix.ui.theme.LocalEdge
import com.israk.dhakaflix.ui.theme.LocalIsTv
import com.israk.dhakaflix.ui.theme.LocalStrings
import com.israk.dhakaflix.ui.theme.Strings
import com.israk.dhakaflix.ui.theme.TextDim
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun rowTitle(key: String, s: Strings, sources: List<Source>): String =
    DhakaFlixApp.instance.settings.rowNames.value[key] ?: defaultRowTitle(key, s, sources)

fun defaultRowTitle(key: String, s: Strings, sources: List<Source>): String = when {
    key == RowKeys.CONTINUE -> s.continueWatching
    key == RowKeys.LATEST -> s.latestUploads
    key == RowKeys.LATEST_MOVIES -> s.latestMovies
    key == RowKeys.LATEST_SERIES -> s.latestSeries
    key == RowKeys.WATCHLIST -> s.myWatchlist
    key == RowKeys.TOP_RATED -> s.topRated
    key == RowKeys.NEW_EPISODES -> s.newEpisodes
    key == RowKeys.TOP10 -> s.top10
    key == RowKeys.BECAUSE -> s.becauseYouWatched.format("…")
    key.startsWith("genre:") -> key.removePrefix("genre:")
    key.startsWith("src:") -> s.latestIn.format(sources.firstOrNull { it.id == key.removePrefix("src:") }?.name ?: "")
    else -> key
}

/** One visible home row: either continue-watching entries or titles. */
private class HomeRowData(val key: String, val title: String, val cw: List<ContinueEntry>, val items: List<ItemEntity>)

@Composable
fun HomeScreen(onOpen: (String) -> Unit) {
    val app = DhakaFlixApp.instance
    val repo = app.library
    val s = LocalStrings.current
    val isTv = LocalIsTv.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    val rows by app.settings.homeRows.collectAsState()
    val sources by app.settings.sources.collectAsState()
    val rowNames by app.settings.rowNames.collectAsState()
    val count by remember { repo.itemCount }.collectAsState(initial = -1)
    val sync by repo.sync.collectAsState()
    val watchedIds by remember { app.db.user().watchedIds() }.collectAsState(initial = emptyList())
    val watchedSet = remember(watchedIds) { watchedIds.toHashSet() }
    val cw by remember { repo.continueWatching() }.collectAsState(initial = emptyList())
    val featured by remember { repo.featured() }.collectAsState(initial = emptyList())
    val because by repo.discovery.because.collectAsState()
    val tmdbStatus by repo.tmdbStatus.collectAsState()
    LaunchedEffect(tmdbStatus, count > 0) { if (count > 0) repo.discovery.refresh() }

    // collect every enabled row up-front so empty rows can be skipped by the D-pad
    val enabledRows = rows.filter { it.enabled }
    val rowItems = HashMap<String, List<ItemEntity>>()
    for (r in enabledRows) {
        if (r.key == RowKeys.CONTINUE) continue
        key(r.key) {
            val v by remember { repo.rowFlow(r.key) }.collectAsState(initial = emptyList())
            rowItems[r.key] = v
        }
    }
    val visible = enabledRows.mapNotNull { r ->
        if (r.key == RowKeys.CONTINUE) {
            if (cw.isEmpty()) null else HomeRowData(r.key, rowNames[r.key] ?: s.continueWatching, cw, emptyList())
        } else {
            val items = rowItems[r.key].orEmpty()
            val title = rowNames[r.key] ?: if (r.key == RowKeys.BECAUSE) s.becauseYouWatched.format(because?.seedTitle ?: "…") else rowTitle(r.key, s, sources)
            if (items.isEmpty()) null else HomeRowData(r.key, title, emptyList(), items)
        }
    }

    if (count == 0) {
        when {
            sync.running -> ScanningState(s.scanning, s.scanProgress.format(sync.scannedFolders, sync.newItems))
            sync.allFailed -> CenterMessage(s.serverUnreachable) {
                PillButton(s.retry, onClick = { repo.startSync(true) }, icon = Icons.Filled.Refresh, primary = true)
            }
            else -> ScanningState(s.scanning, "")
        }
        return
    }

    var bannerIndex by remember { mutableIntStateOf(0) }
    // focus state lives in a holder that only the backdrop reads, so moving between
    // posters doesn't recompose the whole page (that caused the jitter)
    val focus = remember { HomeFocus() }
    val bannerItem = if (featured.isEmpty()) null else featured[bannerIndex % featured.size]
    LaunchedEffect(featured.size, bannerIndex, focus.inRows) {
        if (featured.size > 1 && !focus.inRows) {
            delay(9000)
            bannerIndex = (bannerIndex + 1) % featured.size
        }
    }

    val playContinue: (ContinueEntry) -> Unit = { e ->
        scope.launch {
            val (list, idx) = repo.playlistFromProgress(e.progress)
            PlayerActivity.start(ctx, list, idx, PlayerActivity.MODE_ASK)
        }
    }

    val listState = rememberLazyListState()
    com.israk.dhakaflix.ui.OnTabReselect { listState.animateScrollToItem(0) }
    com.israk.dhakaflix.ui.ReportScroll { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 140 }
    val bannerFr = LocalEntryFocus.current ?: remember { FocusRequester() }
    val rowFrs = remember { HashMap<String, FocusRequester>() }
    fun frFor(k: String) = rowFrs.getOrPut(k) { FocusRequester() }

    /** Eased scroll that lines an item up under the top inset (smoother than the default spring).
     *  Runs at user-input priority so the list's own bring-into-view can't cancel it half way
     *  (that left rows half under the screen edge with the next row not composed). */
    suspend fun smoothScrollTo(index: Int) {
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        if (info != null) {
            val target = info.offset - listState.layoutInfo.beforeContentPadding
            if (target != 0) runCatching {
                listState.scroll(androidx.compose.foundation.MutatePriority.UserInput) {
                    var done = 0f
                    androidx.compose.animation.core.animate(0f, target.toFloat(), animationSpec = tween(340, easing = FastOutSlowInEasing)) { v, _ ->
                        done += scrollBy(v - done)
                    }
                }
            }
        } else {
            listState.scrollToItem(index)
        }
    }

    val focusedRow = remember { arrayOf<String?>(null) }
    fun isComposed(key: String) = listState.layoutInfo.visibleItemsInfo.any { it.key == key }

    val navJob = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    fun goRow(i: Int, up: Boolean = false) {
        navJob[0]?.cancel()
        navJob[0] = scope.launch {
            if (i < 0) {
                if (featured.isNotEmpty()) runCatching { bannerFr.requestFocus() } else focusManager.moveFocus(FocusDirection.Up)
                smoothScrollTo(0)
                return@launch
            }
            if (i > visible.lastIndex) {
                // last row: make sure all of it (title + cards) is on screen
                android.util.Log.d("DfxNav", "goRow $i: end of list")
                smoothScrollTo(visible.lastIndex + 1)
                return@launch
            }
            val key = visible[i].key
            if (!isComposed(key)) {
                // the row is off screen and not composed yet: bring it in first, then focus it
                listState.scrollToItem(i + 1)
                for (f in 0 until 4) { if (isComposed(key)) break; androidx.compose.runtime.withFrameNanos { } }
            }
            runCatching { frFor(key).requestFocus() }
            androidx.compose.runtime.withFrameNanos { }
            if (focusedRow[0] != key) { runCatching { frFor(key).requestFocus() }; androidx.compose.runtime.withFrameNanos { } }
            if (focusedRow[0] != key) focusManager.moveFocus(if (up) FocusDirection.Up else FocusDirection.Down)
            val ok = focusedRow[0] == key
            android.util.Log.d("DfxNav", "goRow $i (${key}) focused=$ok")
            // glide the row under the menu
            smoothScrollTo(i + 1)
        }
    }

    val topInset = LocalTopInset.current

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val posterW = com.israk.dhakaflix.ui.components.posterWidth(maxWidth)
        val landW = if (com.israk.dhakaflix.DhakaFlixApp.instance.settings.postersPerRow.collectAsState().value > 0) posterW * 1.96f
            else if (isTv) 220.dp else 210.dp
        // phone: never taller than ~80% of the screen (landscape phones are only ~400dp high)
        val bannerH: Dp = if (isTv) maxHeight * 0.82f - topInset else minOf(460.dp, maxHeight * 0.8f)
        if (isTv) HomeBackdrop(focus, bannerItem)

        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides OnlyIfNeeded) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(top = if (isTv) topInset else 0.dp, bottom = 80.dp + com.israk.dhakaflix.ui.LocalBottomInset.current),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "banner") {
                FeaturedBanner(
                    item = bannerItem, count = featured.size, index = bannerIndex, height = bannerH,
                    playFr = bannerFr,
                    onPlay = { b -> onOpen("play:" + b.id) },
                    onInfo = { b -> onOpen(b.id) },
                    onFocus = {
                        focus.inRows = false
                        focusedRow[0] = null
                        // always show the whole banner when it has focus (no cut-off title/logo); the
                        // list's own bring-into-view runs right after focus, so correct after it
                        scope.launch {
                            delay(60)
                            if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0)
                                listState.animateScrollToItem(0)
                        }
                    },
                    onDown = { goRow(0) },
                    onNext = { if (featured.isNotEmpty()) bannerIndex = (bannerIndex + 1) % featured.size },
                )
            }
            itemsIndexed(visible, key = { _, r -> r.key }) { ri, row ->
                val nav = if (isTv) RowNav(frFor(row.key), onUp = { goRow(ri - 1, up = true) }, onDown = { goRow(ri + 1) }) else null
                if (row.key == RowKeys.CONTINUE) {
                    MediaRow(row.title, row.cw, key = { it.progress.fileUrl }, nav = nav, modifier = Modifier.onFocusChanged { if (it.hasFocus) focusedRow[0] = row.key }) { e ->
                        LandscapeCard(
                            item = e.item, label = e.progress.label, progress = e.progress.fraction, width = landW,
                            onClick = { playContinue(e) },
                            onFocused = { focus.inRows = true; focus.item = e.item },
                        )
                    }
                } else if (row.key == RowKeys.TOP10) {
                    MediaRow(row.title, row.items, key = { it.id }, nav = nav, modifier = Modifier.onFocusChanged { if (it.hasFocus) focusedRow[0] = row.key }) { item ->
                        com.israk.dhakaflix.ui.components.Top10Card(
                            rank = row.items.indexOf(item) + 1, item = item, width = posterW,
                            watched = item.id in watchedSet,
                            onClick = { onOpen(item.id) },
                            onFocused = { focus.inRows = true; focus.item = item },
                        )
                    }
                } else {
                    MediaRow(row.title, row.items, key = { it.id }, nav = nav, modifier = Modifier.onFocusChanged { if (it.hasFocus) focusedRow[0] = row.key }) { item ->
                        PosterCard(
                            item = item, width = posterW, watched = item.id in watchedSet,
                            onClick = { onOpen(item.id) },
                            onFocused = { focus.inRows = true; focus.item = item },
                        )
                    }
                }
            }
        }
        }
    }
}

/**
 * Android TV's default makes vertical lists keep the focused item ~30% from the top, which pushed the
 * banner up (cut-off logo) and fought the row glide below. Home positions rows itself, so the list
 * only scrolls when the focused thing is actually outside the screen.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private val OnlyIfNeeded = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val end = offset + size
        return when {
            offset >= 0f && end <= containerSize -> 0f
            offset < 0f || size > containerSize -> offset
            else -> end - containerSize
        }
    }
}

@Composable
private fun FeaturedBanner(
    item: ItemEntity?,
    count: Int,
    index: Int,
    height: Dp,
    playFr: FocusRequester,
    onPlay: (ItemEntity) -> Unit,
    onInfo: (ItemEntity) -> Unit,
    onFocus: () -> Unit,
    onDown: () -> Unit,
    onNext: () -> Unit,
) {
    val s = LocalStrings.current
    val isTv = LocalIsTv.current
    val edge = LocalEdge.current
    val ctx = LocalContext.current
    val accent = LocalAccent.current
    val app = DhakaFlixApp.instance
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxWidth().height(height)) {
        if (!isTv && item != null) {
            Crossfade(targetState = item.backdropUrl(false), animationSpec = tween(700), label = "phoneBanner") { url ->
                AsyncImage(ImageRequest.Builder(ctx).data(url).crossfade(true).build(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Transparent, 1f to Bg)))
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = edge, end = edge, bottom = 10.dp)) {
            AnimatedContent(
                targetState = item,
                contentKey = { it?.id },
                transitionSpec = { (fadeIn(tween(500)) + slideInVertically(tween(500)) { it / 12 }) togetherWith fadeOut(tween(250)) },
                label = "bannerInfo",
            ) { cur ->
                Column(Modifier.widthIn(max = 600.dp)) {
                    if (cur == null) {
                        val who by app.settings.userName.collectAsState()
                        val lang by app.settings.language.collectAsState()
                        // the default name is written in Bengali for Dhakaiya
                        val name = if (lang == "dhk" && who == "Israk") "ইশরাক" else who
                        Text(
                            if (name.isBlank()) s.greeting.replace(", %s", "") else s.greeting.format(name),
                            style = MaterialTheme.typography.headlineLarge, color = Color.White,
                        )
                        Spacer(Modifier.height(16.dp))
                        return@Column
                    }
                    Text(
                        (if (cur.isSeries) s.series else s.movies).uppercase() + "  ·  " + s.latestUploads,
                        Modifier.glass(RoundedCornerShape(50), tint = .08f).padding(horizontal = 12.dp, vertical = 4.dp),
                        color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(12.dp))
                    com.israk.dhakaflix.ui.components.TitleOrLogo(cur, maxHeight = 96.dp, style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(8.dp))
                    MetaLine(cur, extra = cur.genreList.take(3))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        com.israk.dhakaflix.ui.components.localOverview(cur) ?: "", style = MaterialTheme.typography.bodyMedium, color = TextDim,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(18.dp))
                }
            }
            // buttons stay outside AnimatedContent so focus survives the banner rotating
            if (item != null) {
                val user by remember(item.id) { app.db.user().observe(item.id) }.collectAsState(initial = null)
                val inList = user?.inWatchlist == true
                Row(
                    Modifier
                        // phone: buttons scroll sideways instead of cutting off the last label
                        .then(if (!isTv) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                        .onFocusChanged { if (it.hasFocus) onFocus() }
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) { onDown(); true } else false
                        },
                    horizontalArrangement = Arrangement.spacedBy(if (isTv) 12.dp else 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PillButton(s.play, onClick = { onPlay(item) }, icon = Icons.Filled.PlayArrow, primary = true, focusRequester = playFr, compact = !isTv)
                    PillButton(s.moreInfo, onClick = { onInfo(item) }, icon = Icons.Filled.Info, compact = !isTv)
                    PillButton(
                        if (inList) s.inWatchlist else s.addToWatchlist,
                        onClick = { scope.launch { app.library.toggleWatchlist(item.id) } },
                        icon = if (inList) Icons.Filled.Check else Icons.Filled.Add, selected = inList, compact = !isTv,
                    )
                    if (count > 1) {
                        Spacer(Modifier.width(10.dp))
                        PillButton("", onClick = onNext, icon = AppIcons.Forward, compact = true)
                        Spacer(Modifier.width(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            repeat(count) { i ->
                                val on = i == index % count
                                Box(
                                    Modifier.size(width = if (on) 18.dp else 6.dp, height = 6.dp)
                                        .clip(CircleShape)
                                        .background(if (on) accent else Color.White.copy(alpha = .35f))
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanningState(title: String, sub: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = LocalAccent.current, strokeWidth = 3.dp)
            Spacer(Modifier.height(22.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.SemiBold)
            if (sub.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = TextDim)
            }
        }
    }
}

private class HomeFocus {
    var inRows by mutableStateOf(false)
    var item by mutableStateOf<ItemEntity?>(null)
}

/** Full-bleed backdrop that follows the banner, or the focused card once you're in the rows. */
@Composable
private fun HomeBackdrop(focus: HomeFocus, bannerItem: ItemEntity?) {
    val ctx = LocalContext.current
    val target = if (focus.inRows) focus.item ?: bannerItem else bannerItem
    var shown by remember { mutableStateOf<ItemEntity?>(null) }
    // wait until the user pauses on a card before decoding a big image (keeps scrolling smooth)
    LaunchedEffect(target?.id) { delay(if (shown == null) 0 else 700); shown = target }
    val dim by animateFloatAsState(if (focus.inRows) 0.55f else 0f, tween(500), label = "dim")
    Box(Modifier.fillMaxSize()) {
        Crossfade(targetState = shown?.backdropUrl(true), animationSpec = tween(450), label = "backdrop") { url ->
            if (url != null) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(url).size(960, 540).build(),
                    contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    // dimming happens inside the image draw: no extra full-screen layer
                    colorFilter = if (dim > 0.01f) androidx.compose.ui.graphics.ColorFilter.tint(Bg.copy(alpha = dim), androidx.compose.ui.graphics.BlendMode.SrcAtop) else null,
                )
            }
        }
        // both gradients pre-rendered once into a small bitmap and drawn as one stretched image:
        // one full-screen fill instead of two gradient passes (the Mali-450 is fill-rate bound)
        Spacer(
            Modifier.fillMaxSize().drawWithCache {
                val w = 480; val hh = 270
                val bmp = androidx.compose.ui.graphics.ImageBitmap(w, hh)
                val h = Brush.horizontalGradient(0f to Bg.copy(alpha = .92f), 0.6f to Bg.copy(alpha = .25f), 1f to Color.Transparent)
                val v = Brush.verticalGradient(0f to Bg.copy(alpha = .5f), 0.25f to Color.Transparent, 0.6f to Bg.copy(alpha = .75f), 1f to Bg)
                androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
                    this, layoutDirection, androidx.compose.ui.graphics.Canvas(bmp), androidx.compose.ui.geometry.Size(w.toFloat(), hh.toFloat()),
                ) { drawRect(h); drawRect(v) }
                onDrawBehind {
                    drawImage(
                        bmp, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
                    )
                }
            }
        )
    }
}
