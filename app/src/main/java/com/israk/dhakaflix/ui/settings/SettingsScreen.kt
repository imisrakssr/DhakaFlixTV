package com.israk.dhakaflix.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.israk.dhakaflix.data.tmdb.TmdbClient
import com.israk.dhakaflix.ui.LocalTopInset
import com.israk.dhakaflix.ui.components.AppIcons
import com.israk.dhakaflix.ui.components.PillButton
import com.israk.dhakaflix.ui.components.dpadScrollFallback
import com.israk.dhakaflix.ui.components.glass
import com.israk.dhakaflix.ui.entryFocus
import com.israk.dhakaflix.ui.home.rowTitle
import com.israk.dhakaflix.ui.theme.Accents
import com.israk.dhakaflix.ui.theme.LocalAccent
import com.israk.dhakaflix.ui.theme.LocalEdge
import com.israk.dhakaflix.ui.theme.LocalIsTv
import com.israk.dhakaflix.ui.theme.LocalStrings
import com.israk.dhakaflix.ui.theme.Strings
import com.israk.dhakaflix.ui.theme.Surface2
import com.israk.dhakaflix.ui.theme.TextDim
import java.text.DateFormat
import java.util.Date

private enum class Section { LIBRARY, APPEARANCE, HOME_ROWS, CATEGORIES, TMDB, PLAYER, ABOUT }

private fun Section.title(s: Strings) = when (this) {
    Section.LIBRARY -> s.library
    Section.APPEARANCE -> s.appearance
    Section.HOME_ROWS -> s.homeRows
    Section.CATEGORIES -> s.categories
    Section.TMDB -> "TMDB"
    Section.PLAYER -> s.player
    Section.ABOUT -> s.about
}

private fun Section.icon(): ImageVector = when (this) {
    Section.LIBRARY -> Icons.Filled.Refresh
    Section.APPEARANCE -> Icons.Filled.Star
    Section.HOME_ROWS -> Icons.Filled.Home
    Section.CATEGORIES -> AppIcons.Layers
    Section.TMDB -> AppIcons.Movie
    Section.PLAYER -> Icons.Filled.PlayArrow
    Section.ABOUT -> Icons.Filled.Info
}

@Composable
fun SettingsScreen() {
    val s = LocalStrings.current
    val isTv = LocalIsTv.current
    val edge = LocalEdge.current
    val top = LocalTopInset.current

    if (isTv) {
        var section by rememberSaveable { mutableStateOf(Section.LIBRARY) }
        // While focus is inside the right pane the section list can't be reached by up/down,
        // so reaching the end of a section never jumps to another one. LEFT goes back to the list.
        var inPane by remember { mutableStateOf(false) }
        val sectionFrs = remember { Section.entries.associateWith { FocusRequester() } }
        val fm = LocalFocusManager.current
        val scope = rememberCoroutineScope()
        // also lock the top/left nav while inside the pane: UP at the top goes nowhere
        val navLock = com.israk.dhakaflix.ui.LocalNavLock.current
        androidx.compose.runtime.LaunchedEffect(inPane) { navLock?.value = inPane }
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { navLock?.value = false } }
        Row(Modifier.fillMaxSize().padding(start = edge, end = edge, top = top + 6.dp)) {
            Column(Modifier.width(240.dp).fillMaxHeight()) {
                Text(s.settings, style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Spacer(Modifier.height(14.dp))
                Section.entries.forEachIndexed { i, sec ->
                    SectionItem(
                        sec, selected = sec == section, selectOnFocus = true,
                        modifier = (if (i == 0) Modifier.entryFocus() else Modifier)
                            .focusRequester(sectionFrs.getValue(sec))
                            .focusProperties { canFocus = !inPane },
                    ) { section = sec }
                    Spacer(Modifier.height(6.dp))
                }
            }
            Spacer(Modifier.width(28.dp))
            AnimatedContent(
                targetState = section,
                transitionSpec = { (fadeIn(tween(260)) + slideInHorizontally(tween(300)) { it / 16 }) togetherWith fadeOut(tween(150)) },
                modifier = Modifier.weight(1f).fillMaxHeight()
                    .onFocusChanged { inPane = it.hasFocus }
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown || e.key != Key.DirectionLeft) return@onPreviewKeyEvent false
                        if (fm.moveFocus(FocusDirection.Left)) return@onPreviewKeyEvent true
                        scope.launch {
                            inPane = false
                            delay(40)
                            runCatching { sectionFrs.getValue(section).requestFocus() }
                        }
                        true
                    },
                label = "settingsPane",
            ) { sec -> SectionContent(sec) }
        }
    } else {
        var section by rememberSaveable { mutableStateOf<Section?>(null) }
        BackHandler(enabled = section != null) { section = null }
        val cur = section
        if (cur == null) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = edge, end = edge, top = top + 8.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Text(s.settings, style = MaterialTheme.typography.headlineMedium, color = Color.White) }
                Section.entries.forEach { sec -> item { SectionItem(sec, selected = false, selectOnFocus = false) { section = sec } } }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(start = edge, end = edge, top = top)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { section = null }.padding(vertical = 8.dp)) {
                    Icon(Icons.Filled.ArrowBack, null, tint = Color.White)
                    Spacer(Modifier.width(12.dp))
                    Text(cur.title(s), style = MaterialTheme.typography.titleLarge, color = Color.White)
                }
                SectionContent(cur)
            }
        }
    }
}

