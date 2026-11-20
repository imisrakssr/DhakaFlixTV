package com.israk.dhakaflix.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.israk.dhakaflix.DhakaFlixApp
import com.israk.dhakaflix.R
import com.israk.dhakaflix.data.MediaType
import com.israk.dhakaflix.ui.browse.BrowseScreen
import com.israk.dhakaflix.ui.components.AppIcons
import com.israk.dhakaflix.ui.components.FocusMemory
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

enum class Tab { HOME, MOVIES, SERIES, SEARCH, LIBRARY, SETTINGS }

private fun Tab.label(s: Strings) = when (this) {
    Tab.HOME -> s.home; Tab.MOVIES -> s.movies; Tab.SERIES -> s.series
    Tab.SEARCH -> s.search; Tab.LIBRARY -> s.library; Tab.SETTINGS -> s.settings
}

private fun Tab.icon(): ImageVector = when (this) {
    Tab.HOME -> Icons.Filled.Home; Tab.MOVIES -> AppIcons.Movie; Tab.SERIES -> AppIcons.Tv
    Tab.SEARCH -> Icons.Filled.Search; Tab.LIBRARY -> AppIcons.Library; Tab.SETTINGS -> Icons.Filled.Settings
}

@Composable
fun AppRoot(onExit: () -> Unit) {
    val stack = remember { mutableStateListOf<String>() } // detail ids
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val holder = rememberSaveableStateHolder()
    val open: (String) -> Unit = { stack.add(it) }

    BackHandler(enabled = stack.isNotEmpty()) {
        val id = stack.removeAt(stack.lastIndex)
        holder.removeState("detail:$id:${stack.size}")
    }
    BackHandler(enabled = stack.isEmpty()) {
        if (tab != Tab.HOME) tab = Tab.HOME else onExit()
    }

    val top = stack.lastOrNull()
    AnimatedContent(
        targetState = top?.let { it to stack.size - 1 },
        transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(180)) },
        label = "nav",
    ) { target ->
        if (target == null) {
            holder.SaveableStateProvider("main") { MainScaffold(tab, { tab = it }, open) }
        } else {
            holder.SaveableStateProvider("detail:${target.first}:${target.second}") {
                DetailScreen(target.first, onOpen = open)
            }
        }
    }
}

@Composable
private fun MainScaffold(tab: Tab, onTab: (Tab) -> Unit, open: (String) -> Unit) {
    val isTv = LocalIsTv.current
    val tabHolder = rememberSaveableStateHolder()
    val content: @Composable (Tab) -> Unit = { t ->
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
    if (isTv) {
        Box(Modifier.fillMaxSize().background(Bg)) {
            androidx.compose.runtime.CompositionLocalProvider(LocalTopInset provides 64.dp) {
                content(tab)
            }
            TvTopBar(tab, onTab)
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
                androidx.compose.runtime.CompositionLocalProvider(LocalTopInset provides 12.dp) {
                    content(tab)
                }
            }
        }
    }
}

@Composable
private fun TvTopBar(tab: Tab, onTab: (Tab) -> Unit) {
    val s = LocalStrings.current
    val repo = DhakaFlixApp.instance.library
    val sync by repo.sync.collectAsState()
    val enriching by repo.enriching.collectAsState()
    val requesters = remember { Tab.entries.associateWith { FocusRequester() } }

    LaunchedEffect(Unit) {
        delay(120)
        if (FocusMemory.restoreId == null) runCatching { requesters.getValue(tab).requestFocus() }
        else {
            delay(700)
            if (FocusMemory.restoreId != null) {
                FocusMemory.restoreId = null
                runCatching { requesters.getValue(tab).requestFocus() }
            }
        }
    }

    Row(
        Modifier.fillMaxWidth().height(64.dp)
            .background(Brush.verticalGradient(listOf(Bg.copy(alpha = .92f), Bg.copy(alpha = 0f))))
            .padding(horizontal = LocalEdge.current),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(R.drawable.logo_wordmark), "DhakaFlix", Modifier.height(20.dp))
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tab.entries.forEach { t ->
                TabPill(t.label(s), t == tab, requesters.getValue(t)) { onTab(t) }
            }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.width(80.dp), contentAlignment = Alignment.CenterEnd) {
            if (sync.running || enriching) {
                CircularProgressIndicator(Modifier.size(18.dp), color = LocalAccent.current, strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun TabPill(text: String, selected: Boolean, fr: FocusRequester, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> Color.White
        selected -> Color.White.copy(alpha = .16f)
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
            .padding(horizontal = 16.dp, vertical = 7.dp),
        color = fg,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
    )
}

fun edgeFor(isTv: Boolean, widthDp: Int): Dp = when {
    isTv -> 48.dp
    widthDp >= 600 -> 28.dp
    else -> 16.dp
}
