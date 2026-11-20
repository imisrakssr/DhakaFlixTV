package com.israk.dhakaflix.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.israk.dhakaflix.DhakaFlixApp
import com.israk.dhakaflix.data.ALL_GENRES
import com.israk.dhakaflix.data.BrowseFilter
import com.israk.dhakaflix.data.MediaType
import com.israk.dhakaflix.data.SortMode
import com.israk.dhakaflix.data.db.ItemEntity
import com.israk.dhakaflix.ui.LocalTopInset
import com.israk.dhakaflix.ui.entryFocus
import com.israk.dhakaflix.ui.components.dpadScrollFallback
import com.israk.dhakaflix.ui.components.ChipRow
import com.israk.dhakaflix.ui.components.PosterCard
import com.israk.dhakaflix.ui.theme.LocalEdge
import com.israk.dhakaflix.ui.theme.LocalIsTv
import com.israk.dhakaflix.ui.theme.LocalStrings
import com.israk.dhakaflix.ui.theme.Strings
import com.israk.dhakaflix.ui.theme.TextDim
import kotlinx.coroutines.flow.Flow
import androidx.paging.PagingData

fun sortLabel(m: SortMode, s: Strings) = when (m) {
    SortMode.LATEST -> s.sortLatest
    SortMode.YEAR -> s.sortYear
    SortMode.AZ -> s.sortAz
    SortMode.RATING -> s.sortRating
}

@Composable
fun rememberBrowsePager(filter: BrowseFilter): Flow<PagingData<ItemEntity>> {
    val app = DhakaFlixApp.instance
    return remember(filter) {
        Pager(PagingConfig(pageSize = 48, prefetchDistance = 24, enablePlaceholders = false)) {
            app.db.items().browse(app.library.browseQuery(filter))
        }.flow
    }
}

@Composable
fun BrowseScreen(type: MediaType, onOpen: (String) -> Unit) {
    val app = DhakaFlixApp.instance
    val s = LocalStrings.current
    val allSources by app.settings.sources.collectAsState()
    val sources = remember(allSources, type) { allSources.filter { it.type == type && it.enabled } }

    var sort by rememberSaveable { mutableStateOf(SortMode.LATEST) }
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceId by rememberSaveable { mutableStateOf<String?>(null) }

    val filter = BrowseFilter(type = type, genre = genre, sourceId = sourceId, sort = sort)
    val items = rememberBrowsePager(filter).collectAsLazyPagingItems()

    PosterGrid(items, onOpen, emptyText = s.noResults) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (type == MediaType.MOVIE) s.movies else s.series, style = MaterialTheme.typography.headlineMedium, color = Color.White)
                ChipRow(SortMode.entries.toList(), sort, { sortLabel(it, s) }, { sort = it }, contentPadding = PaddingValues(vertical = 6.dp), firstItemModifier = Modifier.entryFocus())
                if (sources.size > 1) {
                    ChipRow(listOf<String?>(null) + sources.map { it.id }, sourceId,
                        { id -> if (id == null) s.allCategories else sources.first { it.id == id }.name },
                        { sourceId = it }, contentPadding = PaddingValues(vertical = 6.dp))
                }
                ChipRow(listOf<String?>(null) + ALL_GENRES, genre, { it ?: s.allGenres }, { genre = it },
                    contentPadding = PaddingValues(vertical = 6.dp))
            }
        }
    }
}

@Composable
fun PosterGrid(
    items: LazyPagingItems<ItemEntity>,
    onOpen: (String) -> Unit,
    emptyText: String,
    header: LazyGridScope.() -> Unit,
) {
    val app = DhakaFlixApp.instance
    val isTv = LocalIsTv.current
    val edge = LocalEdge.current
    val watchedIds by remember { app.db.user().watchedIds() }.collectAsState(initial = emptyList())
    val watched = remember(watchedIds) { watchedIds.toHashSet() }
    val cell: Dp = if (isTv) 112.dp else 104.dp
    val gridState = rememberLazyGridState()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(cell),
        state = gridState,
        modifier = Modifier.fillMaxSize().dpadScrollFallback(gridState),
        contentPadding = PaddingValues(start = edge, end = edge, top = LocalTopInset.current + 8.dp, bottom = 56.dp),
        horizontalArrangement = Arrangement.spacedBy(if (isTv) 20.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (isTv) 22.dp else 16.dp),
    ) {
        header()
        items(count = items.itemCount, key = items.itemKey { it.id }) { i ->
            val item = items[i]
            if (item != null) {
                PosterCard(
                    item = item, width = Dp.Unspecified, modifier = Modifier.fillMaxWidth(),
                    watched = item.id in watched, onClick = { onOpen(item.id) },
                )
            }
        }
        if (items.itemCount == 0 && items.loadState.refresh is LoadState.NotLoading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(emptyText, Modifier.padding(vertical = 40.dp), color = TextDim, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
