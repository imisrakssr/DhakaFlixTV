package com.israk.dhakaflix.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.israk.dhakaflix.DhakaFlixApp
import com.israk.dhakaflix.R
import com.israk.dhakaflix.data.MediaType
import com.israk.dhakaflix.ui.browse.BrowseScreen
import com.israk.dhakaflix.ui.components.AppIcons
import com.israk.dhakaflix.ui.components.FocusMemory
import com.israk.dhakaflix.ui.components.glass
import com.israk.dhakaflix.ui.detail.DetailScreen
import com.israk.dhakaflix.ui.home.HomeScreen
import com.israk.dhakaflix.ui.library.LibraryScreen
import com.israk.dhakaflix.ui.search.SearchScreen
import com.israk.dhakaflix.ui.settings.SettingsScreen
import com.israk.dhakaflix.ui.theme.Bg
import com.israk.dhakaflix.ui.theme.LocalAccent
import com.israk.dhakaflix.ui.theme.LocalEdge
import com.israk.dhakaflix.ui.theme.LocalIsTv
import com.israk.dhakaflix.ui.theme.LocalStrings
import com.israk.dhakaflix.ui.theme.Strings
import com.israk.dhakaflix.ui.theme.Surface1
import com.israk.dhakaflix.ui.theme.TextDim
import kotlinx.coroutines.delay

/** Space reserved at the top of each tab for the floating TV tab bar. */
val LocalTopInset = compositionLocalOf { 0.dp }

/** First focusable element of the current tab; the nav bar jumps here on D-pad down/right. */
val LocalEntryFocus = compositionLocalOf<FocusRequester?> { null }

@Composable
fun Modifier.entryFocus(): Modifier = LocalEntryFocus.current?.let { this.focusRequester(it) } ?: this

enum class Tab { HOME, MOVIES, SERIES, SEARCH, LIBRARY, SETTINGS }

private fun Tab.label(s: Strings) = when (this) {
    Tab.HOME -> s.home; Tab.MOVIES -> s.movies; Tab.SERIES -> s.series
    Tab.SEARCH -> s.search; Tab.LIBRARY -> s.library; Tab.SETTINGS -> s.settings
}

private fun Tab.icon(): ImageVector = when (this) {
    Tab.HOME -> Icons.Filled.Home; Tab.MOVIES -> AppIcons.Movie; Tab.SERIES -> AppIcons.Tv
    Tab.SEARCH -> Icons.Filled.Search; Tab.LIBRARY -> AppIcons.Library; Tab.SETTINGS -> Icons.Filled.Settings
}

/** Opening "play:<id>" shows the detail page and starts playback right away. */
@Composable
fun AppRoot(onExit: () -> Unit) {
    val stack = remember { mutableStateListOf<String>() }
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val holder = rememberSaveableStateHolder()
    val open: (String) -> Unit = { stack.add(it) }

    BackHandler(enabled = stack.isNotEmpty()) {
        val id = stack.removeAt(stack.lastIndex)
        holder.removeState("detail:$id:${stack.size}")
    }

    val top = stack.lastOrNull()
    AnimatedContent(
        targetState = top?.let { it to stack.size - 1 },
        transitionSpec = {
            (fadeIn(tween(300)) + scaleIn(tween(320), initialScale = 0.96f)) togetherWith fadeOut(tween(180))
        },
        label = "nav",
    ) { target ->
        if (target == null) {
            holder.SaveableStateProvider("main") { MainScaffold(tab, { tab = it }, open, onExit) }
        } else {
            holder.SaveableStateProvider("detail:${target.first}:${target.second}") {
                val autoPlay = target.first.startsWith("play:")
                DetailScreen(target.first.removePrefix("play:"), onOpen = open, autoPlay = autoPlay)
            }
        }
    }
}

