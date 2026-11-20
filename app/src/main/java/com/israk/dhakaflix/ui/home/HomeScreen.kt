package com.israk.dhakaflix.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.israk.dhakaflix.DhakaFlixApp
import com.israk.dhakaflix.data.ContinueEntry
import com.israk.dhakaflix.data.HomeRow
import com.israk.dhakaflix.data.RowKeys
import com.israk.dhakaflix.data.Source
import com.israk.dhakaflix.data.db.ItemEntity
import com.israk.dhakaflix.player.PlayerActivity
import com.israk.dhakaflix.ui.LocalTopInset
import com.israk.dhakaflix.ui.components.CenterMessage
import com.israk.dhakaflix.ui.components.LandscapeCard
import com.israk.dhakaflix.ui.components.MediaRow
import com.israk.dhakaflix.ui.components.MetaLine
import com.israk.dhakaflix.ui.components.PillButton
import com.israk.dhakaflix.ui.components.PosterCard
import com.israk.dhakaflix.ui.components.backdropUrl
import com.israk.dhakaflix.ui.theme.Bg
import com.israk.dhakaflix.ui.theme.LocalAccent
import com.israk.dhakaflix.ui.theme.LocalEdge
import com.israk.dhakaflix.ui.theme.LocalIsTv
import com.israk.dhakaflix.ui.theme.LocalStrings
import com.israk.dhakaflix.ui.theme.Strings
import com.israk.dhakaflix.ui.theme.TextDim
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun rowTitle(key: String, s: Strings, sources: List<Source>): String = when {
    key == RowKeys.CONTINUE -> s.continueWatching
    key == RowKeys.LATEST -> s.latestUploads
    key == RowKeys.LATEST_MOVIES -> s.latestMovies
    key == RowKeys.LATEST_SERIES -> s.latestSeries
    key == RowKeys.WATCHLIST -> s.myWatchlist
    key == RowKeys.TOP_RATED -> s.topRated
    key.startsWith("genre:") -> key.removePrefix("genre:")
    key.startsWith("src:") -> s.latestIn.format(sources.firstOrNull { it.id == key.removePrefix("src:") }?.name ?: "")
    else -> key
}

@Composable
fun HomeScreen(onOpen: (String) -> Unit) {
    val app = DhakaFlixApp.instance
    val repo = app.library
    val s = LocalStrings.current
    val isTv = LocalIsTv.current
    val edge = LocalEdge.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val rows by app.settings.homeRows.collectAsState()
    val sources by app.settings.sources.collectAsState()
    val count by remember { repo.itemCount }.collectAsState(initial = -1)
    val sync by repo.sync.collectAsState()
    val watchedIds by remember { app.db.user().watchedIds() }.collectAsState(initial = emptyList())
    val watchedSet = remember(watchedIds) { watchedIds.toHashSet() }
    val cw by remember { repo.continueWatching() }.collectAsState(initial = emptyList())
    val latest by remember { repo.rowFlow(RowKeys.LATEST) }.collectAsState(initial = emptyList())

    var hero by remember { mutableStateOf<ItemEntity?>(null) }
    var shownHero by remember { mutableStateOf<ItemEntity?>(null) }
    LaunchedEffect(latest) { if (hero == null) hero = latest.firstOrNull() }
    LaunchedEffect(hero) { delay(if (shownHero == null) 0 else 260); shownHero = hero }

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

    val playContinue: (ContinueEntry) -> Unit = { e ->
        scope.launch {
            val (list, idx) = repo.playlistFromProgress(e.progress)
            PlayerActivity.start(ctx, list, idx)
        }
    }

    val enabledRows = rows.filter { it.enabled }

    if (isTv) {
        Box(Modifier.fillMaxSize()) {
            // Full-bleed backdrop that follows focus
            Crossfade(targetState = shownHero?.backdropUrl(true), animationSpec = tween(600), label = "backdrop") { url ->
                if (url != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(ctx).data(url).size(1280, 720).build(),
                        contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(0f to Bg.copy(alpha = .95f), 0.55f to Bg.copy(alpha = .35f), 1f to Color.Transparent)
                )
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0f to Bg.copy(alpha = .55f), 0.3f to Color.Transparent, 0.62f to Bg.copy(alpha = .9f), 1f to Bg)
                )
            )
            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.height(LocalTopInset.current))
                HeroInfo(shownHero, Modifier.padding(start = edge, end = edge).height(196.dp))
                HomeRows(
                    rows = enabledRows, sources = sources, s = s, cw = cw, watched = watchedSet,
                    onOpen = onOpen, onPlayContinue = playContinue, onFocusItem = { hero = it },
                    contentPadding = PaddingValues(bottom = 60.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    } else {
        val heroItem = latest.firstOrNull()
        HomeRows(
            rows = enabledRows, sources = sources, s = s, cw = cw, watched = watchedSet,
            onOpen = onOpen, onPlayContinue = playContinue, onFocusItem = {},
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize(),
            header = { if (heroItem != null) PhoneHero(heroItem, onOpen) },
        )
    }
}

