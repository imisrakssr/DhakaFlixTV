@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.israk.dhakaflix.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.israk.dhakaflix.DhakaFlixApp
import com.israk.dhakaflix.data.SortMode
import com.israk.dhakaflix.data.db.ItemEntity
import com.israk.dhakaflix.player.PlayerActivity
import com.israk.dhakaflix.ui.LocalTopInset
import com.israk.dhakaflix.ui.browse.FilterBar
import com.israk.dhakaflix.ui.browse.FilterSpec
import com.israk.dhakaflix.ui.browse.sortLabel
import com.israk.dhakaflix.ui.components.CalmVerticalScroll
import com.israk.dhakaflix.ui.components.CardMenu
import com.israk.dhakaflix.ui.components.PosterCard
import com.israk.dhakaflix.ui.components.dpadScrollFallback
import com.israk.dhakaflix.ui.entryFocus
import com.israk.dhakaflix.ui.theme.LocalEdge
import com.israk.dhakaflix.ui.theme.LocalIsTv
import com.israk.dhakaflix.ui.theme.LocalStrings
import com.israk.dhakaflix.ui.theme.TextDim
import kotlinx.coroutines.launch

private enum class Shelf { CONTINUE, WATCHLIST, WATCHED }

/** Category + sort for one shelf. */
private fun List<ItemEntity>.filtered(type: String?, sort: SortMode): List<ItemEntity> {
    val f = if (type == null) this else filter { it.type == type }
    return when (sort) {
        SortMode.LATEST -> f // already newest first
        SortMode.YEAR -> f.sortedByDescending { it.displayYear ?: 0 }
        SortMode.AZ -> f.sortedBy { it.displayTitle.lowercase() }
        SortMode.RATING -> f.sortedByDescending { it.rating ?: 0.0 }
    }
}

/**
 * My Library as poster-grid catalogs (same cards as Movies / Series):
 * Continue Watching, then My Watchlist and Watched, each with its own filters.
 */