@Composable
private fun MainScaffold(tab: Tab, onTab: (Tab) -> Unit, open: (String) -> Unit, onExit: () -> Unit) {
    val isTv = LocalIsTv.current
    val app = DhakaFlixApp.instance
    val navPos by app.settings.navPosition.collectAsState()
    val left = isTv && navPos == "left"
    val tabHolder = rememberSaveableStateHolder()
    val entry = remember { FocusRequester() }
    val requesters = remember { Tab.entries.associateWith { FocusRequester() } }
    var navFocused by remember { mutableStateOf(false) }

    BackHandler {
        when {
            isTv && !navFocused -> runCatching { requesters.getValue(tab).requestFocus() }
            tab != Tab.HOME -> onTab(Tab.HOME)
            else -> onExit()
        }
    }

    val content: @Composable () -> Unit = {
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                if (left) {
                    (fadeIn(tween(320)) + slideInVertically(tween(380)) { h -> dir * h / 10 }) togetherWith
                        (fadeOut(tween(180)) + slideOutVertically(tween(300)) { h -> -dir * h / 14 })
                } else {
                    (fadeIn(tween(320)) + slideInHorizontally(tween(380)) { w -> dir * w / 8 } + scaleIn(tween(380), initialScale = 0.98f)) togetherWith
                        (fadeOut(tween(180)) + slideOutHorizontally(tween(300)) { w -> -dir * w / 12 })
                }
            },
            label = "tabs",
        ) { t ->
            tabHolder.SaveableStateProvider(t.name) {
                when (t) {
                    Tab.HOME -> HomeScreen(open)
                    Tab.MOVIES -> BrowseScreen(MediaType.MOVIE, open)
                    Tab.SERIES -> BrowseScreen(MediaType.SERIES, open)
                    Tab.SEARCH -> SearchScreen(open)
                    Tab.LIBRARY -> LibraryScreen(open)
                    Tab.SETTINGS -> SettingsScreen()
                }
            }
        }
    }

    if (isTv) {
        LaunchedEffect(Unit) {
            delay(150)
            if (FocusMemory.restoreId == null) runCatching { requesters.getValue(tab).requestFocus() }
            else {
                delay(700)
                if (FocusMemory.restoreId != null) {
                    FocusMemory.restoreId = null
                    runCatching { requesters.getValue(tab).requestFocus() }
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Bg)) {
            if (left) {
                Box(Modifier.fillMaxSize().padding(start = 76.dp)) {
                    CompositionLocalProvider(
                        LocalTopInset provides 20.dp,
                        LocalEdge provides 28.dp,
                        LocalEntryFocus provides entry,
                    ) { content() }
                }
                LeftRail(tab, onTab, requesters, entry) { navFocused = it }
            } else {
                CompositionLocalProvider(LocalTopInset provides 70.dp, LocalEntryFocus provides entry) { content() }
                TvTopBar(tab, onTab, requesters, entry) { navFocused = it }
            }
        }
    } else {
        val s = LocalStrings.current
        val accent = LocalAccent.current
        Scaffold(
            containerColor = Bg,
            bottomBar = {
                NavigationBar(containerColor = Surface1) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = t == tab, onClick = { onTab(t) },
                            icon = { Icon(t.icon(), null) },
                            label = { Text(t.label(s), maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.White, selectedTextColor = accent,
                                indicatorColor = accent.copy(alpha = .35f),
                                unselectedIconColor = TextDim, unselectedTextColor = TextDim,
                            ),
                        )
                    }
                }
            },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                CompositionLocalProvider(LocalTopInset provides 12.dp) { content() }
            }
        }
    }
}

/** D-pad from the nav into the content: jump to the tab's entry element. */
private fun Modifier.navKeys(entry: FocusRequester, intoContent: Key): Modifier = onPreviewKeyEvent { e ->
    if (e.type == KeyEventType.KeyDown && e.key == intoContent) runCatching { entry.requestFocus() }.isSuccess else false
}

@Composable
private fun SyncIndicator() {
    val repo = DhakaFlixApp.instance.library
    val sync by repo.sync.collectAsState()
    val enriching by repo.enriching.collectAsState()
    if (sync.running || enriching) {
        CircularProgressIndicator(Modifier.size(16.dp), color = LocalAccent.current, strokeWidth = 2.dp)
    }
}

