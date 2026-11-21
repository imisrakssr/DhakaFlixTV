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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.window.Dialog
import androidx.compose.material.icons.filled.Close
import kotlinx.coroutines.launch
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

/** While true the nav bar can't take focus (e.g. focus is inside the settings detail pane). */
val LocalNavLock = compositionLocalOf<MutableState<Boolean>?> { null }

/** Set by scrolling screens; the TV top bar slides away while content is scrolled. */
val LocalScrolled = compositionLocalOf<MutableState<Boolean>?> { null }

@Composable
fun ReportScroll(isScrolled: () -> Boolean) {
    val target = LocalScrolled.current ?: return
    LaunchedEffect(target) { snapshotFlow { isScrolled() }.collect { target.value = it } }
    DisposableEffect(target) { onDispose { target.value = false } }
}

/** Opening "play:<id>" shows the detail page and starts playback right away. */
@Composable
fun AppRoot(onExit: () -> Unit) {
    // saveable: survives the system reclaiming the activity while a movie plays (1 GB TV sticks)
    val stack = rememberSaveable(
        saver = listSaver<SnapshotStateList<String>, String>(save = { it.toList() }, restore = { it.toMutableStateList() })
    ) { mutableStateListOf() }
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val holder = rememberSaveableStateHolder()
    val open: (String) -> Unit = { stack.add(it) }

    BackHandler(enabled = stack.isNotEmpty()) {
        val id = stack.removeAt(stack.lastIndex)
        holder.removeState("detail:$id:${stack.size}")
    }

    // the remote's search / mic key: jump to Search (SearchScreen starts listening)
    LaunchedEffect(Unit) {
        AppEvents.voiceSearch.collect { n -> if (n > AppEvents.navHandled) { AppEvents.navHandled = n; stack.clear(); tab = Tab.SEARCH } }
    }

    val top = stack.lastOrNull()
    Box(Modifier.fillMaxSize()) {
    AnimatedContent(
        targetState = top?.let { it to stack.size - 1 },
        transitionSpec = {
            (fadeIn(tween(300)) + scaleIn(tween(320), initialScale = 0.96f)) togetherWith fadeOut(tween(180))
        },
        label = "nav",
    ) { target ->
        if (target == null) {
            holder.SaveableStateProvider("main") { MainScaffold(tab, { tab }, { tab = it }, open, onExit) }
        } else {
            holder.SaveableStateProvider("detail:${target.first}:${target.second}") {
                val autoPlay = target.first.startsWith("play:")
                DetailScreen(target.first.removePrefix("play:"), onOpen = open, autoPlay = autoPlay)
            }
        }
    }
    if (LocalIsTv.current) Screensaver()
    }
}

