package com.israk.dhakaflix.ui.search

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.paging.compose.collectAsLazyPagingItems
import com.israk.dhakaflix.DhakaFlixApp
import com.israk.dhakaflix.data.BrowseFilter
import com.israk.dhakaflix.data.MediaType
import com.israk.dhakaflix.data.SortMode
import com.israk.dhakaflix.ui.browse.PosterGrid
import com.israk.dhakaflix.ui.browse.FilterBar
import com.israk.dhakaflix.ui.browse.FilterSpec
import com.israk.dhakaflix.ui.entryFocus
import com.israk.dhakaflix.ui.browse.rememberBrowsePager
import com.israk.dhakaflix.ui.browse.sortLabel
import com.israk.dhakaflix.ui.components.ChipRow
import com.israk.dhakaflix.ui.theme.LocalAccent
import com.israk.dhakaflix.ui.theme.LocalStrings
import com.israk.dhakaflix.ui.theme.TextDim
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(onOpen: (String) -> Unit) {
    val app = DhakaFlixApp.instance
    val s = LocalStrings.current
    val accent = LocalAccent.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    var query by rememberSaveable { mutableStateOf("") }
    var debounced by remember { mutableStateOf(query) }
    LaunchedEffect(query) { delay(350); debounced = query }
    var type by rememberSaveable { mutableStateOf<MediaType?>(null) }
    var year by rememberSaveable { mutableStateOf<Int?>(null) }
    var sort by rememberSaveable { mutableStateOf(SortMode.LATEST) }
    val years by remember { app.db.items().years() }.collectAsState(initial = emptyList())

    val filter = BrowseFilter(type = type, query = debounced, year = year, sort = sort)
    val items = rememberBrowsePager(filter).collectAsLazyPagingItems()

    PosterGrid(items, onOpen, emptyText = s.noResults) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(s.searchHint, color = TextDim) },
                    leadingIcon = { Icon(Icons.Filled.Search, null, tint = TextDim) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, null, tint = TextDim) }
                    },
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        keyboard?.hide(); focusManager.moveFocus(FocusDirection.Down)
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accent, unfocusedBorderColor = Color.White.copy(alpha = .18f),
                        focusedContainerColor = Color.White.copy(alpha = .06f), unfocusedContainerColor = Color.White.copy(alpha = .04f),
                        cursorColor = accent, focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    ),
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().entryFocus().onPreviewKeyEvent { e ->
                        // Let the D-pad leave the text field on TV
                        if (e.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                        when (e.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_DOWN -> { keyboard?.hide(); focusManager.moveFocus(FocusDirection.Down) }
                            KeyEvent.KEYCODE_DPAD_UP -> focusManager.moveFocus(FocusDirection.Up)
                            else -> false
                        }
                    },
                )
                FilterBar(
                    listOf(
                        FilterSpec(s.category, listOf<Pair<String?, String>>(null to s.all, MediaType.MOVIE.name to s.movies, MediaType.SERIES.name to s.series), type?.name) {
                            type = it?.let { v -> MediaType.valueOf(v) }
                        },
                        FilterSpec(s.year, listOf<Pair<String?, String>>(null to s.anyYear) + years.map { it.toString() to it.toString() }, year?.toString()) { year = it?.toInt() },
                        FilterSpec(s.sort, SortMode.entries.map { it.name to sortLabel(it, s) }, sort.name, highlight = false) { sort = SortMode.valueOf(it!!) },
                    ),
                    onClear = if (type != null || year != null) ({ type = null; year = null }) else null,
                )
            }
        }
    }
}