@Composable
fun LibraryScreen(onOpen: (String) -> Unit) {
    val app = DhakaFlixApp.instance
    val repo = app.library
    val s = LocalStrings.current
    val isTv = LocalIsTv.current
    val edge = LocalEdge.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val cw by remember { repo.continueWatching() }.collectAsState(initial = emptyList())
    val watchlist by remember { app.db.user().watchlist() }.collectAsState(initial = emptyList())
    val watched by remember { app.db.user().watched() }.collectAsState(initial = emptyList())

    var wlType by rememberSaveable { mutableStateOf<String?>(null) }
    var wlSort by rememberSaveable { mutableStateOf(SortMode.LATEST) }
    var wType by rememberSaveable { mutableStateOf<String?>(null) }
    var wSort by rememberSaveable { mutableStateOf(SortMode.LATEST) }
    val wlShown = remember(watchlist, wlType, wlSort) { watchlist.filtered(wlType, wlSort) }
    val wShown = remember(watched, wType, wSort) { watched.filtered(wType, wSort) }

    fun specs(type: String?, sort: SortMode, onType: (String?) -> Unit, onSort: (SortMode) -> Unit) = listOf(
        FilterSpec(s.category, listOf<Pair<String?, String>>(null to s.all, "MOVIE" to s.movies, "SERIES" to s.series), type) { onType(it) },
        FilterSpec(s.sort, SortMode.entries.map { it.name to sortLabel(it, s) }, sort.name, highlight = false) { onSort(SortMode.valueOf(it!!)) },
    )

    if (cw.isEmpty() && watchlist.isEmpty() && watched.isEmpty()) {
        com.israk.dhakaflix.ui.components.CenterMessage(s.libraryEmpty, Modifier.padding(top = LocalTopInset.current))
        return
    }

    val perRow by app.settings.postersPerRow.collectAsState()
    val screenW = with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toDp() }
    val posterW = com.israk.dhakaflix.ui.components.posterWidth(screenW - if (isTv && app.settings.navPosition.collectAsState().value == "left") 96.dp else 0.dp)
    val landW = if (perRow > 0) posterW * 1.96f else if (isTv) 220.dp else 210.dp
    val cell: Dp = if (isTv) 112.dp else 104.dp
    val gridState = rememberLazyGridState()
    com.israk.dhakaflix.ui.ReportScroll { gridState.firstVisibleItemIndex > 0 }
    val entry = Modifier.entryFocus()

    @Composable
    fun Card(shelf: Shelf, item: ItemEntity, focusFirst: Boolean) {
        val key = "${shelf.name}:${item.id}"
        val mod = Modifier.fillMaxWidth().lineEndGuard(gridState, key).then(if (focusFirst) entry else Modifier)
        when (shelf) {
            Shelf.CONTINUE -> PosterCard(
                item = item, width = Dp.Unspecified, modifier = mod, menu = CardMenu.CONTINUE,
                onClick = {
                    val p = cw.firstOrNull { it.item.id == item.id }?.progress
                    if (p == null) onOpen(item.id) else scope.launch {
                        val (list, idx) = repo.playlistFromProgress(p)
                        PlayerActivity.start(ctx, list, idx, PlayerActivity.MODE_ASK)
                    }
                },
            )
            Shelf.WATCHLIST -> PosterCard(item = item, width = Dp.Unspecified, modifier = mod, menu = CardMenu.LIBRARY, onClick = { onOpen(item.id) })
            Shelf.WATCHED -> PosterCard(item = item, width = Dp.Unspecified, modifier = mod, watched = true, menu = CardMenu.LIBRARY, onClick = { onOpen(item.id) })
        }
    }

    CalmVerticalScroll(topMargin = LocalTopInset.current + 12.dp, bottomMargin = 64.dp) {
        LazyVerticalGrid(
            columns = app.settings.postersPerRow.collectAsState().value.let { n -> if (n > 0) GridCells.Fixed(n) else GridCells.Adaptive(cell) },
            state = gridState,
            modifier = Modifier.fillMaxSize().dpadScrollFallback(gridState),
            contentPadding = PaddingValues(start = edge, end = edge, top = LocalTopInset.current + 8.dp, bottom = 56.dp + com.israk.dhakaflix.ui.LocalBottomInset.current),
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 20.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (isTv) 22.dp else 16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "title") {
                Text(s.library, style = MaterialTheme.typography.headlineMedium, color = Color.White)
            }

            // ---- Continue Watching (no filters)
            // same wide landscape row as before (v1.0.22)
            if (cw.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "row:cw") {
                    Box(
                        // the row brings its own side padding: stretch it over the grid's edge padding
                        Modifier.layout { m, c ->
                            val extra = edge.roundToPx()
                            val w = c.maxWidth + 2 * extra
                            val pl = m.measure(c.copy(minWidth = w, maxWidth = w))
                            layout(c.maxWidth, pl.height) { pl.place(-extra, 0) }
                        }
                    ) {
                        com.israk.dhakaflix.ui.components.MediaRow(s.continueWatching, cw, key = { it.progress.fileUrl }, modifier = entry) { e ->
                            com.israk.dhakaflix.ui.components.LandscapeCard(
                                e.item, e.progress.label, e.progress.fraction, landW,
                                onClick = {
                                    scope.launch {
                                        val (list, idx) = repo.playlistFromProgress(e.progress)
                                        PlayerActivity.start(ctx, list, idx, PlayerActivity.MODE_ASK)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // ---- My Watchlist
            item(span = { GridItemSpan(maxLineSpan) }, key = "h:wl") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SectionTitle(s.myWatchlist, watchlist.size)
                    FilterBar(
                        specs(wlType, wlSort, { wlType = it }, { wlSort = it }),
                        onClear = if (wlType != null) ({ wlType = null }) else null,
                        firstModifier = if (cw.isEmpty()) entry else Modifier,
                    )
                }
            }
            items(wlShown.size, key = { "${Shelf.WATCHLIST.name}:${wlShown[it].id}" }) { i -> Card(Shelf.WATCHLIST, wlShown[i], false) }
            if (wlShown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "e:wl") { EmptyLine(if (watchlist.isEmpty()) s.libraryEmpty else s.noResults) }

            // ---- Watched
            item(span = { GridItemSpan(maxLineSpan) }, key = "h:w") {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SectionTitle(s.history, watched.size)
                    FilterBar(
                        specs(wType, wSort, { wType = it }, { wSort = it }),
                        onClear = if (wType != null) ({ wType = null }) else null,
                    )
                }
            }
            items(wShown.size, key = { "${Shelf.WATCHED.name}:${wShown[it].id}" }) { i -> Card(Shelf.WATCHED, wShown[i], false) }
            if (wShown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "e:w") { EmptyLine(if (watched.isEmpty()) s.libraryEmpty else s.noResults) }
        }
    }
}

@Composable
private fun SectionTitle(text: String, count: Int) {
    Text(
        "$text  ·  $count", Modifier.padding(top = 10.dp),
        style = MaterialTheme.typography.titleLarge, color = Color.White,
    )
}

@Composable
private fun EmptyLine(text: String) {
    Text(text, Modifier.padding(vertical = 16.dp), color = TextDim, style = MaterialTheme.typography.titleMedium)
}

/** RIGHT on the last poster of a grid line does nothing (instead of jumping up into the menu). */
private fun Modifier.lineEndGuard(state: LazyGridState, key: Any): Modifier =
    this.onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown || e.key != Key.DirectionRight) return@onPreviewKeyEvent false
        val infos = state.layoutInfo.visibleItemsInfo
        val i = infos.indexOfFirst { it.key == key }
        if (i < 0) return@onPreviewKeyEvent false
        val next = infos.getOrNull(i + 1)
        next == null || next.row != infos[i].row
    }
