package com.israk.dhakaflix.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.israk.dhakaflix.DhakaFlixApp
import com.israk.dhakaflix.player.PlayerActivity
import com.israk.dhakaflix.ui.LocalTopInset
import com.israk.dhakaflix.ui.entryFocus
import com.israk.dhakaflix.ui.components.dpadScrollFallback
import androidx.compose.foundation.lazy.rememberLazyListState
import com.israk.dhakaflix.ui.components.CenterMessage
import com.israk.dhakaflix.ui.components.LandscapeCard
import com.israk.dhakaflix.ui.components.MediaRow
import com.israk.dhakaflix.ui.components.PosterCard
import com.israk.dhakaflix.ui.theme.LocalEdge
import com.israk.dhakaflix.ui.theme.LocalIsTv
import com.israk.dhakaflix.ui.theme.LocalStrings
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(onOpen: (String) -> Unit) {
    val app = DhakaFlixApp.instance
    val repo = app.library
    val s = LocalStrings.current
    val isTv = LocalIsTv.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val cw by remember { repo.continueWatching() }.collectAsState(initial = emptyList())
    val watchlist by remember { app.db.user().watchlist() }.collectAsState(initial = emptyList())
    val watched by remember { app.db.user().watched() }.collectAsState(initial = emptyList())
    val posterW = if (isTv) 112.dp else 104.dp

    if (cw.isEmpty() && watchlist.isEmpty() && watched.isEmpty()) {
        CenterMessage(s.libraryEmpty, Modifier.padding(top = LocalTopInset.current))
        return
    }
    val listState = rememberLazyListState()
    com.israk.dhakaflix.ui.ReportScroll { listState.firstVisibleItemIndex > 1 }
    val entryMod = Modifier.entryFocus()
    LazyColumn(
        Modifier.fillMaxSize().dpadScrollFallback(listState),
        state = listState,
        contentPadding = PaddingValues(top = LocalTopInset.current + 8.dp, bottom = 56.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Text(
                s.library, Modifier.padding(start = LocalEdge.current, bottom = 8.dp),
                style = MaterialTheme.typography.headlineMedium, color = Color.White,
            )
        }
        item {
            MediaRow(s.continueWatching, cw, key = { it.progress.fileUrl }, modifier = entryMod) { e ->
                LandscapeCard(
                    e.item, e.progress.label, e.progress.fraction, if (isTv) 220.dp else 210.dp,
                    onClick = {
                        scope.launch {
                            val (list, idx) = repo.playlistFromProgress(e.progress)
                            PlayerActivity.start(ctx, list, idx, PlayerActivity.MODE_ASK)
                        }
                    },
                )
            }
        }
        item {
            MediaRow(s.myWatchlist, watchlist, key = { it.id }, modifier = if (cw.isEmpty()) entryMod else Modifier) { item ->
                PosterCard(item, posterW, onClick = { onOpen(item.id) }, menu = com.israk.dhakaflix.ui.components.CardMenu.LIBRARY)
            }
        }
        item {
            MediaRow(s.history, watched, key = { it.id }, modifier = if (cw.isEmpty() && watchlist.isEmpty()) entryMod else Modifier) { item ->
                PosterCard(item, posterW, watched = true, onClick = { onOpen(item.id) }, menu = com.israk.dhakaflix.ui.components.CardMenu.LIBRARY)
            }
        }
    }
}