@Composable
private fun MainScaffold(tab: Tab, currentTab: () -> Tab, onTab: (Tab) -> Unit, open: (String) -> Unit, onExit: () -> Unit) {
    val isTv = LocalIsTv.current
    val app = DhakaFlixApp.instance
    val navPos by app.settings.navPosition.collectAsState()
    val left = isTv && navPos == "left"
    val tabHolder = rememberSaveableStateHolder()
    val entry = remember { FocusRequester() }
    val requesters = remember { Tab.entries.associateWith { FocusRequester() } }
    var navFocused by remember { mutableStateOf(false) }
    val scrolled = remember { mutableStateOf(false) }
    val navLock = remember { mutableStateOf(false) }
    var railActive by remember { mutableStateOf(false) }
    var askExit by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val fm = LocalFocusManager.current

    fun focusNav() {
        navLock.value = false
        if (left) railActive = true
        scope.launch { delay(40); NavGate.open { runCatching { requesters.getValue(currentTab()).requestFocus() } } }
    }

    BackHandler {
        when {
            isTv && !navFocused -> focusNav()
            tab != Tab.HOME -> onTab(Tab.HOME)
            else -> askExit = true
        }
    }
    if (askExit) ExitDialog(onStay = { askExit = false }, onExit = onExit)

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
            if (FocusMemory.restoreId != null) {
                delay(700)
                if (FocusMemory.restoreId == null) return@LaunchedEffect
                FocusMemory.restoreId = null
            }
            if (left) {
                // rail stays closed: start in the content. On a cold start the banner appears a moment
                // later, so keep trying for a few seconds before falling back to the menu.
                var ok = false
                repeat(25) {
                    if (!ok) {
                        ok = runCatching { entry.requestFocus() }.isSuccess
                        if (!ok) delay(200)
                    }
                }
                if (!ok) focusNav()
            } else NavGate.open { runCatching { requesters.getValue(tab).requestFocus() } }
        }
        val nav = NavHandle(currentTab, onTab, requesters, entry) {
            navFocused = it
            NavGate.inNav = it
            if (it) FocusMemory.restoreId = null // a new page must never pull focus out of the menu
            if (!it) railActive = false
        }
        // (the activity root already paints the background once)
        Box(Modifier.fillMaxSize()) {
            if (left) {
                Box(
                    Modifier.fillMaxSize().padding(start = 76.dp)
                        // the rail opens only when LEFT is pressed at the very left of the content
                        .onKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown || e.key != Key.DirectionLeft) return@onKeyEvent false
                            if (fm.moveFocus(FocusDirection.Left)) return@onKeyEvent true
                            focusNav()
                            true
                        }
                ) {
                    CompositionLocalProvider(
                        LocalTopInset provides 20.dp,
                        LocalEdge provides 28.dp,
                        LocalEntryFocus provides entry,
                        LocalScrolled provides scrolled,
                        LocalNavLock provides navLock,
                    ) { content() }
                }
                LeftRail(nav, active = railActive) { t ->
                    onTab(t)
                    // picking a tab closes the rail and moves into the page
                    scope.launch { delay(120); runCatching { entry.requestFocus() } }
                }
            } else {
                CompositionLocalProvider(
                    LocalTopInset provides 70.dp,
                    LocalEntryFocus provides entry,
                    LocalScrolled provides scrolled,
                    LocalNavLock provides navLock,
                ) { content() }
                // fade that hides rows scrolling under the (hidden) tab bar
                val scrimAlpha by animateFloatAsState(if (scrolled.value) 1f else 0f, tween(300), label = "scrim")
                Box(
                    Modifier.fillMaxWidth().height(78.dp).graphicsLayer { alpha = scrimAlpha }
                        .background(Brush.verticalGradient(listOf(Bg, Bg.copy(alpha = .85f), Bg.copy(alpha = 0f))))
                )
                TvTopBar(nav, visible = navFocused || !scrolled.value, locked = navLock.value)
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

/**
 * The menu can only be entered on purpose: UP (top bar), LEFT (left rail), BACK, or by the app itself.
 * Without this, RIGHT/LEFT at the end of any row, button strip or grid line "escaped" into the menu
 * and switched tabs.
 */
object NavGate {
    @Volatile var inNav = false
    @Volatile var forced = false
    /** keyCode of the last D-pad key pressed (set by MainActivity). */
    @Volatile var lastDir = 0
    fun allow(leftRail: Boolean): Boolean =
        inNav || forced || lastDir == android.view.KeyEvent.KEYCODE_DPAD_UP ||
            (leftRail && lastDir == android.view.KeyEvent.KEYCODE_DPAD_LEFT)

    /** Focus something in the menu from code. */
    inline fun <T> open(block: () -> T): T { forced = true; try { return block() } finally { forced = false } }
}

/** Everything the TV navigation (top bar or left rail) needs. */
private class NavHandle(
    val currentTab: () -> Tab,
    val onTab: (Tab) -> Unit,
    val requesters: Map<Tab, FocusRequester>,
    val entry: FocusRequester,
    val onNavFocus: (Boolean) -> Unit,
) {
    /** Move selection with the D-pad inside the nav; focus follows the new tab. */
    fun step(delta: Int) {
        val all = Tab.entries
        val next = all[(currentTab().ordinal + delta).coerceIn(0, all.lastIndex)]
        if (next != currentTab()) onTab(next)
        runCatching { requesters.getValue(next).requestFocus() }
    }

    /** Focus arriving from the content always lands on the current tab, never a neighbour. */
    fun focusCurrent() { runCatching { requesters.getValue(currentTab()).requestFocus() } }

    fun keys(prev: Key, next: Key, intoContent: Key): Modifier = Modifier.onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
            prev -> { step(-1); true }
            next -> { step(1); true }
            intoContent -> runCatching { entry.requestFocus() }.isSuccess
            else -> false
        }
    }
}

@Composable
private fun SyncIndicator() {
    val repo = DhakaFlixApp.instance.library
    val sync by repo.sync.collectAsState()
    if (sync.running) {
        CircularProgressIndicator(Modifier.size(16.dp), color = LocalAccent.current, strokeWidth = 2.dp)
    }
}

