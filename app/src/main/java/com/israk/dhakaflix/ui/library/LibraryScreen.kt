@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.israk.dhakaflix.ui.library

import androidx.compose.foundation.layout.Arrangement
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

/** Which part of My Library the grid shows. */
private enum class Shelf { WATCH_LATER, CONTINUE, WATCHED }

/** My Library as a poster grid (same look as Movies / Series) with filters on top. */
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

    var shelf by rememberSaveable { mutableStateOf(Shelf.WATCH_LATER) }
    var type by rememberSaveable { mutableStateOf<String?>(null) }   // null = all, "MOVIE", "SERIES"
    var sort by rememberSaveable { mutableStateOf(SortMode.LATEST) }

    // continue-watching entries keep their file so OK resumes playback like before
    val cwByItem = remember(cw) { cw.associateBy { it.item.id } }
    val base: List<ItemEntity> = when (shelf) {
        Shelf.WATCH_LATER -> watchlist
        Shelf.CONTINUE -> cw.map { it.item }
        Shelf.WATCHED -> watched
    }
    val shown = remember(base, type, sort) {
        val f = if (type == null) base else base.filter { it.type == type }
        when (sort) {
            SortMode.LATEST -> f // already newest first
            SortMode.YEAR -> f.sortedByDescending { it.displayYear ?: 0 }
            SortMode.AZ -> f.sortedBy { it.displayTitle.lowercase() }
            SortMode.RATING -> f.sortedByDescending { it.rating ?: 0.0 }
        }
    }

    val specs = listOf(
        FilterSpec(
            s.library,
            listOf<Pair<String?, String>>(
                Shelf.WATCH_LATER.name to "${s.myWatchlist} (${watchlist.size})",
                Shelf.CONTINUE.name to "${s.continueWatching} (${cw.size})",
                Shelf.WATCHED.name to "${s.history} (${watched.size})",
            ),
            shelf.name, highlight = false,
        ) { shelf = Shelf.valueOf(it!!) },
        FilterSpec(s.category, listOf<Pair<String?, String>>(null to s.all, "MOVIE" to s.movies, "SERIES" to s.series), type) { type = it },
        FilterSpec(s.sort, SortMode.entries.map { it.name to sortLabel(it, s) }, sort.name, highlight = false) { sort = SortMode.valueOf(it!!) },
    )

    val cell: Dp = if (isTv) 112.dp else 104.dp
    val gridState = rememberLazyGridState()
    com.israk.dhakaflix.ui.ReportScroll { gridState.firstVisibleItemIndex > 0 }
    CalmVerticalScroll(topMargin = LocalTopInset.current + 12.dp, bottomMargin = 64.dp) {
        LazyVerticalGrid(
            columns = app.settings.postersPerRow.collectAsState().value.let { n -> if (n > 0) GridCells.Fixed(n) else GridCells.Adaptive(cell) },
            state = gridState,
            modifier = Modifier.fillMaxSize().dpadScrollFallback(gridState),
            contentPadding = PaddingValues(start = edge, end = edge, top = LocalTopInset.current + 8.dp, bottom = 56.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 20.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (isTv) 22.dp else 16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(s.library, style = MaterialTheme.typography.headlineMedium, color = Color.White)
                    FilterBar(
                        specs,
                        onClear = if (type != null) ({ type = null }) else null,
                        firstModifier = Modifier.entryFocus(),
                    )
                }
            }
            items(shown.size, key = { "${shelf.name}:${shown[it].id}" }) { i ->
                val item = shown[i]
                when (shelf) {
                    Shelf.CONTINUE -> PosterCard(
                        item = item, width = Dp.Unspecified, modifier = Modifier.fillMaxWidth().lineEndGuard(gridState, "${shelf.name}:${item.id}"),
                        menu = CardMenu.CONTINUE,
                        onClick = {
                            val p = cwByItem[item.id]?.progress
                            if (p == null) onOpen(item.id) else scope.launch {
                                val (list, idx) = repo.playlistFromProgress(p)
                                PlayerActivity.start(ctx, list, idx, PlayerActivity.MODE_ASK)
                            }
                        },
                    )
                    Shelf.WATCHED -> PosterCard(
                        item = item, width = Dp.Unspecified, modifier = Modifier.fillMaxWidth().lineEndGuard(gridState, "${shelf.name}:${item.id}"),
                        watched = true, menu = CardMenu.LIBRARY, onClick = { onOpen(item.id) },
                    )
                    Shelf.WATCH_LATER -> PosterCard(
                        item = item, width = Dp.Unspecified, modifier = Modifier.fillMaxWidth().lineEndGuard(gridState, "${shelf.name}:${item.id}"),
                        menu = CardMenu.LIBRARY, onClick = { onOpen(item.id) },
                    )
                }
            }
            if (shown.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        if (base.isEmpty()) s.libraryEmpty else s.noResults,
                        Modifier.padding(vertical = 40.dp), color = TextDim, style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
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
