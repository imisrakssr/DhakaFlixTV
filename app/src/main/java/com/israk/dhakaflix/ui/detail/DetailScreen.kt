package com.israk.dhakaflix.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
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
import com.israk.dhakaflix.data.FolderContent
import com.israk.dhakaflix.data.LibraryRepository
import com.israk.dhakaflix.data.PlayItem
import com.israk.dhakaflix.data.VideoFile
import com.israk.dhakaflix.data.db.ItemEntity
import com.israk.dhakaflix.data.db.ProgressEntity
import com.israk.dhakaflix.data.tmdb.TmdbEpisode
import com.israk.dhakaflix.data.tmdb.TmdbImages
import com.israk.dhakaflix.player.ExternalPlayer
import com.israk.dhakaflix.player.PlayerActivity
import com.israk.dhakaflix.ui.components.AppIcons
import com.israk.dhakaflix.ui.components.ChipRow
import com.israk.dhakaflix.ui.components.FocusFrame
import com.israk.dhakaflix.ui.components.MetaLine
import com.israk.dhakaflix.ui.components.PillButton
import com.israk.dhakaflix.ui.components.PosterImage
import com.israk.dhakaflix.ui.components.ProgressLine
import com.israk.dhakaflix.ui.components.backdropUrl
import com.israk.dhakaflix.ui.components.fmtTime
import com.israk.dhakaflix.ui.components.dpadScrollFallback
import com.israk.dhakaflix.ui.theme.Bg
import com.israk.dhakaflix.ui.theme.LocalAccent
import com.israk.dhakaflix.ui.theme.LocalEdge
import com.israk.dhakaflix.ui.theme.LocalIsTv
import com.israk.dhakaflix.ui.theme.LocalStrings
import com.israk.dhakaflix.ui.theme.Surface1
import com.israk.dhakaflix.ui.theme.Surface2
import com.israk.dhakaflix.ui.theme.TextDim
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private data class PlayPlan(val list: List<PlayItem>, val index: Int, val resumeAt: Long?, val label: String?)

