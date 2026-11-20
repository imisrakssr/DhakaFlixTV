package com.israk.dhakaflix.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.israk.dhakaflix.BuildConfig
import com.israk.dhakaflix.DhakaFlixApp
import com.israk.dhakaflix.R
import com.israk.dhakaflix.data.HomeRow
import com.israk.dhakaflix.data.MediaType
import com.israk.dhakaflix.data.RowKeys
import com.israk.dhakaflix.data.Source
import com.israk.dhakaflix.ui.LocalTopInset
import com.israk.dhakaflix.ui.components.PillButton
import com.israk.dhakaflix.ui.home.rowTitle
import com.israk.dhakaflix.ui.theme.Accents
import com.israk.dhakaflix.ui.theme.LocalAccent
import com.israk.dhakaflix.ui.theme.LocalEdge
import com.israk.dhakaflix.ui.theme.LocalStrings
import com.israk.dhakaflix.ui.theme.Surface1
import com.israk.dhakaflix.ui.theme.Surface2
import com.israk.dhakaflix.ui.theme.TextDim
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen() {
    val app = DhakaFlixApp.instance
    val st = app.settings
    val repo = app.library
    val s = LocalStrings.current
    val edge = LocalEdge.current

    val accent by st.accent.collectAsState()
    val lang by st.language.collectAsState()
    val rows by st.homeRows.collectAsState()
    val sources by st.sources.collectAsState()
    val engine by st.engine.collectAsState()
    val autoNext by st.autoNext.collectAsState()
    val subsDefault by st.subsDefault.collectAsState()
    val lastSync by st.lastSync.collectAsState()
    val sync by repo.sync.collectAsState()
    val count by remember { repo.itemCount }.collectAsState(initial = 0)
    val pending by remember { repo.pendingMeta }.collectAsState(initial = 0)
    val enriching by repo.enriching.collectAsState()
    var showAdd by remember { mutableStateOf(false) }

    // rows shown in the editor = saved rows + one optional row per category
    val allRows = remember(rows, sources) {
        val keys = rows.map { it.key }.toSet()
        rows + sources.filter { it.enabled && RowKeys.source(it.id) !in keys }.map { HomeRow(RowKeys.source(it.id), false) }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = edge, end = edge, top = LocalTopInset.current + 8.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text(s.settings, style = MaterialTheme.typography.headlineMedium, color = Color.White) }

        // ---------------- Library
        item { Section(s.library) }
        item {
            Card {
                val status = when {
                    sync.running -> s.scanning + "  " + s.scanProgress.format(sync.scannedFolders, sync.newItems)
                    else -> "${s.lastUpdated}: " + if (lastSync == 0L) s.never else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastSync))
                }
                Text("$count titles", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text(status, color = TextDim, style = MaterialTheme.typography.bodyMedium)
                if (pending > 0 && repo.tmdb.hasKey) {
                    Text(s.metadataPending.format(pending) + if (enriching) " …" else "", color = TextDim, style = MaterialTheme.typography.bodySmall)
                }
                if (sync.failedSources.isNotEmpty()) {
                    Text("⚠ " + sync.failedSources.joinToString(), color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton(s.refreshLibrary, onClick = { repo.startSync(false) }, icon = Icons.Filled.Refresh, primary = true)
                    PillButton(s.fullRescan, onClick = { repo.startSync(true) })
                }
            }
        }

        // ---------------- Appearance
        item { Section(s.appearance) }
        item { Column {
            Label(s.accentColor)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)) {
                itemsIndexed(Accents) { i, a ->
                    var f by remember { mutableStateOf(false) }
                    Box(
                        Modifier.size(if (f) 46.dp else 40.dp)
                            .onFocusChanged { f = it.isFocused }
                            .clip(CircleShape)
                            .background(a.color)
                            .border(3.dp, if (f) Color.White else Color.Transparent, CircleShape)
                            .clickable { st.setAccent(i) },
                        contentAlignment = Alignment.Center,
                    ) { if (accent == i) Icon(Icons.Filled.Check, null, tint = Color.White) }
                }
            }
        } }
        item { Column {
            Label(s.language)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                PillButton("English", onClick = { st.setLanguage("en") }, selected = lang == "en", compact = true)
                PillButton("বাংলা", onClick = { st.setLanguage("bn") }, selected = lang == "bn", compact = true)
                PillButton("ঢাকাইয়া 😎", onClick = { st.setLanguage("dhk") }, selected = lang == "dhk", compact = true)
            }
        } }

        // ---------------- Home rows
        item { Section(s.homeRows) }
        itemsIndexed(allRows, key = { _, r -> "row-" + r.key }) { i, row ->
            ToggleRow(
                title = rowTitle(row.key, s, sources),
                checked = row.enabled,
                onToggle = { st.setHomeRows(allRows.toMutableList().also { it[i] = row.copy(enabled = !row.enabled) }) },
                trailing = {
                    PillButton("", icon = Icons.Filled.KeyboardArrowUp, compact = true, onClick = {
                        if (i > 0) st.setHomeRows(allRows.toMutableList().also { l -> val t = l[i - 1]; l[i - 1] = l[i]; l[i] = t })
                    })
                    Spacer(Modifier.width(8.dp))
                    PillButton("", icon = Icons.Filled.KeyboardArrowDown, compact = true, onClick = {
                        if (i < allRows.lastIndex) st.setHomeRows(allRows.toMutableList().also { l -> val t = l[i + 1]; l[i + 1] = l[i]; l[i] = t })
                    })
                },
            )
        }

        // ---------------- Categories
        item { Section(s.categories) }
        itemsIndexed(sources, key = { _, src -> "src-" + src.id }) { i, src ->
            val sub = (if (src.type == MediaType.MOVIE) s.movies else s.series) + " · " + android.net.Uri.decode(src.url)
            val toggle = { st.setSources(sources.toMutableList().also { it[i] = src.copy(enabled = !src.enabled) }) }
            if (src.custom) {
                ToggleRow(title = src.name, subtitle = sub, checked = src.enabled, onToggle = toggle, trailing = {
                    PillButton("", icon = Icons.Filled.Delete, compact = true, onClick = { st.setSources(sources.filterNot { it.id == src.id }) })
                })
            } else {
                ToggleRow(title = src.name, subtitle = sub, checked = src.enabled, onToggle = toggle)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                PillButton(s.addCategory, onClick = { showAdd = true }, icon = Icons.Filled.Add)
                PillButton(s.resetDefaults, onClick = { st.resetSources() })
                PillButton(s.refreshLibrary, onClick = { repo.startSync(false) }, icon = Icons.Filled.Refresh)
            }
        }

        // ---------------- TMDB
        item { Section("TMDB") }
        item { TmdbKeyEditor() }

        // ---------------- Player
        item { Section(s.player) }
        item { Column {
            Label(s.playerEngine)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                PillButton(s.engineAuto, onClick = { st.setEngine("auto") }, selected = engine == "auto", compact = true)
                PillButton("ExoPlayer", onClick = { st.setEngine("exo") }, selected = engine == "exo", compact = true)
                PillButton("LibVLC", onClick = { st.setEngine("vlc") }, selected = engine == "vlc", compact = true)
            }
        } }
        item { ToggleRow(s.autoPlayNext, checked = autoNext, onToggle = { st.setAutoNext(!autoNext) }) }
        item { ToggleRow(s.subtitlesByDefault, checked = subsDefault, onToggle = { st.setSubsDefault(!subsDefault) }) }

        // ---------------- About
        item { Section(s.about) }
        item { AboutCard() }
    }

    if (showAdd) AddCategoryDialog(onDismiss = { showAdd = false }) { src ->
        st.setSources(sources + src)
        showAdd = false
        repo.startSync(false)
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(), Modifier.padding(top = 22.dp, bottom = 2.dp),
        color = LocalAccent.current, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun Label(text: String) {
    Text(text, color = TextDim, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.widthIn(max = 820.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).padding(18.dp)
    ) { content() }
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    onToggle: () -> Unit,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(if (focused) Color.White.copy(alpha = .14f) else Surface1, label = "row")
    val accent = LocalAccent.current
    Row(
        Modifier.widthIn(max = 820.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f)
                .onFocusChanged { focused = it.isFocused }
                .clip(RoundedCornerShape(14.dp))
                .background(bg)
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) Text(subtitle, color = TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            // switch visual
            Box(
                Modifier.size(width = 44.dp, height = 26.dp).clip(RoundedCornerShape(13.dp))
                    .background(if (checked) accent else Surface2).padding(3.dp),
                contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
            ) { Box(Modifier.size(20.dp).clip(CircleShape).background(Color.White)) }
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = LocalAccent.current, unfocusedBorderColor = Color.White.copy(alpha = .2f),
    focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = LocalAccent.current,
    focusedLabelColor = LocalAccent.current, unfocusedLabelColor = TextDim,
)

@Composable
private fun TmdbKeyEditor() {
    val app = DhakaFlixApp.instance
    val s = LocalStrings.current
    val saved by app.settings.tmdbKey.collectAsState()
    var text by remember(saved) { mutableStateOf(saved) }
    Card {
        Text(s.tmdbKeyHint, color = TextDim, style = MaterialTheme.typography.bodySmall)
        if (saved.isBlank() && BuildConfig.TMDB_API_KEY.isNotBlank()) {
            Text("✓ Built-in key active", color = TextDim, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = true,
            label = { Text(s.tmdbKey) }, colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        PillButton(s.save, primary = true, onClick = {
            app.settings.setTmdbKey(text)
            app.library.retryMetadata()
        })
    }
}

@Composable
private fun AddCategoryDialog(onDismiss: () -> Unit, onAdd: (Source) -> Unit) {
    val s = LocalStrings.current
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("http://172.16.50.") }
    var type by remember { mutableStateOf(MediaType.MOVIE) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(24.dp)
                .clip(RoundedCornerShape(20.dp)).background(Surface1).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(s.addCategory, style = MaterialTheme.typography.titleLarge, color = Color.White)
            OutlinedTextField(name, { name = it }, label = { Text(s.name) }, singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(url, { url = it }, label = { Text(s.url) }, singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(s.movies, onClick = { type = MediaType.MOVIE }, selected = type == MediaType.MOVIE, compact = true)
                PillButton(s.series, onClick = { type = MediaType.SERIES }, selected = type == MediaType.SERIES, compact = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(s.save, primary = true, onClick = {
                    val u = url.trim().let { if (it.endsWith("/")) it else "$it/" }
                    if (name.isNotBlank() && u.startsWith("http")) {
                        onAdd(Source("c" + System.currentTimeMillis(), name.trim(), u.replace(" ", "%20"), type, true, custom = true))
                    }
                })
                PillButton(s.cancel, onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun AboutCard() {
    val s = LocalStrings.current
    Card {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.logo_mark), null, Modifier.size(72.dp))
            Spacer(Modifier.height(10.dp))
            Image(painterResource(R.drawable.logo_wordmark), "DhakaFlix", Modifier.height(30.dp))
            Spacer(Modifier.height(6.dp))
            Text("DhakaFlix TV · ${s.version} ${BuildConfig.VERSION_NAME}", color = TextDim, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Text(
                "Made ❤️ by Israk Sisir", color = Color.White,
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Streams from DhakaFlix servers (172.16.50.x).\nThis product uses the TMDB API but is not endorsed or certified by TMDB.",
                color = TextDim, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            )
        }
    }
}