@Composable
private fun TvTopBar(
    tab: Tab, onTab: (Tab) -> Unit, requesters: Map<Tab, FocusRequester>, entry: FocusRequester,
    onNavFocus: (Boolean) -> Unit,
) {
    val s = LocalStrings.current
    Box(
        Modifier.fillMaxWidth().height(70.dp)
            .background(Brush.verticalGradient(listOf(Bg.copy(alpha = .85f), Bg.copy(alpha = 0f)))),
    ) {
        Image(
            painterResource(R.drawable.logo_wordmark), "DhakaFlix",
            Modifier.align(Alignment.CenterStart).padding(start = LocalEdge.current).height(18.dp),
        )
        Row(
            Modifier.align(Alignment.Center)
                .glass(RoundedCornerShape(50), tint = 0.08f)
                .onFocusChanged { onNavFocus(it.hasFocus) }
                .navKeys(entry, Key.DirectionDown)
                .padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Tab.entries.forEach { t -> TabPill(t.label(s), t == tab, requesters.getValue(t)) { onTab(t) } }
        }
        Box(Modifier.align(Alignment.CenterEnd).padding(end = LocalEdge.current)) { SyncIndicator() }
    }
}

@Composable
private fun TabPill(text: String, selected: Boolean, fr: FocusRequester, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> Color.White
        selected -> Color.White.copy(alpha = .18f)
        else -> Color.Transparent
    }
    val fg = when {
        focused -> Color.Black
        selected -> Color.White
        else -> TextDim
    }
    Text(
        text,
        Modifier
            .focusRequester(fr)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused && !selected) onSelect()
            }
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onSelect)
            .padding(horizontal = 15.dp, vertical = 6.dp),
        color = fg,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
    )
}

@Composable
private fun LeftRail(
    tab: Tab, onTab: (Tab) -> Unit, requesters: Map<Tab, FocusRequester>, entry: FocusRequester,
    onNavFocus: (Boolean) -> Unit,
) {
    val s = LocalStrings.current
    var expanded by remember { mutableStateOf(false) }
    val width: Dp by animateDpAsState(if (expanded) 220.dp else 64.dp, tween(260), label = "rail")
    Box(Modifier.zIndex(5f).fillMaxHeight()) {
        if (expanded) {
            Box(Modifier.fillMaxHeight().width(420.dp).background(Brush.horizontalGradient(listOf(Bg.copy(alpha = .92f), Color.Transparent))))
        }
        Column(
            Modifier.padding(start = 6.dp, top = 16.dp, bottom = 16.dp).fillMaxHeight().width(width)
                .glass(RoundedCornerShape(22.dp), tint = 0.07f)
                .onFocusChanged { expanded = it.hasFocus; onNavFocus(it.hasFocus) }
                .navKeys(entry, Key.DirectionRight)
                .padding(vertical = 14.dp, horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Image(painterResource(R.drawable.logo_mark), "DhakaFlix", Modifier.padding(start = 6.dp, bottom = 14.dp).size(32.dp))
            Tab.entries.forEach { t ->
                RailItem(t.icon(), t.label(s), t == tab, expanded, requesters.getValue(t)) { onTab(t) }
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.padding(start = 14.dp)) { SyncIndicator() }
        }
    }
}

@Composable
private fun RailItem(icon: ImageVector, text: String, selected: Boolean, expanded: Boolean, fr: FocusRequester, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val accent = LocalAccent.current
    val bg = when {
        focused -> Color.White
        selected -> accent.copy(alpha = .30f)
        else -> Color.Transparent
    }
    val fg = if (focused) Color.Black else if (selected) Color.White else TextDim
    Row(
        Modifier.fillMaxWidth()
            .focusRequester(fr)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused && !selected) onSelect()
            }
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, text, tint = fg, modifier = Modifier.size(22.dp))
        AnimatedVisibility(visible = expanded) {
            Text(
                text, Modifier.padding(start = 14.dp), color = fg, maxLines = 1,
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

fun edgeFor(isTv: Boolean, widthDp: Int): Dp = when {
    isTv -> 44.dp
    widthDp >= 600 -> 28.dp
    else -> 16.dp
}