@Composable
fun DetailScreen(id: String, onOpen: (String) -> Unit, autoPlay: Boolean = false, onBack: () -> Unit = {}) {
    val app = DhakaFlixApp.instance
    val repo = app.library
    val s = LocalStrings.current
    val isTv = LocalIsTv.current
    val edge = LocalEdge.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val item by remember(id) { app.db.items().observe(id) }.collectAsState(initial = null)
    val user by remember(id) { app.db.user().observe(id) }.collectAsState(initial = null)
    val progress by remember(id) { app.db.progress().forItem(id) }.collectAsState(initial = emptyList())
    val groupKey = item?.groupKey
    val versions by remember(groupKey) { groupKey?.let { app.db.items().versions(it) } ?: flowOf(emptyList()) }
        .collectAsState(initial = emptyList())

    var content by remember(id) { mutableStateOf<FolderContent?>(null) }
    var failed by remember(id) { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var season by rememberSaveable(id) { mutableStateOf<Int?>(null) }
    var episodes by remember(id) { mutableStateOf<List<VideoFile>>(emptyList()) }
    var episodesLoading by remember { mutableStateOf(false) }
    var tmdbEps by remember(id) { mutableStateOf<Map<Int, TmdbEpisode>>(emptyMap()) }
    var showFix by remember { mutableStateOf(false) }
    val playFr = remember { FocusRequester() }

    LaunchedEffect(id) {
        delay(80)
        runCatching { playFr.requestFocus() }
        launch { repo.ensureDetails(id) }
    }
    LaunchedEffect(id, reload) {
        failed = false
        val entity = app.db.items().get(id) ?: return@LaunchedEffect
        content = try { repo.listItem(entity) } catch (e: Exception) { failed = true; null }
    }
    LaunchedEffect(content) {
        val c = content ?: return@LaunchedEffect
        if (c.seasons.isNotEmpty() && season == null) {
            val last = progress.firstOrNull()?.season
            season = c.seasons.firstOrNull { it.number == last }?.number ?: c.seasons.first().number
        } else if (c.seasons.isEmpty()) {
            episodes = c.videos
        }
    }
    LaunchedEffect(season, content) {
        val c = content ?: return@LaunchedEffect
        val ref = c.seasons.firstOrNull { it.number == season } ?: return@LaunchedEffect
        episodesLoading = true
        episodes = runCatching { repo.listSeason(ref) }.getOrDefault(emptyList())
        episodesLoading = false
    }
    LaunchedEffect(season, item?.tmdbId) {
        val t = item?.tmdbId ?: return@LaunchedEffect
        val sn = season ?: 1
        if (item?.isSeries == true) tmdbEps = repo.tmdbSeason(t, sn)
    }

    val cur = item
    if (cur == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = LocalAccent.current) }
        return
    }

    val progressByUrl = remember(progress) { progress.associateBy { it.fileUrl } }
    val plan: PlayPlan? = remember(cur, content, episodes, progress) {
        buildPlan(cur, content, episodes, progressByUrl, progress.firstOrNull(), app.library)
    }
    val play: (PlayPlan, String) -> Unit = { p, mode -> PlayerActivity.start(ctx, p.list, p.index, mode) }
    var autoStarted by rememberSaveable(id) { mutableStateOf(false) }
    LaunchedEffect(plan, autoPlay) {
        val p = plan ?: return@LaunchedEffect
        if (autoPlay && !autoStarted) {
            autoStarted = true
            play(p, PlayerActivity.MODE_ASK)
        }
    }
    val detailList = androidx.compose.foundation.lazy.rememberLazyListState()
    val bgTrailers by app.settings.bgTrailers.collectAsState()
    var trailerKey by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(cur.tmdbId) { trailerKey = repo.discovery.trailerKey(cur) }
    var showTrailer by remember(id) { mutableStateOf(false) }
    // A YouTube WebView behind the page is far too heavy for 1-2 GB sticks (Mi TV Stick: the whole
    // page froze, 96% janky frames, WebView ran out of GPU memory). Only bigger boxes get it;
    // the Trailer button still works everywhere.
    val roomForTrailer = remember {
        val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        !am.isLowRamDevice && mi.totalMem > 2_500L * 1024 * 1024
    }
    LaunchedEffect(trailerKey, bgTrailers) {
        showTrailer = false
        if (trailerKey != null && bgTrailers && isTv && roomForTrailer) { delay(2500); showTrailer = true }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        AsyncImage(
            model = ImageRequest.Builder(ctx).data(content?.backdrop?.takeIf { cur.backdropPath == null } ?: cur.backdropUrl(isTv))
                .crossfade(true).build(),
            contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(if (isTv) 1f else .42f),
        )
        val tk = trailerKey
        if (showTrailer && tk != null) {
            com.israk.dhakaflix.ui.components.BackgroundTrailer(tk, Modifier.fillMaxSize())
        }
        Box(
            Modifier.fillMaxSize().background(
                if (isTv) Brush.horizontalGradient(0f to Bg, 0.5f to Bg.copy(alpha = .78f), 1f to Bg.copy(alpha = .15f))
                else Brush.verticalGradient(0f to Color.Transparent, 0.3f to Bg.copy(alpha = .4f), 0.42f to Bg)
            )
        )
        if (isTv) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Bg)))

        LazyColumn(
            Modifier.fillMaxSize().dpadScrollFallback(detailList),
            state = detailList,
            contentPadding = PaddingValues(start = edge, end = edge, top = if (isTv) 44.dp else 210.dp, bottom = 60.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") {
                Row {
                    if (isTv) {
                        PosterImage(cur, Modifier.width(170.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)))
                        Spacer(Modifier.width(32.dp))
                    }
                    Column(Modifier.widthIn(max = 720.dp)) {
                        com.israk.dhakaflix.ui.components.TitleOrLogo(cur, maxHeight = 110.dp, style = MaterialTheme.typography.headlineLarge)
                        cur.tagline?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = TextDim, maxLines = 1) }
                        Spacer(Modifier.height(10.dp))
                        MetaLine(cur, extra = cur.genreList.take(3))
                        ExternalRatings(cur)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            cur.overview ?: s.noMetadata, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = .85f),
                            maxLines = if (isTv) 5 else 8, overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(20.dp))
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            val resumeAt = plan?.resumeAt
                            val mainLabel = when {
                                failed -> s.retry
                                plan == null -> s.loading
                                resumeAt != null -> s.resume + " " + listOfNotNull(plan.label, fmtTime(resumeAt)).joinToString(" · ")
                                else -> s.play + (plan.label?.let { " $it" } ?: "")
                            }
                            PillButton(
                                mainLabel, primary = true, focusRequester = playFr,
                                icon = if (failed) Icons.Filled.Refresh else Icons.Filled.PlayArrow,
                                onClick = {
                                    when {
                                        failed -> reload++
                                        plan != null -> play(plan, if (resumeAt != null) PlayerActivity.MODE_RESUME else PlayerActivity.MODE_ASK)
                                    }
                                },
                            )
                            if (plan?.resumeAt != null) {
                                PillButton(s.startOver, onClick = { play(plan, PlayerActivity.MODE_START) }, icon = AppIcons.Rewind)
                            }
                            trailerKey?.let { k ->
                                PillButton(s.trailer, icon = Icons.Filled.PlayArrow, onClick = { com.israk.dhakaflix.ui.components.openTrailer(ctx, k) })
                            }
                            val inList = user?.inWatchlist == true
                            PillButton(
                                if (inList) s.inWatchlist else s.addToWatchlist,
                                icon = if (inList) Icons.Filled.Check else Icons.Filled.Add,
                                selected = inList,
                                onClick = { scope.launch { repo.toggleWatchlist(id) } },
                            )
                            val watched = user?.watched == true
                            PillButton(
                                if (watched) s.watched else s.markWatched, icon = AppIcons.Eye, selected = watched,
                                onClick = { scope.launch { repo.setWatched(id, !watched) } },
                            )
                            if (plan != null) {
                                PillButton(s.openInVlc, icon = AppIcons.OpenExternal, onClick = {
                                    val p = plan.list[plan.index]
                                    ExternalPlayer.openInVlc(ctx, p, plan.resumeAt ?: 0L)
                                })
                            }
                            PillButton(s.fixMatch, icon = Icons.Filled.Edit, onClick = { showFix = true })
                        }
                        if (failed) {
                            Text(s.folderError, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // Seasons & episodes
            val c = content
            if (c != null && cur.isSeries) {
                if (c.seasons.size > 1) {
                    item(key = "seasons") {
                        Column {
                            Section(s.seasons)
                            ChipRow(c.seasons.map { it.number }, season ?: -1, { "${s.season} $it" }, { season = it },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp))
                        }
                    }
                } else item(key = "eps-title") { Section(s.episodes) }
                if (episodesLoading) item(key = "eps-loading") { Text(s.loading, color = TextDim) }
                itemsIndexed(episodes, key = { _, v -> v.url }) { i, v ->
                    EpisodeRow(
                        v = v, tmdb = v.episode?.let { tmdbEps[it] }, progress = progressByUrl[v.url],
                        onClick = { play(PlayPlan(repo.playlistFor(cur, episodes), i, null, null), PlayerActivity.MODE_ASK) },
                    )
                }
            } else if (c != null && c.videos.size > 1) {
                item(key = "files-title") { Section(s.files) }
                itemsIndexed(c.videos, key = { _, v -> v.url }) { i, v ->
                    EpisodeRow(
                        v = v, tmdb = null, progress = progressByUrl[v.url],
                        onClick = { play(PlayPlan(repo.playlistFor(cur, c.videos), i, null, null), PlayerActivity.MODE_ASK) },
                    )
                }
            }

            // Cast
            val cast = repo.tmdb.castFromJson(cur.castJson)
            if (cast.isNotEmpty()) {
                item(key = "cast") {
                    Column {
                        Section(s.cast)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                            items(cast.size) { ci ->
                                val p = cast[ci]
                                Column(Modifier.width(92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.size(78.dp).clip(CircleShape).background(Surface2)) {
                                        AsyncImage(
                                            model = TmdbImages.url(p.profilePath, "w185"), contentDescription = p.name,
                                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(p.name, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    p.character?.let { Text(it, color = TextDim, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                }
                            }
                        }
                    }
                }
            }

            // Other versions of the same title (720p / 1080p / other category)
            val others = versions.filter { it.id != cur.id }
            if (others.isNotEmpty()) {
                item(key = "versions") {
                    Column {
                        Section(s.versions)
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            others.forEach { v ->
                                PillButton(
                                    listOfNotNull(v.quality, sourceName(v)).joinToString(" · ").ifEmpty { v.rawName },
                                    icon = AppIcons.Layers, compact = true, onClick = { onOpen(v.id) },
                                )
                            }
                        }
                    }
                }
            }
            item(key = "raw") {
                Text(cur.rawName, color = TextDim.copy(alpha = .5f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 16.dp))
            }
        }
        // phone only: Back button fixed in the top-left corner (TV uses the remote's BACK, as in v1.0.21)
        if (!isTv) PillButton(
            s.back, icon = Icons.AutoMirrored.Filled.ArrowBack, compact = true, onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 20.dp, top = 36.dp)
                .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .55f)),
        )
    }

    if (showFix) FixMatchDialog(cur, onDismiss = { showFix = false })
}

