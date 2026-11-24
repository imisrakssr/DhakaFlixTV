@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import dev.chrisbanes.haze.hazeEffect
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import dev.chrisbanes.haze.hazeSource
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

/** Space the phone's floating tab bar takes at the bottom (0 on TV). Scroll lists add it to their bottom padding. */
val LocalBottomInset = compositionLocalOf { 0.dp }

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

    val goBack: () -> Unit = {
        if (stack.isNotEmpty()) {
            val id = stack.removeAt(stack.lastIndex)
            holder.removeState("detail:$id:${stack.size}")
        }
    }
    BackHandler(enabled = stack.isNotEmpty()) { goBack() }

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
            holder.SaveableStateProvider("main") { MainScaffold(tab, { tab }, { t -> if (t == tab) AppEvents.scrollTop.value++ else tab = t }, open, onExit) }
        } else {
            holder.SaveableStateProvider("detail:${target.first}:${target.second}") {
                val autoPlay = target.first.startsWith("play:")
                DetailScreen(target.first.removePrefix("play:"), onOpen = open, autoPlay = autoPlay, onBack = goBack)
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
        // phone: content runs full screen under a transparent status bar, a small header (not on Home)
        // and a floating glass tab bar
        val statusTop = androidx.compose.foundation.layout.WindowInsets.statusBars
            .asPaddingValues().calculateTopPadding()
        val haze = remember { dev.chrisbanes.haze.HazeState() }
        val glassLevel by DhakaFlixApp.instance.settings.glassLevel.collectAsState()
        val glassLook = remember(glassLevel) { com.israk.dhakaflix.ui.theme.GlassLook.level(glassLevel) }
        CompositionLocalProvider(com.israk.dhakaflix.ui.theme.LocalGlass provides glassLook) {
        Box(Modifier.fillMaxSize().background(Bg)) {
            // page titles start below the status bar and the header (Home's banner ignores it and runs to the top)
            Box(Modifier.fillMaxSize().then(Modifier.hazeSourceCompat(haze))) {
                CompositionLocalProvider(
                    LocalTopInset provides statusTop + 64.dp, LocalBottomInset provides 96.dp,
                    androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides com.israk.dhakaflix.ui.components.NoAutoScrollSpec,
                ) { content() }
            }
            if (tab != Tab.HOME) PhoneHeader(Modifier.align(Alignment.TopCenter), statusTop, haze)
            PhoneTabBar(
                tab = tab, onTab = onTab, labels = { it.label(s) }, haze = haze,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
            )
        }
        }
    }
}

/** Phone header: app logo on the left, Simkl profile picture on the right (like the TV top bar). */
@Composable
private fun PhoneHeader(modifier: Modifier, statusTop: androidx.compose.ui.unit.Dp, haze: dev.chrisbanes.haze.HazeState) {
    val (brandMark, brandWord) = rememberBrandLogos()
    val g = com.israk.dhakaflix.ui.theme.LocalGlass.current
    Box(
        modifier.fillMaxWidth()
            // liquid glass: the page shows through the header as it scrolls (how clear: Settings → Glass look),
            // a hairline of light at the bottom
            .hazeEffect(
                haze,
                dev.chrisbanes.haze.HazeStyle(
                    backgroundColor = Bg,
                    tints = listOf(dev.chrisbanes.haze.HazeTint(Bg.copy(alpha = (g.tint + .08f).coerceAtMost(.7f)))),
                    blurRadius = g.blur,
                    noiseFactor = 0f,
                ),
            )
            // a soft shade from the top keeps the status bar and logo readable even with crystal glass
            .background(Brush.verticalGradient(listOf(Bg.copy(alpha = .45f * (1f - g.tint)), Color.Transparent)))
            .drawBehind {
                drawLine(Color.White.copy(alpha = .10f * g.rim), androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height), 1f)
            }
            .padding(top = statusTop)
            .height(56.dp)
            .padding(horizontal = LocalEdge.current),
    ) {
        Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(brandMark), null, Modifier.size(26.dp))
            Spacer(Modifier.width(8.dp))
            Image(painterResource(brandWord), "DhakaFlix", Modifier.height(16.dp))
        }
        Box(Modifier.align(Alignment.CenterEnd)) { SimklAvatar() }
    }
}

/**
 * Floating "crystal" tab bar for phones: the page shows through (how clear: Settings → Glass look), bright
 * curved-glass edges, an accent-tinted lens under the current tab. Names show only while a finger is on the
 * bar and fade out 2 s after it lifts.
 */
