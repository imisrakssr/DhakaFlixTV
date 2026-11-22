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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.israk.dhakaflix.ui.home.defaultRowTitle
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

private enum class Section { LIBRARY, APPEARANCE, HOME_ROWS, CATEGORIES, ACCOUNTS, PLAYER, DIAGNOSTICS, ABOUT }

private fun Section.title(s: Strings) = when (this) {
    Section.LIBRARY -> s.library
    Section.APPEARANCE -> s.appearance
    Section.HOME_ROWS -> s.homeRows
    Section.CATEGORIES -> s.categories
    Section.ACCOUNTS -> s.accounts
    Section.PLAYER -> s.player
    Section.DIAGNOSTICS -> s.diagnostics
    Section.ABOUT -> s.about
}

private fun Section.icon(): ImageVector = when (this) {
    Section.LIBRARY -> Icons.Filled.Refresh
    Section.APPEARANCE -> Icons.Filled.Star
    Section.HOME_ROWS -> Icons.Filled.Home
    Section.CATEGORIES -> AppIcons.Layers
    Section.ACCOUNTS -> Icons.Filled.AccountCircle
    Section.PLAYER -> Icons.Filled.PlayArrow
    Section.DIAGNOSTICS -> Icons.Filled.Build
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
        val leaving = remember { booleanArrayOf(false) }
        val sectionFrs = remember { Section.entries.associateWith { FocusRequester() } }
        val fm = LocalFocusManager.current
        val scope = rememberCoroutineScope()
        // also lock the top/left nav while inside the pane: UP at the top goes nowhere
        val navLock = com.israk.dhakaflix.ui.LocalNavLock.current
        androidx.compose.runtime.LaunchedEffect(inPane) { navLock?.value = inPane }
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { navLock?.value = false } }
        Row(Modifier.fillMaxSize().padding(start = edge, end = edge, top = top + 6.dp)) {
            // the section list scrolls (9 entries don't fit under the menu on a 1080p TV);
            // focusing an entry brings it into view, so Diagnostics / About are always reachable
            Column(
                Modifier.width(240.dp).fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 40.dp)
            ) {
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
                        // already on the way back to the list: swallow repeated LEFTs, otherwise the
                        // second one lands on whatever entry sits beside the button and switches section
                        if (leaving[0]) return@onPreviewKeyEvent true
                        if (fm.moveFocus(FocusDirection.Left)) return@onPreviewKeyEvent true
                        leaving[0] = true
                        scope.launch {
                            inPane = false
                            delay(40)
                            runCatching { sectionFrs.getValue(section).requestFocus() }
                            delay(160)
                            leaving[0] = false
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
    val tv = LocalIsTv.current
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
            Section.ACCOUNTS -> {
                if (tv) item { PhoneSetupCard() }
                item { TmdbCard() }
                item { MdbListCard() }
                item { SimklCard() }
            }
            Section.PLAYER -> item { PlayerCard() }
            Section.DIAGNOSTICS -> item { DiagnosticsCard() }
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
    val lang by st.language.collectAsState()
    val nav by st.navPosition.collectAsState()
    val size by st.uiSize.collectAsState()
    Card {
        Label(s.language)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("English", onClick = { st.setLanguage("en") }, selected = lang == "en", compact = true)
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
        val perRow by st.postersPerRow.collectAsState()
        Label(s.postersPerRow)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(0, 5, 6, 7, 8, 9).forEach { n ->
                PillButton(if (n == 0) s.auto else "$n", onClick = { st.setPostersPerRow(n) }, selected = perRow == n, compact = true)
            }
        }
        AppIconPicker()
        val saver by st.screensaver.collectAsState()
        Label(s.screensaver)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(0, 2, 5, 10, 15).forEach { n ->
                PillButton(if (n == 0) s.off else s.minutesShort.format(n), onClick = { st.setScreensaver(n) }, selected = saver == n, compact = true)
            }
        }
        if (isTv) {
            Spacer(Modifier.height(12.dp))
            val bgT by st.bgTrailers.collectAsState()
            ToggleRow(s.bgTrailers, checked = bgT, onToggle = { st.setBgTrailers(!bgT) })
        }
    }
}