private fun sourceName(item: ItemEntity): String? =
    DhakaFlixApp.instance.settings.sources.value.firstOrNull { it.id == item.sourceId }?.name

private fun buildPlan(
    item: ItemEntity,
    content: FolderContent?,
    episodes: List<VideoFile>,
    progressByUrl: Map<String, ProgressEntity>,
    latest: ProgressEntity?,
    repo: LibraryRepository,
): PlayPlan? {
    content ?: return null
    if (!item.isSeries) {
        if (content.videos.isEmpty()) return null
        val list = repo.playlistFor(item, content.videos)
        // resume the most recently watched part, otherwise the biggest file (main feature)
        val idx = latest?.takeIf { !it.completed }?.let { p -> list.indexOfFirst { it.url == p.fileUrl } }?.takeIf { it >= 0 }
            ?: content.videos.indices.maxByOrNull { content.videos[it].size } ?: 0
        val p = progressByUrl[list[idx].url]
        return PlayPlan(list, idx, p?.takeIf { !it.completed && it.positionMs > 20_000 }?.positionMs, null)
    }
    if (episodes.isEmpty()) return null
    val list = repo.playlistFor(item, episodes)
    val lastIdx = latest?.let { p -> list.indexOfFirst { it.url == p.fileUrl } } ?: -1
    val idx = when {
        lastIdx >= 0 && latest?.completed == false -> lastIdx
        lastIdx >= 0 && lastIdx < list.lastIndex -> lastIdx + 1
        else -> list.indexOfFirst { progressByUrl[it.url]?.completed != true }.takeIf { it >= 0 } ?: 0
    }
    val p = progressByUrl[list[idx].url]
    return PlayPlan(
        list, idx,
        p?.takeIf { !it.completed && it.positionMs > 20_000 }?.positionMs,
        LibraryRepository.episodeLabel(list[idx].season, list[idx].episode),
    )
}