@Composable
private fun PhoneTabBar(
    tab: Tab, onTab: (Tab) -> Unit, labels: (Tab) -> String,
    haze: dev.chrisbanes.haze.HazeState, modifier: Modifier = Modifier,
) {
    // Press and slide along the bar: the lens follows the finger, the tab under it lights up, and letting go
    // opens it.
    val shape = RoundedCornerShape(50)
    val tabs = Tab.entries
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val accent = LocalAccent.current
    val g = com.israk.dhakaflix.ui.theme.LocalGlass.current
    var touching by remember { mutableStateOf(false) }
    var showNames by remember { mutableStateOf(false) }
    LaunchedEffect(touching) {
        if (touching) showNames = true
        else { kotlinx.coroutines.delay(2000); showNames = false }
    }
    val nameAlpha by animateFloatAsState(if (showNames) 1f else 0f, tween(if (showNames) 140 else 420), label = "names")
    val barH by androidx.compose.animation.core.animateDpAsState(if (showNames) 66.dp else 58.dp, tween(260), label = "barH")
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(barH)
            .shadow(16.dp * (.4f + g.tint), shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .hazeEffect(
                haze,
                dev.chrisbanes.haze.HazeStyle(
                    backgroundColor = Bg,
                    tints = listOf(dev.chrisbanes.haze.HazeTint(Color(0xFF14161C).copy(alpha = g.tint))),
                    blurRadius = g.blur,
                    noiseFactor = 0f,
                ),
            )
            // curved glass: a bright highlight along the top, clear middle, a little light caught at the bottom
            .background(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = .16f * g.rim),
                    .35f to Color.White.copy(alpha = .03f),
                    .75f to Color.Transparent,
                    1f to Color.White.copy(alpha = .07f * g.rim),
                )
            )
            .border(
                1.dp,
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = (.40f * g.rim).coerceAtMost(.7f)),
                    .5f to Color.White.copy(alpha = .05f),
                    1f to Color.White.copy(alpha = (.18f * g.rim).coerceAtMost(.35f)),
                ),
                shape,
            )
            // only watches the finger (doesn't consume), so taps and slides below still work
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    touching = true; showNames = true
                    do {
                        val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    } while (e.changes.any { it.pressed })
                    touching = false
                }
            },
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val pad = 5.dp
        val slotW = (maxWidth - pad * 2) / tabs.size
        val slotPx = with(density) { slotW.toPx() }
        var dragX by remember { mutableStateOf<Float?>(null) }   // lens left edge (px) while the finger slides
        var hover by remember { mutableIntStateOf(tabs.indexOf(tab)) }
        val selIdx = tabs.indexOf(tab)
        val target = dragX ?: (selIdx * slotPx)
        val lensX by animateFloatAsState(
            target, if (dragX != null) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(dampingRatio = .72f, stiffness = 420f),
            label = "lens",
        )
        val lensScale by animateFloatAsState(if (dragX != null) 1.08f else 1f, label = "lensScale")
        val lit = if (dragX != null) hover else selIdx

        Box(
            Modifier.fillMaxSize().padding(pad)
                .pointerInput(tabs.size) {
                    detectTapGestures { o -> tabs.getOrNull((o.x / slotPx).toInt().coerceIn(0, tabs.size - 1))?.let(onTab) }
                }
                .pointerInput(tabs.size) {
                    detectHorizontalDragGestures(
                        onDragStart = { o ->
                            dragX = (o.x - slotPx / 2).coerceIn(0f, slotPx * (tabs.size - 1))
                            hover = ((o.x / slotPx).toInt()).coerceIn(0, tabs.size - 1)
                        },
                        onDragEnd = { tabs.getOrNull(hover)?.let(onTab); dragX = null },
                        onDragCancel = { dragX = null },
                    ) { change, dx ->
                        change.consume()
                        val x = ((dragX ?: 0f) + dx).coerceIn(0f, slotPx * (tabs.size - 1))
                        dragX = x
                        val h = ((x + slotPx / 2) / slotPx).toInt().coerceIn(0, tabs.size - 1)
                        if (h != hover) { hover = h; haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove) }
                    }
                },
        ) {
            // the glass lens under the chosen tab, tinted with the accent colour
            Box(
                Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(lensX.toInt(), 0) }
                    .width(slotW).fillMaxHeight()
                    .graphicsLayer { scaleX = lensScale; scaleY = lensScale }
                    .clip(shape)
                    .background(Brush.verticalGradient(listOf(accent.copy(alpha = .16f), accent.copy(alpha = .06f))))
                    .background(Brush.verticalGradient(0f to Color.White.copy(alpha = .20f), .5f to Color.White.copy(alpha = .04f), 1f to Color.White.copy(alpha = .08f)))
                    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = .55f), accent.copy(alpha = .45f))), shape),
            )
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEachIndexed { i, t ->
                    val on = i == lit
                    val tint by animateColorAsState(if (on) accent else Color.White, tween(160), label = "tabTint")
                    Column(
                        Modifier.weight(1f).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box {
                            Icon(t.icon(), labels(t), tint = tint, modifier = Modifier.size(23.dp))
                            if (tabBadge(t)) UpdateDot(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-2).dp))
                        }
                        if (nameAlpha > 0.01f) {
                            Spacer(Modifier.height(2.dp * nameAlpha))
                            Text(
                                labels(t), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                color = tint, style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                                modifier = Modifier.graphicsLayer { alpha = nameAlpha },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Marks the page as what the glass tab bar blurs. */
private fun Modifier.hazeSourceCompat(state: dev.chrisbanes.haze.HazeState): Modifier = this.then(Modifier.hazeSource(state))

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
        val (brandMark, brandWord) = rememberBrandLogos()
        Row(
            Modifier.align(Alignment.CenterStart).padding(start = LocalEdge.current),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(brandMark), null, Modifier.size(28.dp))
            Spacer(Modifier.width(8.dp))
            Image(painterResource(brandWord), "DhakaFlix", Modifier.height(18.dp))
        }
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
        Row(
            Modifier.align(Alignment.CenterEnd).padding(end = LocalEdge.current),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SyncIndicator()
            Spacer(Modifier.width(12.dp))
            TopClock()
            Spacer(Modifier.width(14.dp))
            SimklAvatar()
        }
    }
}

