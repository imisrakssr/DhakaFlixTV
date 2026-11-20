package com.israk.dhakaflix.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.israk.dhakaflix.DhakaFlixApp
import com.israk.dhakaflix.data.NameParser
import com.israk.dhakaflix.data.db.ItemEntity
import com.israk.dhakaflix.data.tmdb.TmdbImages
import com.israk.dhakaflix.data.tmdb.TmdbResult
import com.israk.dhakaflix.ui.components.FocusFrame
import com.israk.dhakaflix.ui.components.PillButton
import com.israk.dhakaflix.ui.theme.LocalAccent
import com.israk.dhakaflix.ui.theme.LocalStrings
import com.israk.dhakaflix.ui.theme.Surface1
import com.israk.dhakaflix.ui.theme.Surface2
import com.israk.dhakaflix.ui.theme.TextDim
import kotlinx.coroutines.launch

@Composable
fun FixMatchDialog(item: ItemEntity, onDismiss: () -> Unit) {
    val repo = DhakaFlixApp.instance.library
    val s = LocalStrings.current
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(NameParser.searchQuery(item.title)) }
    var year by remember { mutableStateOf(item.year?.toString() ?: "") }
    var results by remember { mutableStateOf<List<TmdbResult>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    suspend fun runSearch() {
        busy = true
        results = repo.searchTmdb(item.isSeries, query, year.toIntOrNull())
        busy = false
    }
    LaunchedEffect(Unit) { runSearch() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().fillMaxHeight(.9f).padding(16.dp)
                .clip(RoundedCornerShape(20.dp)).background(Surface1).padding(20.dp),
        ) {
            Text(s.fixMatch, style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text(item.rawName, style = MaterialTheme.typography.bodySmall, color = TextDim, maxLines = 1)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accent, unfocusedBorderColor = Color.White.copy(alpha = .2f),
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = accent,
                )
                OutlinedTextField(query, { query = it }, singleLine = true, colors = colors, modifier = Modifier.weight(1f))
                OutlinedTextField(year, { v -> year = v.filter { it.isDigit() }.take(4) }, singleLine = true, colors = colors,
                    modifier = Modifier.width(110.dp), placeholder = { Text("Year", color = TextDim) })
                PillButton(s.searchTmdb, icon = Icons.Filled.Search, primary = true, compact = true, onClick = { scope.launch { runSearch() } })
            }
            Spacer(Modifier.height(12.dp))
            if (busy) Text(s.loading, color = TextDim)
            if (!busy && results.isEmpty()) Text(if (repo.tmdb.hasKey) s.noResults else s.tmdbKeyHint, color = TextDim)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(results, key = { it.id }) { r ->
                    FocusFrame(
                        onClick = { scope.launch { repo.applyManualMatch(item.id, r); onDismiss() } },
                        focusedScale = 1.02f, modifier = Modifier.fillMaxWidth(),
                    ) { focused ->
                        Row(
                            Modifier.fillMaxWidth().background(if (focused) Color.White.copy(alpha = .12f) else Surface2).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(54.dp, 80.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
                                AsyncImage(TmdbImages.url(r.posterPath, "w154"), null, Modifier.size(54.dp, 80.dp), contentScale = ContentScale.Crop)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.displayTitle + (r.year?.let { " ($it)" } ?: ""), color = Color.White, style = MaterialTheme.typography.titleSmall)
                                Text(r.overview ?: "", color = TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            PillButton(s.cancel, onClick = onDismiss, compact = true)
        }
    }
}