@Composable
private fun TvTopBar(nav: NavHandle, visible: Boolean, locked: Boolean) {
    val s = LocalStrings.current
    val shown by animateFloatAsState(if (visible) 1f else 0f, tween(280), label = "bar")
    val tab = nav.currentTab()
    Box(
        Modifier.fillMaxWidth().height(70.dp)
            .graphicsLayer { alpha = shown; translationY = -(1f - shown) * 40f }
            .background(Brush.verticalGradient(listOf(Bg.copy(alpha = .85f), Bg.copy(alpha = 0f)))),
    ) {
        Image(
            painterResource(R.drawable.logo_wordmark), "DhakaFlix",
            Modifier.align(Alignment.CenterStart).padding(start = LocalEdge.current).height(18.dp),
        )
        Row(
            Modifier.align(Alignment.Center)
                .glass(RoundedCornerShape(50), tint = 0.08f)
                .onFocusChanged { nav.onNavFocus(it.hasFocus) }
                .then(nav.keys(Key.DirectionLeft, Key.DirectionRight, Key.DirectionDown))
                .padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Tab.entries.forEach { t ->
                TabPill(t.label(s), t == tab, nav.requesters.getValue(t), !locked, isCurrent = { nav.currentTab() == t }, redirect = nav::focusCurrent) { nav.onTab(t) }
            }
        }
        Box(Modifier.align(Alignment.CenterEnd).padding(end = LocalEdge.current)) { SyncIndicator() }
    }
}

@Composable
private fun TabPill(
    text: String, selected: Boolean, fr: FocusRequester, focusable: Boolean,
    isCurrent: () -> Boolean, redirect: () -> Unit, onSelect: () -> Unit,
) {
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
            .focusProperties { canFocus = focusable && NavGate.allow(leftRail = false) }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused && !isCurrent()) redirect()
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
private fun LeftRail(nav: NavHandle, active: Boolean, onPick: (Tab) -> Unit) {
    val s = LocalStrings.current
    var expanded by remember { mutableStateOf(false) }
    val width: Dp by animateDpAsState(if (expanded) 220.dp else 64.dp, tween(240), label = "rail")
    val scrim by animateFloatAsState(if (expanded) 1f else 0f, tween(240), label = "railScrim")
    val tab = nav.currentTab()
    Box(Modifier.zIndex(5f).fillMaxSize()) {
        // dim the page while the rail is open so labels never sit on top of content
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = scrim }.background(Bg.copy(alpha = .82f)))
        Column(
            Modifier.padding(start = 6.dp, top = 16.dp, bottom = 16.dp).fillMaxHeight().width(width)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xF20D1016))
                .glass(RoundedCornerShape(22.dp), tint = 0.05f)
                .onFocusChanged { expanded = it.hasFocus; nav.onNavFocus(it.hasFocus) }
                .then(nav.keys(Key.DirectionUp, Key.DirectionDown, Key.DirectionRight))
                .padding(vertical = 14.dp, horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Image(painterResource(R.drawable.logo_mark), "DhakaFlix", Modifier.padding(start = 6.dp, bottom = 14.dp).size(32.dp))
            Tab.entries.forEach { t ->
                RailItem(t.icon(), t.label(s), t == tab, expanded, nav.requesters.getValue(t), active, isCurrent = { nav.currentTab() == t }, redirect = nav::focusCurrent) { onPick(t) }
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.padding(start = 14.dp)) { SyncIndicator() }
        }
    }
}

@Composable
private fun RailItem(
    icon: ImageVector, text: String, selected: Boolean, expanded: Boolean, fr: FocusRequester, focusable: Boolean,
    isCurrent: () -> Boolean, redirect: () -> Unit, onSelect: () -> Unit,
) {
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
            .focusProperties { canFocus = focusable && NavGate.allow(leftRail = true) }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused && !isCurrent()) redirect()
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

@Composable
private fun ExitDialog(onStay: () -> Unit, onExit: () -> Unit) {
    val s = LocalStrings.current
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { fr.requestFocus() } }
    Dialog(onDismissRequest = onStay) {
        androidx.compose.foundation.layout.Column(
            Modifier.glass(RoundedCornerShape(26.dp), tint = .12f).background(Bg.copy(alpha = .7f)).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(painterResource(R.drawable.logo_mark), null, Modifier.size(52.dp))
            Spacer(Modifier.height(14.dp))
            Text(s.exitTitle, style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                com.israk.dhakaflix.ui.components.PillButton(s.exitNo, onClick = onStay, primary = true, focusRequester = fr)
                com.israk.dhakaflix.ui.components.PillButton(s.exitYes, onClick = onExit, icon = Icons.Filled.Close)
            }
        }
    }
}

fun edgeFor(isTv: Boolean, widthDp: Int): Dp = when {
    isTv -> 44.dp
    widthDp >= 600 -> 28.dp
    else -> 16.dp
}