@Composable
private fun SectionItem(sec: Section, selected: Boolean, selectOnFocus: Boolean, modifier: Modifier = Modifier, onSelect: () -> Unit) {
    val s = LocalStrings.current
    val accent = LocalAccent.current
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        when { focused -> Color.White; selected -> accent.copy(alpha = .28f); else -> Color.White.copy(alpha = .05f) }, label = "sec",
    )
    val fg = if (focused) Color.Black else Color.White
    Row(
        modifier.fillMaxWidth()
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused && selectOnFocus && !selected) onSelect()
            }
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(sec.icon(), null, tint = if (focused) Color.Black else if (selected) Color.White else TextDim, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(sec.title(s), Modifier.weight(1f), color = fg, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Icon(Icons.Filled.KeyboardArrowRight, null, tint = if (focused) Color.Black else TextDim, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SectionContent(sec: Section) {
    val state = rememberLazyListState()
    LazyColumn(
        Modifier.fillMaxSize().dpadScrollFallback(state),
        state = state,
        contentPadding = PaddingValues(top = if (LocalIsTv.current) 52.dp else 8.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (sec) {
            Section.LIBRARY -> item { LibraryCard() }
            Section.APPEARANCE -> item { AppearanceCard() }
            Section.HOME_ROWS -> homeRows(this)
            Section.CATEGORIES -> item { CategoriesCard() }
            Section.TMDB -> item { TmdbCard() }
            Section.PLAYER -> item { PlayerCard() }
            Section.ABOUT -> item { AboutCard() }
        }
    }
}

// ------------------------------------------------------------------ cards

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.widthIn(max = 760.dp).fillMaxWidth().glass(RoundedCornerShape(20.dp), tint = .06f).padding(20.dp)
    ) { content() }
}

@Composable
private fun Label(text: String) {
    Text(text, Modifier.padding(top = 6.dp, bottom = 6.dp), color = TextDim, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun LibraryCard() {
    val app = DhakaFlixApp.instance
    val repo = app.library
    val s = LocalStrings.current
    val lastSync by app.settings.lastSync.collectAsState()
    val sync by repo.sync.collectAsState()
    val count by remember { repo.itemCount }.collectAsState(initial = 0)
    val pending by remember { repo.pendingMeta }.collectAsState(initial = 0)
    val enriching by repo.enriching.collectAsState()
    Card {
        Text("$count", color = Color.White, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(s.movies + " + " + s.series, color = TextDim, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(10.dp))
        val status = if (sync.running) s.scanning + "  " + s.scanProgress.format(sync.scannedFolders, sync.newItems)
        else "${s.lastUpdated}: " + if (lastSync == 0L) s.never else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastSync))
        Text(status, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        if (pending > 0 && repo.tmdb.hasKey) {
            Text(s.metadataPending.format(pending) + if (enriching) " …" else "", color = TextDim, style = MaterialTheme.typography.bodySmall)
        }
        if (sync.failedSources.isNotEmpty()) {
            Text("⚠ " + sync.failedSources.joinToString(), color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        Text(s.autoRefreshNote, color = TextDim, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(s.refreshLibrary, onClick = { repo.startSync(false) }, icon = Icons.Filled.Refresh, primary = true)
            PillButton(s.fullRescan, onClick = { repo.startSync(true) })
        }
    }
}

@Composable
private fun AppearanceCard() {
    val st = DhakaFlixApp.instance.settings
    val s = LocalStrings.current
    val isTv = LocalIsTv.current
    val accent by st.accent.collectAsState()
    val lang by st.language.collectAsState()
    val nav by st.navPosition.collectAsState()
    val size by st.uiSize.collectAsState()
    Card {
        Label(s.accentColor)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)) {
            itemsIndexed(Accents) { i, a ->
                var f by remember { mutableStateOf(false) }
                Box(
                    Modifier.size(if (f) 44.dp else 38.dp)
                        .onFocusChanged { f = it.isFocused }
                        .clip(CircleShape)
                        .background(a.color)
                        .border(3.dp, if (f) Color.White else Color.Transparent, CircleShape)
                        .clickable { st.setAccent(i) },
                    contentAlignment = Alignment.Center,
                ) { if (accent == i) Icon(Icons.Filled.Check, null, tint = Color.White) }
            }
        }
        Label(s.language)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("English", onClick = { st.setLanguage("en") }, selected = lang == "en", compact = true)
            PillButton("বাংলা", onClick = { st.setLanguage("bn") }, selected = lang == "bn", compact = true)
            PillButton("ঢাকাইয়া 😎", onClick = { st.setLanguage("dhk") }, selected = lang == "dhk", compact = true)
        }
        if (isTv) {
            Label(s.navigation)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(s.navTop, onClick = { st.setNavPosition("top") }, selected = nav == "top", compact = true)
                PillButton(s.navLeft, onClick = { st.setNavPosition("left") }, selected = nav == "left", compact = true)
            }
        }
        Label(s.uiSize)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(s.sizeSmall, onClick = { st.setUiSize(0) }, selected = size == 0, compact = true)
            PillButton(s.sizeMedium, onClick = { st.setUiSize(1) }, selected = size == 1, compact = true)
            PillButton(s.sizeLarge, onClick = { st.setUiSize(2) }, selected = size == 2, compact = true)
        }
    }
}

private fun homeRows(scope: LazyListScope) {
    scope.item { HomeRowsEditor() }
}

@Composable
private fun HomeRowsEditor() {
    val st = DhakaFlixApp.instance.settings
    val s = LocalStrings.current
    val rows by st.homeRows.collectAsState()
    val sources by st.sources.collectAsState()
    val allRows = remember(rows, sources) {
        val keys = rows.map { it.key }.toSet()
        rows + sources.filter { it.enabled && RowKeys.source(it.id) !in keys }.map { HomeRow(RowKeys.source(it.id), false) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        allRows.forEachIndexed { i, row ->
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
    }
}

@Composable
private fun CategoriesCard() {
    val app = DhakaFlixApp.instance
    val st = app.settings
    val s = LocalStrings.current
    val sources by st.sources.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        sources.forEachIndexed { i, src ->
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
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
            PillButton(s.addCategory, onClick = { showAdd = true }, icon = Icons.Filled.Add)
            PillButton(s.resetDefaults, onClick = { st.resetSources() })
            PillButton(s.refreshLibrary, onClick = { app.library.startSync(false) }, icon = Icons.Filled.Refresh)
        }
    }
    if (showAdd) AddCategoryDialog(onDismiss = { showAdd = false }) { src ->
        st.setSources(sources + src)
        showAdd = false
        app.library.startSync(false)
    }
}

@Composable
private fun TmdbCard() {
    val app = DhakaFlixApp.instance
    val repo = app.library
    val s = LocalStrings.current
    val status by repo.tmdbStatus.collectAsState()
    val saved by app.settings.tmdbKey.collectAsState()
    val builtIn = BuildConfig.TMDB_API_KEY
    val effective = saved.ifBlank { builtIn }
    var editing by remember { mutableStateOf(false) }
    var text by remember(saved) { mutableStateOf(saved) }

    val (dot, label) = when (status) {
        TmdbClient.Status.CONNECTED -> Color(0xFF34D399) to s.tmdbConnected
        TmdbClient.Status.INVALID -> Color(0xFFF87171) to s.tmdbInvalid
        TmdbClient.Status.OFFLINE -> Color(0xFFFBBF24) to s.tmdbOffline
        TmdbClient.Status.CHECKING -> Color(0xFF93C5FD) to s.tmdbChecking
        TmdbClient.Status.NOT_SET -> TextDim to s.tmdbNotSet
    }
    Card {
        Text(s.tmdbKey, color = Color.White, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(10.dp))
            Text(label, color = Color.White, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
        if (effective.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "••••••••" + effective.takeLast(4) + if (saved.isBlank()) "  (built-in)" else "",
                color = TextDim, style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(16.dp))
        if (editing || effective.isBlank()) {
            Text(s.tmdbKeyHint, color = TextDim, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                label = { Text(s.tmdbKey) }, colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(s.save, primary = true, icon = Icons.Filled.Check, onClick = {
                    app.settings.setTmdbKey(text)
                    editing = false
                    repo.verifyTmdb()
                    repo.retryMetadata()
                })
                if (effective.isNotBlank()) PillButton(s.cancel, onClick = { editing = false; text = saved })
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(s.edit, icon = Icons.Filled.Edit, primary = true, onClick = { editing = true })
                PillButton(s.testAgain, icon = Icons.Filled.Refresh, onClick = { repo.verifyTmdb() })
                if (saved.isNotBlank()) PillButton(s.remove, icon = Icons.Filled.Delete, onClick = {
                    app.settings.setTmdbKey(""); text = ""; repo.verifyTmdb()
                })
            }
        }
    }
}

@Composable
private fun PlayerCard() {
    val st = DhakaFlixApp.instance.settings
    val s = LocalStrings.current
    val engine by st.engine.collectAsState()
    val autoNext by st.autoNext.collectAsState()
    val subsDefault by st.subsDefault.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card {
            Label(s.playerEngine)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(s.engineAuto, onClick = { st.setEngine("auto") }, selected = engine == "auto", compact = true)
                PillButton("ExoPlayer", onClick = { st.setEngine("exo") }, selected = engine == "exo", compact = true)
                PillButton("LibVLC", onClick = { st.setEngine("vlc") }, selected = engine == "vlc", compact = true)
            }
        }
        ToggleRow(s.autoPlayNext, checked = autoNext, onToggle = { st.setAutoNext(!autoNext) })
        ToggleRow(s.subtitlesByDefault, checked = subsDefault, onToggle = { st.setSubsDefault(!subsDefault) })
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
            Spacer(Modifier.height(18.dp))
            Text("Made ❤️ by Israk Sisir", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))
            Text(
                "Streams from DhakaFlix servers (172.16.50.x).\nThis product uses the TMDB API but is not endorsed or certified by TMDB.",
                color = TextDim, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            )
        }
    }
}

// ------------------------------------------------------------------ bits

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    onToggle: () -> Unit,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(if (focused) Color.White.copy(alpha = .16f) else Color.White.copy(alpha = .05f), label = "row")
    val accent = LocalAccent.current
    Row(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f)
                .onFocusChanged { focused = it.isFocused }
                .clip(RoundedCornerShape(14.dp))
                .background(bg)
                .border(1.dp, if (focused) Color.White.copy(alpha = .6f) else Color.White.copy(alpha = .08f), RoundedCornerShape(14.dp))
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) Text(subtitle, color = TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
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
private fun AddCategoryDialog(onDismiss: () -> Unit, onAdd: (Source) -> Unit) {
    val s = LocalStrings.current
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("http://172.16.50.") }
    var type by remember { mutableStateOf(MediaType.MOVIE) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(24.dp)
                .clip(RoundedCornerShape(20.dp)).background(Color(0xFF15181F)).padding(22.dp),
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
