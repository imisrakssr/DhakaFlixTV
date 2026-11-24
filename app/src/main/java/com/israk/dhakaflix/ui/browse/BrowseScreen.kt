@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

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
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
import androidx.paging.cachedIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.israk.dhakaflix.ui.components.FocusFrame
import com.israk.dhakaflix.ui.components.PillButton
import com.israk.dhakaflix.ui.components.glass
import com.israk.dhakaflix.ui.theme.Bg
import com.israk.dhakaflix.ui.theme.LocalAccent
import kotlinx.coroutines.delay

fun sortLabel(m: SortMode, s: Strings) = when (m) {
    SortMode.LATEST -> s.sortLatest
    SortMode.YEAR -> s.sortYear
    SortMode.AZ -> s.sortAz
    SortMode.RATING -> s.sortRating
}

/** Keeps recent pagers alive app-wide so switching back to Movies/Series/Search is instant. */
private object PagerCache {
    private val map = object : LinkedHashMap<BrowseFilter, Flow<PagingData<ItemEntity>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<BrowseFilter, Flow<PagingData<ItemEntity>>>?) = size > 6
    }

    @Synchronized
    fun get(filter: BrowseFilter): Flow<PagingData<ItemEntity>> = map.getOrPut(filter) {
        val app = DhakaFlixApp.instance
        Pager(PagingConfig(pageSize = 30, initialLoadSize = 45, prefetchDistance = 30, enablePlaceholders = false)) {
            app.db.items().browse(app.library.browseQuery(filter))
        }.flow.cachedIn(app.appScope)
    }
}

@Composable
fun rememberBrowsePager(filter: BrowseFilter): Flow<PagingData<ItemEntity>> = remember(filter) { PagerCache.get(filter) }

@Composable
fun BrowseScreen(type: MediaType, onOpen: (String) -> Unit) {
    val app = DhakaFlixApp.instance
    val s = LocalStrings.current
    val allSources by app.settings.sources.collectAsState()
    // streaming-style categories (Hollywood, Bollywood, K-Drama …) across every server
    val categories = remember(allSources, type) { com.israk.dhakaflix.data.Categories.available(type, allSources) }
    val dhk = com.israk.dhakaflix.ui.components.isDhakaiya()
    // only years this tab actually has (Movies or Series)
    val years by remember(type) { app.db.items().yearsFor(type.name) }.collectAsState(initial = emptyList())

    var sort by rememberSaveable { mutableStateOf(SortMode.LATEST) }
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceId by rememberSaveable { mutableStateOf<String?>(null) } // category key
    var year by rememberSaveable { mutableStateOf<Int?>(null) }

    val catSources = remember(sourceId, categories) { categories.firstOrNull { it.key == sourceId }?.sources?.sorted().orEmpty() }
    val filter = BrowseFilter(type = type, genre = genre, categorySources = catSources, year = year, sort = sort)
    val items = rememberBrowsePager(filter).collectAsLazyPagingItems()

    val specs = buildList {
        add(FilterSpec(s.sort, SortMode.entries.map { it.name to sortLabel(it, s) }, sort.name, highlight = false) { sort = SortMode.valueOf(it!!) })
        if (categories.size > 1) add(FilterSpec(s.category, listOf<Pair<String?, String>>(null to s.allCategories) + categories.map { it.key to it.label(dhk) }, sourceId) { sourceId = it })
        add(FilterSpec(s.genre, listOf<Pair<String?, String>>(null to s.allGenres) + ALL_GENRES.map { it to it }, genre) { genre = it })
        add(FilterSpec(s.year, listOf<Pair<String?, String>>(null to s.anyYear) + years.map { it.toString() to it.toString() }, year?.toString()) { year = it?.toInt() })
    }
    val anyFilter = genre != null || sourceId != null || year != null

    PosterGrid(items, onOpen, emptyText = s.noResults) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (type == MediaType.MOVIE) s.movies else s.series, style = MaterialTheme.typography.headlineMedium, color = Color.White)
                FilterBar(
                    specs,
                    onClear = if (anyFilter) ({ genre = null; sourceId = null; year = null }) else null,
                    firstModifier = Modifier.entryFocus(),
                )
            }
        }
    }
}

/** One dropdown-style filter: ids are strings (null = "all"). */
class FilterSpec(
    val title: String,
    val options: List<Pair<String?, String>>,
    val selected: String?,
    val highlight: Boolean = true,
    val onSelect: (String?) -> Unit,
) {
    val selectedLabel: String get() = options.firstOrNull { it.first == selected }?.second ?: options.firstOrNull()?.second.orEmpty()
}

/** Compact row of filter buttons; each opens an easy D-pad list on the right. */
@Composable
fun FilterBar(specs: List<FilterSpec>, onClear: (() -> Unit)?, firstModifier: Modifier = Modifier) {
    val s = LocalStrings.current
    var open by remember { mutableStateOf<Int?>(null) }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides com.israk.dhakaflix.ui.components.tvScrollSpec(com.israk.dhakaflix.ui.components.OnlyIfNeededSpec)) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tv = LocalIsTv.current
        specs.forEachIndexed { i, sp ->
            Box {
                PillButton(
                    sp.title + ":  " + sp.selectedLabel,
                    icon = Icons.Filled.KeyboardArrowDown, compact = true,
                    selected = sp.highlight && sp.selected != null,
                    modifier = if (i == 0) firstModifier else Modifier,
                    onClick = { open = i },
                )
                // phone: a plain drop-down right under the button (TV keeps the side list)
                if (!tv) androidx.compose.material3.DropdownMenu(
                    expanded = open == i,
                    onDismissRequest = { open = null },
                    modifier = Modifier.heightIn(max = 380.dp),
                    shape = RoundedCornerShape(16.dp),
                    containerColor = Color(0xFF17171F),
                ) {
                    sp.options.forEach { (id, label) ->
                        val sel = id == sp.selected || (sp.selected == null && id == null)
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(label, color = if (sel) LocalAccent.current else Color.White, maxLines = 1) },
                            trailingIcon = if (sel) ({ Icon(Icons.Filled.Check, null, tint = LocalAccent.current) }) else null,
                            onClick = { sp.onSelect(id); open = null },
                        )
                    }
                }
            }
        }
        if (onClear != null) PillButton(s.clearFilters, icon = Icons.Filled.Close, compact = true, onClick = onClear)
    }
    }
    if (LocalIsTv.current) open?.let { idx -> specs.getOrNull(idx)?.let { FilterSheet(it) { open = null } } }
}