@Composable
private fun AppIconPicker() {
    val st = DhakaFlixApp.instance.settings
    val s = LocalStrings.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val current by st.appIcon.collectAsState()
    Label(s.appIcon)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)) {
        items(com.israk.dhakaflix.data.LauncherIcons.variants.size) { i ->
            val v = com.israk.dhakaflix.data.LauncherIcons.variants[i]
            var f by remember { mutableStateOf(false) }
            val res = remember(v) { ctx.resources.getIdentifier("ic_launcher_$v", "mipmap", ctx.packageName) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(if (f) 64.dp else 58.dp)
                        .onFocusChanged { f = it.isFocused }
                        .clip(RoundedCornerShape(16.dp))
                        .border(3.dp, if (f) Color.White else if (current == v) LocalAccent.current else Color.Transparent, RoundedCornerShape(16.dp))
                        .clickable {
                            st.setAppIcon(v)
                            com.israk.dhakaflix.data.LauncherIcons.apply(ctx, v)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (res != 0) Image(painterResource(res), null, Modifier.fillMaxSize())
                    if (current == v) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(18.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(com.israk.dhakaflix.data.LauncherIcons.label(v), color = if (current == v) Color.White else TextDim, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    Text(s.appIconNote, color = TextDim, style = MaterialTheme.typography.bodySmall)
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
    val names by st.rowNames.collectAsState()
    var renaming by remember { mutableStateOf<String?>(null) }
    renaming?.let { key ->
        RenameDialog(
            current = names[key] ?: defaultRowTitle(key, s, sources),
            onDismiss = { renaming = null },
            onReset = { st.setRowName(key, null); renaming = null },
        ) { st.setRowName(key, it); renaming = null }
    }
    val allRows = remember(rows, sources) {
        val keys = rows.map { it.key }.toSet()
        rows + sources.filter { it.enabled && RowKeys.source(it.id) !in keys }.map { HomeRow(RowKeys.source(it.id), false) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        allRows.forEachIndexed { i, row ->
            ToggleRow(
                title = names[row.key] ?: rowTitle(row.key, s, sources),
                checked = row.enabled,
                onToggle = { st.setHomeRows(allRows.toMutableList().also { it[i] = row.copy(enabled = !row.enabled) }) },
                trailing = {
                    PillButton("", icon = Icons.Filled.Edit, compact = true, onClick = { renaming = row.key })
                    Spacer(Modifier.width(8.dp))
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
    var renaming by remember { mutableStateOf<String?>(null) }
    renaming?.let { id ->
        val src = sources.firstOrNull { it.id == id }
        if (src == null) renaming = null else RenameDialog(
            current = src.name,
            onDismiss = { renaming = null },
            onReset = {
                val def = com.israk.dhakaflix.data.Defaults.sources.firstOrNull { it.id == id }?.name
                if (def != null) st.setSources(sources.map { if (it.id == id) it.copy(name = def) else it })
                renaming = null
            },
        ) { n -> st.setSources(sources.map { if (it.id == id) it.copy(name = n) else it }); renaming = null }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        sources.forEachIndexed { i, src ->
            val sub = (if (src.type == MediaType.MOVIE) s.movies else s.series) + " · " + android.net.Uri.decode(src.url)
            val toggle = { st.setSources(sources.toMutableList().also { it[i] = src.copy(enabled = !src.enabled) }) }
            if (src.custom) {
                ToggleRow(title = src.name, subtitle = sub, checked = src.enabled, onToggle = toggle, trailing = {
                    PillButton("", icon = Icons.Filled.Edit, compact = true, onClick = { renaming = src.id })
                    Spacer(Modifier.width(8.dp))
                    PillButton("", icon = Icons.Filled.Delete, compact = true, onClick = { st.setSources(sources.filterNot { it.id == src.id }) })
                })
            } else {
                ToggleRow(title = src.name, subtitle = sub, checked = src.enabled, onToggle = toggle, trailing = {
                    PillButton("", icon = Icons.Filled.Edit, compact = true, onClick = { renaming = src.id })
                })
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
        val audioDelay by st.audioDelay.collectAsState()
        val subDelay by st.subDelay.collectAsState()
        Card {
            Label(s.defaultSync)
            SyncRow(s.audioSync, audioDelay, step = 10) { v -> st.setAudioDelay(v ?: com.israk.dhakaflix.data.AppSettings.DEFAULT_AUDIO_DELAY) }
            Spacer(Modifier.height(10.dp))
            SyncRow(s.subtitleSync, subDelay, step = 100) { v -> st.setSubDelay(v ?: 0L) }
        }
        val subSize by st.subSize.collectAsState()
        val subColor by st.subColor.collectAsState()
        Card {
            Label(s.subtitleSize)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(s.sizeSmall, s.sizeMedium, s.sizeLarge, "XL").forEachIndexed { i, l ->
                    PillButton(l, onClick = { st.setSubSize(i) }, selected = subSize == i, compact = true)
                }
            }
            Spacer(Modifier.height(10.dp))
            Label(s.subtitleColor)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                com.israk.dhakaflix.data.AppSettings.SUB_COLORS.forEachIndexed { i, (_, name) ->
                    PillButton(name, onClick = { st.setSubColor(i) }, selected = subColor == i, compact = true)
                }
            }
        }
        val hideAfter by st.controlsTimeout.collectAsState()
        val pauseAfter by st.pauseOverlay.collectAsState()
        Card {
            Label(s.controlsHide)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(3, 5, 8, 10, 15).forEach { n -> PillButton("${n}s", onClick = { st.setControlsTimeout(n) }, selected = hideAfter == n, compact = true) }
            }
            Spacer(Modifier.height(10.dp))
            Label(s.pauseInfo)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(0, 5, 10, 20, 30).forEach { n ->
                    PillButton(if (n == 0) s.off else "${n}s", onClick = { st.setPauseOverlay(n) }, selected = pauseAfter == n, compact = true)
                }
            }
        }
        val autoSkip by st.autoSkipIntro.collectAsState()
        val night by st.nightMode.collectAsState()
        ToggleRow(s.autoSkipIntro, subtitle = s.skipIntroHint, checked = autoSkip, onToggle = { st.setAutoSkipIntro(!autoSkip) })
        ToggleRow(s.nightMode, subtitle = s.nightModeHint, checked = night, onToggle = { st.setNightMode(!night) })
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

/** "Audio sync   −   -0.03 s   +   Reset" — value in ms; null = reset to default. */
@Composable
private fun SyncRow(title: String, valueMs: Long, step: Long, onChange: (Long?) -> Unit) {
    val s = LocalStrings.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, Modifier.width(170.dp), color = Color.White, style = MaterialTheme.typography.bodyLarge)
        PillButton("−", compact = true, onClick = { onChange(valueMs - step) })
        Text(
            String.format(java.util.Locale.US, "%+.2f s", valueMs / 1000.0),
            Modifier.width(86.dp), color = Color.White, style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center, fontWeight = FontWeight.Bold,
        )
        PillButton("+", compact = true, onClick = { onChange(valueMs + step) })
        PillButton(s.resetDefault, compact = true, onClick = { onChange(null) })
    }
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
                if (subtitle != null) Text(subtitle, color = TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 2)
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

/** Small dialog to rename a home row or a server category. */
@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onReset: () -> Unit, onSave: (String) -> Unit) {
    val s = LocalStrings.current
    var name by remember { mutableStateOf(current) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(24.dp)
                .clip(RoundedCornerShape(20.dp)).background(Color(0xFF15181F)).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(current, style = MaterialTheme.typography.titleLarge, color = Color.White)
            OutlinedTextField(name, { name = it }, label = { Text(s.name) }, singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(s.save, primary = true, onClick = { if (name.isNotBlank()) onSave(name.trim()) })
                PillButton(s.resetDefaults, onClick = onReset)
                PillButton(s.cancel, onClick = onDismiss)
            }
        }
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


// ------------------------------------------------------------------ accounts

@Composable
private fun StatusLine(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** QR → phone web page on the same Wi-Fi for typing keys, backups and diagnostics. */
@Composable
private fun PhoneSetupCard() {
    val app = DhakaFlixApp.instance
    val s = LocalStrings.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        com.israk.dhakaflix.data.accounts.SetupServer.acquire()
        onDispose { com.israk.dhakaflix.data.accounts.SetupServer.release() }
    }
    val address by com.israk.dhakaflix.data.accounts.SetupServer.address.collectAsState()
    val event by com.israk.dhakaflix.data.accounts.SetupServer.event.collectAsState()
    var note by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(event) {
        when (val e = event) {
            is com.israk.dhakaflix.data.accounts.SetupServer.Event.Saved -> {
                note = "✓ " + e.what.joinToString()
                if ("TMDB" in e.what) { app.library.verifyTmdb(); app.library.retryMetadata() }
                if ("MDBList" in e.what) app.library.mdblist.verify()
                com.israk.dhakaflix.data.accounts.SetupServer.consumeEvent()
            }
            com.israk.dhakaflix.data.accounts.SetupServer.Event.Restored -> {
                note = "✓ Backup restored — restarting…"
                delay(1500)
                val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                    ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                if (i != null) ctx.startActivity(i)
                Runtime.getRuntime().exit(0)
            }
            null -> Unit
        }
    }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val a = address
            if (a != null) QrCodeBox(a)
            Spacer(Modifier.width(22.dp))
            Column(Modifier.weight(1f)) {
                Text(s.phoneSetup, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(s.phoneSetupHint, color = TextDim, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                if (a != null) {
                    Text(s.orOpen, color = TextDim, style = MaterialTheme.typography.bodySmall)
                    Text(a, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                } else Text("Wi-Fi?", color = Color(0xFFFBBF24), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(s.backupHint, color = TextDim, style = MaterialTheme.typography.bodySmall)
                note?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Color(0xFF34D399), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun QrCodeBox(text: String) = com.israk.dhakaflix.ui.components.QrCode(text, size = 150.dp)

/** Reusable "API key" card: status, masked key, edit / test / remove. */
@Composable
private fun KeyCard(
    title: String,
    hint: String,
    saved: String,
    status: Pair<Color, String>,
    onSave: (String) -> Unit,
    onTest: () -> Unit,
) {
    val s = LocalStrings.current
    var editing by remember { mutableStateOf(false) }
    var text by remember(saved) { mutableStateOf(saved) }
    Card {
        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(hint, color = TextDim, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        StatusLine(status.first, status.second)
        if (saved.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text("••••••••" + saved.takeLast(4), color = TextDim, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(14.dp))
        if (editing) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                label = { Text(title) }, colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(s.save, primary = true, icon = Icons.Filled.Check, onClick = { onSave(text); editing = false })
                PillButton(s.cancel, onClick = { editing = false; text = saved })
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(s.edit, icon = Icons.Filled.Edit, primary = saved.isBlank(), onClick = { editing = true })
                if (saved.isNotBlank()) {
                    PillButton(s.testAgain, icon = Icons.Filled.Refresh, onClick = onTest)
                    PillButton(s.remove, icon = Icons.Filled.Delete, onClick = { onSave(""); text = "" })
                }
            }
        }
    }
}

@Composable
private fun MdbListCard() {
    val app = DhakaFlixApp.instance
    val s = LocalStrings.current
    val saved by app.settings.mdblistKey.collectAsState()
    val st by app.library.mdblist.status.collectAsState()
    val status = when (st) {
        com.israk.dhakaflix.data.accounts.MdbList.Status.CONNECTED -> Color(0xFF34D399) to s.tmdbConnected
        com.israk.dhakaflix.data.accounts.MdbList.Status.INVALID -> Color(0xFFF87171) to s.tmdbInvalid.replace("TMDB", "MDBList")
        com.israk.dhakaflix.data.accounts.MdbList.Status.OFFLINE -> Color(0xFFFBBF24) to s.tmdbOffline.replace("TMDB", "MDBList")
        com.israk.dhakaflix.data.accounts.MdbList.Status.CHECKING -> Color(0xFF93C5FD) to s.tmdbChecking
        com.israk.dhakaflix.data.accounts.MdbList.Status.NOT_SET -> TextDim to s.tmdbNotSet
    }
    KeyCard("MDBList", s.mdblistHint, saved, status,
        onSave = { app.settings.setMdblistKey(it); app.library.mdblist.verify() },
        onTest = { app.library.mdblist.verify() })
}

@Composable
private fun SimklCard() {
    val app = DhakaFlixApp.instance
    val s = LocalStrings.current
    val simkl = app.library.simkl
    val token by app.settings.simklToken.collectAsState()
    val user by app.settings.simklUser.collectAsState()
    val clientSaved by app.settings.simklClientId.collectAsState()
    val pin by simkl.pin.collectAsState()
    val syncing by simkl.syncing.collectAsState()
    val error by simkl.error.collectAsState()
    val last by app.settings.simklLastSync.collectAsState()
    val hasClient = clientSaved.isNotBlank() || BuildConfig.SIMKL_CLIENT_ID.isNotBlank()
    var editClient by remember { mutableStateOf(false) }
    var clientText by remember(clientSaved) { mutableStateOf(clientSaved) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { simkl.cancelLogin() } }

    Card {
        Text("Simkl", color = Color.White, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(s.simklHint, color = TextDim, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        val p = pin
        when {
            token.isNotBlank() && hasClient -> {
                StatusLine(Color(0xFF34D399), s.signedInAs.format(user.ifBlank { "Simkl" }))
                Spacer(Modifier.height(4.dp))
                Text(
                    "${s.lastSynced}: " + (if (last == 0L) s.never else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last))) +
                        if (syncing) "  …" else "",
                    color = TextDim, style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton(s.syncNow, icon = Icons.Filled.Refresh, primary = true, onClick = { simkl.syncNow() })
                    PillButton(s.signOut, onClick = { simkl.signOut() })
                }
            }
            p != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.israk.dhakaflix.ui.components.QrCode(p.link, size = 130.dp)
                    Spacer(Modifier.width(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.scanToSignIn.format(p.url.removePrefix("https://")), color = TextDim, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(p.code, color = Color.White, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(s.waitingApproval, color = Color(0xFF93C5FD), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(10.dp))
                        PillButton(s.cancel, compact = true, onClick = { simkl.cancelLogin() })
                    }
                }
            }
            else -> {
                StatusLine(TextDim, s.tmdbNotSet)
                if (!hasClient) {
                    Spacer(Modifier.height(6.dp))
                    Text(s.simklNeedsClient, color = Color(0xFFFBBF24), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(14.dp))
                if (editClient) {
                    OutlinedTextField(clientText, { clientText = it }, singleLine = true, label = { Text("Simkl Client ID") },
                        colors = fieldColors(), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillButton(s.save, primary = true, icon = Icons.Filled.Check, onClick = { app.settings.setSimklClientId(clientText); editClient = false })
                        PillButton(s.cancel, onClick = { editClient = false; clientText = clientSaved })
                    }
                } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (hasClient) PillButton(s.signIn, primary = true, onClick = { simkl.startLogin() })
                    PillButton("Client ID", icon = Icons.Filled.Edit, onClick = { editClient = true })
                }
            }
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text("⚠ $it", color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ------------------------------------------------------------------ diagnostics

@Composable
private fun DiagnosticsCard() {
    val s = LocalStrings.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val isTv = LocalIsTv.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<com.israk.dhakaflix.data.DiagReport?>(null) }
    var running by remember { mutableStateOf(false) }
    var errors by remember { mutableStateOf(com.israk.dhakaflix.data.DiagLog.entries()) }
    fun run() {
        if (running) return
        running = true
        scope.launch {
            report = runCatching { com.israk.dhakaflix.data.Diagnostics.run(ctx) }.getOrNull()
            errors = com.israk.dhakaflix.data.DiagLog.entries()
            running = false
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { run() }
    val r = report
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(if (running) s.loading else s.runAgain, icon = Icons.Filled.Refresh, primary = true, onClick = { run() })
                PillButton(s.clearCache, icon = Icons.Filled.Delete, onClick = {
                    scope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ctx.cacheDir.resolve("images").deleteRecursively() }
                        run()
                    }
                })
                if (!isTv && r != null) PillButton("Share", onClick = {
                    val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT, r.asText())
                    ctx.startActivity(android.content.Intent.createChooser(i, "DhakaFlix diagnostics"))
                })
            }
        }
        if (r != null) {
            DiagSection(s.servers, r.servers.map { (h, ok, d) -> Triple(h, d, ok) })
            DiagSection(s.network, r.network.map { Triple(it.first, it.second, null) })
            DiagSection(s.decoders, r.codecs.map { Triple(it.first, it.second, if (it.second.startsWith("✗")) false else null) })
            DiagSection(s.library, r.library.map { Triple(it.first, it.second, null) })
            DiagSection(s.device, r.device.map { Triple(it.first, it.second, null) })
        }
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.recentErrors, Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.titleMedium)
                if (errors.isNotEmpty()) PillButton(s.delete, compact = true, icon = Icons.Filled.Delete, onClick = {
                    com.israk.dhakaflix.data.DiagLog.clear(); errors = emptyList()
                })
            }
            Spacer(Modifier.height(8.dp))
            if (errors.isEmpty()) Text(s.noErrors, color = TextDim, style = MaterialTheme.typography.bodyMedium)
            val fmt = remember { java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault()) }
            errors.take(15).forEach { e ->
                Text(
                    fmt.format(Date(e.at)) + "  ·  " + e.tag, color = TextDim, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(e.msg, color = Color.White, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            }
        }
    }
}

@Composable
private fun DiagSection(title: String, rows: List<Triple<String, String, Boolean?>>) {
    if (rows.isEmpty()) return
    // focusable so D-pad scrolling walks through the report on TV
    var f by remember { mutableStateOf(false) }
    Column(
        Modifier.widthIn(max = 760.dp).fillMaxWidth()
            .onFocusChanged { f = it.isFocused }
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, if (f) Color.White.copy(alpha = .5f) else Color.Transparent, RoundedCornerShape(20.dp))
            .glass(RoundedCornerShape(20.dp), tint = .06f)
            .clickable { }
            .padding(20.dp)
    ) {
        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        rows.forEach { (k, v, ok) ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                if (ok != null) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(if (ok) Color(0xFF34D399) else Color(0xFFF87171)))
                    Spacer(Modifier.width(8.dp))
                }
                Text(k, Modifier.width(210.dp), color = TextDim, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text(v, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