/** Small clock in the top bar, next to the profile picture (follows the app language). */
@Composable
private fun TopClock() {
    val bn = com.israk.dhakaflix.ui.components.isDhakaiya()
    var now by remember { mutableStateOf(java.util.Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = java.util.Date()
            // wake up right after the minute changes
            kotlinx.coroutines.delay(60_000L - System.currentTimeMillis() % 60_000L + 200L)
        }
    }
    Text(
        com.israk.dhakaflix.ui.components.Bangla.clock(now, bn),
        color = Color.White.copy(alpha = .9f),
        // same type as the menu pills so it sits on the same line
        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
    )
}

/** Signed-in Simkl account picture with a thin accent ring (display only, not focusable). */
@Composable
private fun SimklAvatar() {
    val settings = DhakaFlixApp.instance.settings
    val token by settings.simklToken.collectAsState()
    val user by settings.simklUser.collectAsState()
    val avatar by settings.simklAvatar.collectAsState()
    if (token.isBlank()) return
    val url = avatar.let { if (it.startsWith("//")) "https:$it" else it }
    Box(
        Modifier.size(36.dp)
            .border(1.5.dp, LocalAccent.current, androidx.compose.foundation.shape.CircleShape)
            .padding(3.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(Color.White.copy(alpha = .10f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(user.take(1).uppercase(), color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (url.isNotBlank()) coil.compose.AsyncImage(
            model = url, contentDescription = user,
            modifier = Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
    }
}

@Composable
private fun TabPill(
    text: String, selected: Boolean, fr: FocusRequester, focusable: Boolean,
    isCurrent: () -> Boolean, redirect: () -> Unit, badge: Boolean = false, onSelect: () -> Unit,
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
            Image(painterResource(rememberBrandLogos().first), "DhakaFlix", Modifier.padding(start = 6.dp, bottom = 14.dp).size(32.dp))
            Tab.entries.forEach { t ->
                RailItem(t.icon(), t.label(s), t == tab, expanded, nav.requesters.getValue(t), active, isCurrent = { nav.currentTab() == t }, redirect = nav::focusCurrent, badge = tabBadge(t)) { onPick(t) }
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.padding(start = 14.dp)) { SyncIndicator() }
        }
    }
}

@Composable
private fun RailItem(
    icon: ImageVector, text: String, selected: Boolean, expanded: Boolean, fr: FocusRequester, focusable: Boolean,
    isCurrent: () -> Boolean, redirect: () -> Unit, badge: Boolean = false, onSelect: () -> Unit,
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
        Box {
            Icon(icon, text, tint = fg, modifier = Modifier.size(22.dp))
            if (badge) UpdateDot(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-2).dp))
        }
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

/** True while a newer release is waiting (Settings → About me → Check for updates). */
@Composable
fun updateReady(): Boolean = com.israk.dhakaflix.data.Updater.available.collectAsState().value != null

/** Small accent dot with a dark rim, the "new update" mark. */
@Composable
fun UpdateDot(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 9.dp) {
    Box(
        modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xFF0B0B10))
            .padding(1.5.dp).clip(androidx.compose.foundation.shape.CircleShape).background(LocalAccent.current)
    )
}

/** Dot on the Settings tab: a new app version, or new titles on RongDhong / BokaSoka waiting for a refresh. */
@Composable
fun settingsBadge(): Boolean =
    updateReady() || com.israk.dhakaflix.DhakaFlixApp.instance.library.otherNews.collectAsState().value.isNotEmpty()

/** Settings: app update or new titles on other servers. My Library: new titles on other servers. */
@Composable
fun tabBadge(t: Tab): Boolean = when (t) {
    Tab.SETTINGS -> settingsBadge()
    Tab.LIBRARY -> com.israk.dhakaflix.DhakaFlixApp.instance.library.otherNews.collectAsState().value.isNotEmpty()
    else -> false
}