@Composable
private fun FilterSheet(spec: FilterSpec, onDismiss: () -> Unit) {
    val fr = remember { FocusRequester() }
    val selIdx = spec.options.indexOfFirst { it.first == spec.selected }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selIdx - 3).coerceAtLeast(0))
    LaunchedEffect(Unit) { delay(80); runCatching { fr.requestFocus() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().clickable(onClick = onDismiss), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier.padding(16.dp).width(340.dp).fillMaxHeight()
                    .glass(RoundedCornerShape(24.dp), tint = .10f)
                    .background(Bg.copy(alpha = .88f))
                    .padding(20.dp)
            ) {
                Text(spec.title, style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(spec.options) { i, (id, label) ->
                        FocusFrame(
                            onClick = { spec.onSelect(id); onDismiss() },
                            focusedScale = 1.03f, shape = RoundedCornerShape(14.dp),
                            focusRequester = if (i == selIdx) fr else null,
                            modifier = Modifier.fillMaxWidth(),
                        ) { focused ->
                            Row(
                                Modifier.fillMaxWidth().background(if (focused) Color.White else Color.White.copy(alpha = .06f))
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(label, Modifier.weight(1f), color = if (focused) Color.Black else Color.White, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                if (i == selIdx) Icon(Icons.Filled.Check, null, tint = if (focused) Color.Black else LocalAccent.current)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PosterGrid(
    items: LazyPagingItems<ItemEntity>,
    onOpen: (String) -> Unit,
    emptyText: String,
    override: List<ItemEntity>? = null,
    overrideTitle: String? = null,
    onClearOverride: (() -> Unit)? = null,
    header: LazyGridScope.() -> Unit,
) {
    val app = DhakaFlixApp.instance
    val isTv = LocalIsTv.current
    val edge = LocalEdge.current
    val watchedIds by remember { app.db.user().watchedIds() }.collectAsState(initial = emptyList())
    val watched = remember(watchedIds) { watchedIds.toHashSet() }
    val cell: Dp = if (isTv) 112.dp else 104.dp
    val gridState = rememberLazyGridState()
    com.israk.dhakaflix.ui.OnTabReselect { gridState.animateScrollToItem(0) }
    com.israk.dhakaflix.ui.ReportScroll { gridState.firstVisibleItemIndex > 0 }
    com.israk.dhakaflix.ui.components.CalmVerticalScroll(topMargin = LocalTopInset.current + 12.dp, bottomMargin = 64.dp) {
    LazyVerticalGrid(
        columns = app.settings.postersPerRow.collectAsState().value.let { n -> if (n > 0) GridCells.Fixed(n) else GridCells.Adaptive(cell) },
        state = gridState,
        modifier = Modifier.fillMaxSize().dpadScrollFallback(gridState),
        contentPadding = PaddingValues(start = edge, end = edge, top = LocalTopInset.current + 8.dp, bottom = 56.dp + com.israk.dhakaflix.ui.LocalBottomInset.current),
        horizontalArrangement = Arrangement.spacedBy(if (isTv) 20.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (isTv) 22.dp else 16.dp),
    ) {
        header()
        if (override != null) {
            if (overrideTitle != null && override.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(overrideTitle, Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.titleMedium)
                        if (onClearOverride != null) com.israk.dhakaflix.ui.components.PillButton(
                            LocalStrings.current.clearFilters, icon = androidx.compose.material.icons.Icons.Filled.Close,
                            compact = true, onClick = onClearOverride,
                        )
                    }
                }
            }
            items(override.size, key = { override[it].id }) { i ->
                val item = override[i]
                PosterCard(
                    item = item, width = Dp.Unspecified, modifier = Modifier.fillMaxWidth().rowEndGuard(gridState, item.id),
                    watched = item.id in watched, onClick = { onOpen(item.id) },
                )
            }
            if (override.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(emptyText, Modifier.padding(vertical = 40.dp), color = TextDim, style = MaterialTheme.typography.titleMedium)
                }
            }
            return@LazyVerticalGrid
        }
        items(count = items.itemCount, key = items.itemKey { it.id }) { i ->
            val item = items[i]
            if (item != null) {
                PosterCard(
                    item = item, width = Dp.Unspecified, modifier = Modifier.fillMaxWidth().rowEndGuard(gridState, item.id),
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
}


/** RIGHT on the last poster of a grid line does nothing (instead of jumping up into the menu). */
private fun Modifier.rowEndGuard(state: androidx.compose.foundation.lazy.grid.LazyGridState, key: Any): Modifier =
    this.onPreviewKeyEvent { e ->
        if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown || e.key != androidx.compose.ui.input.key.Key.DirectionRight) return@onPreviewKeyEvent false
        val infos = state.layoutInfo.visibleItemsInfo
        val i = infos.indexOfFirst { it.key == key }
        if (i < 0) return@onPreviewKeyEvent false
        val next = infos.getOrNull(i + 1)
        next == null || next.row != infos[i].row
    }