@Composable
private fun HomeRows(
    rows: List<HomeRow>,
    sources: List<Source>,
    s: Strings,
    cw: List<ContinueEntry>,
    watched: Set<String>,
    onOpen: (String) -> Unit,
    onPlayContinue: (ContinueEntry) -> Unit,
    onFocusItem: (ItemEntity) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier,
    header: (@Composable () -> Unit)? = null,
) {
    val repo = DhakaFlixApp.instance.library
    val isTv = LocalIsTv.current
    val posterW = if (isTv) 128.dp else 108.dp
    val listState = rememberLazyListState()
    LazyColumn(modifier, state = listState, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (header != null) item(key = "header") { header() }
        items(rows, key = { it.key }) { row ->
            if (row.key == RowKeys.CONTINUE) {
                MediaRow(title = s.continueWatching, items = cw, key = { it.progress.fileUrl }) { e ->
                    LandscapeCard(
                        item = e.item, label = e.progress.label, progress = e.progress.fraction,
                        width = if (isTv) 250.dp else 220.dp,
                        onClick = { onPlayContinue(e) }, onFocused = { onFocusItem(e.item) },
                    )
                }
            } else {
                val items by remember(row.key) { repo.rowFlow(row.key) }.collectAsState(initial = emptyList())
                MediaRow(title = rowTitle(row.key, s, sources), items = items, key = { it.id }) { item ->
                    PosterCard(
                        item = item, width = posterW, watched = item.id in watched,
                        onClick = { onOpen(item.id) }, onFocused = { onFocusItem(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroInfo(item: ItemEntity?, modifier: Modifier) {
    val s = LocalStrings.current
    AnimatedContent(
        targetState = item,
        transitionSpec = { fadeIn(tween(450)) togetherWith fadeOut(tween(250)) },
        contentKey = { it?.id },
        modifier = modifier,
        label = "hero",
    ) { cur ->
        Column(Modifier.widthIn(max = 620.dp).fillMaxHeight()) {
            if (cur == null) {
                Text(s.greeting, style = MaterialTheme.typography.headlineLarge, color = Color.White)
                return@Column
            }
            Spacer(Modifier.weight(1f))
            Text(
                cur.displayTitle, style = MaterialTheme.typography.headlineLarge, color = Color.White,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            MetaLine(cur, extra = cur.genreList.take(3))
            Spacer(Modifier.height(10.dp))
            Text(
                cur.overview ?: "", style = MaterialTheme.typography.bodyMedium, color = TextDim,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PhoneHero(item: ItemEntity, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val s = LocalStrings.current
    Box(Modifier.fillMaxWidth().height(420.dp)) {
        AsyncImage(
            model = ImageRequest.Builder(ctx).data(item.backdropUrl(false)).crossfade(true).build(),
            contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Transparent, 1f to Bg)))
        Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
            Text(item.displayTitle, style = MaterialTheme.typography.headlineMedium, color = Color.White, maxLines = 2)
            Spacer(Modifier.height(6.dp))
            MetaLine(item)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(s.play, onClick = { onOpen(item.id) }, icon = Icons.Filled.PlayArrow, primary = true)
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
            Spacer(Modifier.width(1.dp))
        }
    }
}