@Composable
private fun Section(title: String) {
    Text(title, Modifier.padding(top = 18.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium, color = Color.White)
}

@Composable
private fun EpisodeRow(v: VideoFile, tmdb: TmdbEpisode?, progress: ProgressEntity?, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val isTv = LocalIsTv.current
    FocusFrame(onClick = onClick, focusedScale = 1.02f, shape = RoundedCornerShape(14.dp), modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth()) { focused ->
        Row(
            Modifier.fillMaxWidth().background(if (focused) Color.White.copy(alpha = .12f) else Surface1.copy(alpha = .8f)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(if (isTv) 170.dp else 130.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Surface2)) {
                val still = TmdbImages.url(tmdb?.stillPath, "w300")
                if (still != null) {
                    AsyncImage(ImageRequest.Builder(ctx).data(still).crossfade(true).build(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(Icons.Filled.PlayArrow, null, tint = TextDim, modifier = Modifier.align(Alignment.Center).size(32.dp))
                }
                if (progress?.completed == true) {
                    Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).background(LocalAccent.current, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(15.dp))
                    }
                }
                if (progress != null && !progress.completed) {
                    ProgressLine(progress.fraction, Modifier.align(Alignment.BottomCenter).padding(6.dp))
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                val label = LibraryRepository.episodeLabel(v.season, v.episode)
                Text(
                    listOfNotNull(label, tmdb?.name).joinToString("  ·  ").ifEmpty { v.name },
                    color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                tmdb?.overview?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    listOfNotNull(
                        if (v.size > 0) String.format("%.2f GB", v.size / 1073741824.0) else null,
                        if (v.subs.isNotEmpty()) "CC" else null,
                        tmdb?.runtime?.let { "$it min" },
                    ).joinToString("  ·  "),
                    color = TextDim.copy(alpha = .7f), style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}


/** IMDb / RT / Metacritic / Letterboxd chips from MDBList (only when a key is set). */
@Composable
private fun ExternalRatings(item: com.israk.dhakaflix.data.db.ItemEntity) {
    val repo = com.israk.dhakaflix.DhakaFlixApp.instance.library
    val tmdb = item.tmdbId ?: return
    val key by com.israk.dhakaflix.DhakaFlixApp.instance.settings.mdblistKey.collectAsState()
    if (key.isBlank()) return
    var list by remember(tmdb) { mutableStateOf<List<com.israk.dhakaflix.data.accounts.ExtRating>>(emptyList()) }
    LaunchedEffect(tmdb, key) { list = repo.mdblist.ratings(tmdb, item.isSeries) }
    if (list.isEmpty()) return
    Spacer(Modifier.height(10.dp))
    // one line of chips; on a narrow phone it scrolls sideways instead of squashing the last chip
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        list.take(5).forEach { r ->
            val tint = when (r.source) {
                "imdb" -> Color(0xFFF5C518)
                "tomatoes" -> Color(0xFFFA320A)
                "metacritic" -> Color(0xFF66CC33)
                "letterboxd" -> Color(0xFF40BCF4)
                else -> Color.White.copy(alpha = .7f)
            }
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = .55f)).padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(r.label, color = tint, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                Spacer(Modifier.width(6.dp))
                Text(r.value, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            }
        }
    }
}
