package com.israk.dhakaflix.player

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.israk.dhakaflix.ui.components.AppIcons
import com.israk.dhakaflix.ui.components.FocusFrame
import com.israk.dhakaflix.ui.components.PillButton
import com.israk.dhakaflix.ui.components.fmtTime
import com.israk.dhakaflix.ui.components.glass
import com.israk.dhakaflix.ui.theme.LocalAccent
import com.israk.dhakaflix.ui.theme.LocalIsTv
import com.israk.dhakaflix.ui.theme.LocalStrings
import com.israk.dhakaflix.ui.theme.TextDim
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun PlayerScreen(c: PlayerController) {
    LaunchedEffect(Unit) {
        while (true) {
            c.tick()
            delay(if (c.ownSubUrl != null) 100 else 300)
        }
    }
    val settings = com.israk.dhakaflix.DhakaFlixApp.instance.settings
    c.sleepMessage = LocalStrings.current.sleepDone
    val hideAfter by settings.controlsTimeout.collectAsState()
    val pauseAfter by settings.pauseOverlay.collectAsState()
    LaunchedEffect(c.controlsVisible, c.interaction, c.isPlaying, c.panel, hideAfter) {
        if (c.controlsVisible && c.isPlaying && c.panel == null) {
            delay(hideAfter.coerceIn(2, 30) * 1000L)
            c.controlsVisible = false
        }
    }
    // Nuvio-style: after a while in pause, swap the controls for a "You're watching" info screen
    LaunchedEffect(c.isPlaying, c.buffering, c.interaction, c.panel, c.resumeAsk, c.error, c.exitAsk, pauseAfter) {
        if (c.isPlaying) c.pauseInfo = false
        if (pauseAfter > 0 && !c.isPlaying && !c.buffering && c.panel == null && c.resumeAsk == null &&
            c.error == null && !c.exitAsk && c.duration > 0 && !c.pauseInfo
        ) {
            delay(pauseAfter * 1000L)
            c.controlsVisible = false
            c.pauseInfo = true
        }
    }
    LaunchedEffect(c.toast) { if (c.toast != null) { delay(2500); c.toast = null } }

    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            detectTapGestures(
                onTap = { c.toggleControls() },
                onDoubleTap = { o -> if (o.x < size.width / 2) c.seekBy(-10_000) else c.seekBy(10_000) },
            )
        }
    ) {
        if (c.engine != null) {
            key(c.viewGeneration) {
                AndroidView(factory = { ctx -> c.viewFor(ctx) }, modifier = Modifier.fillMaxSize())
            }
        }

        c.subtitleText?.let { SubtitleOverlay(it, raised = c.controlsVisible, scale = c.subScale, color = Color(c.subColorArgb)) }

        if (c.buffering && c.resumeAsk == null && c.error == null && !c.serverAsk) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(54.dp), color = LocalAccent.current, strokeWidth = 4.dp)
        }

        SeekFlash(c)

        Controls(c, visible = c.controlsVisible && !c.pauseInfo && c.resumeAsk == null && c.error == null && !c.exitAsk && c.panel == null && !c.serverAsk)

        AnimatedVisibility(c.pauseInfo, enter = fadeIn(androidx.compose.animation.core.tween(600)), exit = fadeOut(androidx.compose.animation.core.tween(250))) {
            PauseInfo(c)
        }

        AnimatedVisibility(
            visible = c.showSkipIntro && c.panel == null && !c.pauseInfo && c.resumeAsk == null,
            enter = fadeIn() + slideInHorizontally { it / 3 }, exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            Row(
                Modifier.padding(end = 40.dp, bottom = if (c.controlsVisible) 190.dp else 56.dp)
                    .glass(RoundedCornerShape(50), tint = .16f)
                    .clickable { c.skipIntro() }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.SkipNext, null, tint = Color.White, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(LocalStrings.current.skipIntro, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (!c.controlsVisible) {
                    Spacer(Modifier.width(10.dp))
                    Text("OK", color = TextDim, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        AnimatedVisibility(
            visible = c.showUpNext && !c.controlsVisible && c.panel == null,
            enter = fadeIn() + slideInHorizontally { it / 3 }, exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) { UpNextCard(c) }

        AnimatedVisibility(
            visible = c.panel != null,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) { SidePanel(c) }

        if (c.serverAsk) ServerPrompt(c)
        c.resumeAsk?.let { ResumePrompt(c, it) }
        if (c.exitAsk) ExitPrompt(c)
        c.error?.let { ErrorOverlay(c, it) }
        c.toast?.let {
            Text(
                it, Modifier.align(Alignment.TopCenter).padding(top = 28.dp)
                    .glass(RoundedCornerShape(50), tint = .12f)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                color = Color.White, style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** "You're watching" screen while paused: title, details and plot over a dark left-side shade. */
@Composable
private fun PauseInfo(c: PlayerController) {
    val s = LocalStrings.current
    val accent = LocalAccent.current
    val m = c.meta
    val ep = c.episodeInfo
    val bn = com.israk.dhakaflix.ui.components.isDhakaiya()
    val B = com.israk.dhakaflix.ui.components.Bangla
    var clock by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    LaunchedEffect(bn) {
        while (true) {
            val now = java.util.Date()
            clock = B.clock(now, bn)
            date = B.date(now, bn)
            delay(15_000)
        }
    }
    Box(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(0f to Color.Black.copy(alpha = .88f), 0.55f to Color.Black.copy(alpha = .55f), 1f to Color.Black.copy(alpha = .15f))
        )
    ) {
        // big clock with the day and date
        Column(Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 44.dp), horizontalAlignment = Alignment.End) {
            Text(clock, color = Color.White, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Light)
            Text(date, color = Color.White.copy(alpha = .75f), style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.align(Alignment.CenterStart).padding(start = 56.dp, end = 56.dp).widthIn(max = 560.dp)) {
            Text(s.youAreWatching, color = TextDim, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            // the title's logo, like the Home hero (plain bold text when there is no logo)
            if (m != null) com.israk.dhakaflix.ui.components.TitleOrLogo(
                m, maxHeight = 110.dp,
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
            ) else Text(
                c.current.title, color = Color.White, style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val facts = listOfNotNull(
                m?.displayYear?.toString()?.let { if (bn) B.digits(it) else it },
                m?.rating?.takeIf { it > 0 }?.let { "★ " + com.israk.dhakaflix.ui.components.fmtRating(it).let { r -> if (bn) B.digits(r) else r } },
                (ep?.runtime ?: m?.runtime)?.takeIf { it > 0 }?.let { if (bn) B.runtime(it) else "${it / 60}h ${it % 60}m".removePrefix("0h ") },
                m?.genreList?.take(3)?.map { if (bn) B.genre(it) else it }?.joinToString(", ")?.takeIf { it.isNotBlank() },
            )
            if (facts.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(facts.joinToString("   ·   "), color = Color.White.copy(alpha = .85f), style = MaterialTheme.typography.bodyLarge)
            }
            c.current.subtitle?.let { sub ->
                Spacer(Modifier.height(14.dp))
                Text(
                    sub + (ep?.name?.let { "  ·  $it" } ?: ""), color = accent,
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            val plot = ep?.overview?.takeIf { it.isNotBlank() } ?: m?.let { com.israk.dhakaflix.ui.components.localOverview(it) }
            if (!plot.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(plot, color = Color.White.copy(alpha = .78f), style = MaterialTheme.typography.bodyLarge, maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.align(Alignment.BottomStart).padding(start = 56.dp, bottom = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Pause, null, tint = Color.White, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Text(s.paused, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(16.dp))
            Text((fmtTime(c.position) + " / " + fmtTime(c.duration)).let { if (bn) B.digits(it) else it }, color = TextDim, style = MaterialTheme.typography.titleMedium)

        }
        // after a while the overlay dims down (easier on the eyes and the panel); any key brings it back
        val dim = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(Unit) {
            delay(45_000)
            dim.animateTo(0.65f, androidx.compose.animation.core.tween(4_000))
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim.value)))
    }
}

@Composable
private fun SubtitleOverlay(text: String, raised: Boolean, scale: Float, color: Color) {
    val bottom by animateDpAsState(if (raised) 170.dp else 42.dp, label = "subPos")
    Box(Modifier.fillMaxSize().padding(bottom = bottom, start = 60.dp, end = 60.dp), contentAlignment = Alignment.BottomCenter) {
        Text(
            text,
            style = TextStyle(
                color = color, fontSize = 26.sp * scale, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                shadow = Shadow(Color.Black, blurRadius = 8f),
            ),
            modifier = Modifier.background(Color.Black.copy(alpha = .35f), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}

/** "+30s" bubble and a thin progress line while seeking with the controls hidden. */
@Composable
private fun SeekFlash(c: PlayerController) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(c.seekFlashAt) {
        now = SystemClock.elapsedRealtime()
        delay(1300)
        now = SystemClock.elapsedRealtime()
    }
    val visible = !c.controlsVisible && c.seekFlashAt > 0 && now - c.seekFlashAt < 1200
    AnimatedVisibility(visible, enter = fadeIn() + scaleIn(initialScale = .9f), exit = fadeOut()) {
        Box(Modifier.fillMaxSize()) {
            val d = c.seekFlashDelta / 1000
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            ThumbImage(c)
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.glass(RoundedCornerShape(50), tint = .14f).padding(horizontal = 22.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (d >= 0) AppIcons.Forward else AppIcons.Rewind, null, tint = Color.White, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(10.dp))
                Text((if (d >= 0) "+" else "−") + "${kotlin.math.abs(d)}s   ·   " + fmtTime(c.position), color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
            }
            val frac = if (c.duration > 0) (c.position.toFloat() / c.duration).coerceIn(0f, 1f) else 0f
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = .2f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(LocalAccent.current))
            }
        }
    }
}

@Composable
private fun Controls(c: PlayerController, visible: Boolean) {
    val s = LocalStrings.current
    val isTv = LocalIsTv.current
    val playFr = remember { FocusRequester() }
    val item = c.current
    val fm = androidx.compose.ui.platform.LocalFocusManager.current

    Box(
        Modifier.fillMaxSize().onKeyEvent { e ->
            // pressing UP at the top row or DOWN at the bottom row closes the overlay
            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
            val dir = when (e.key) {
                Key.DirectionUp -> androidx.compose.ui.focus.FocusDirection.Up
                Key.DirectionDown -> androidx.compose.ui.focus.FocusDirection.Down
                else -> return@onKeyEvent false
            }
            if (!fm.moveFocus(dir)) c.controlsVisible = false
            true
        }
    ) {
        // ---- top: title slides down from the top edge with its own short shade
        AnimatedVisibility(
            visible = visible, modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(),
        ) {
        Box(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .7f), Color.Transparent)))
        ) {
        Row(Modifier.padding(start = 40.dp, end = 40.dp, top = 26.dp, bottom = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!isTv) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White, modifier = Modifier.size(28.dp).clickable { c.exit() })
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                item.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = TextDim, maxLines = 1) }
            }
            // top right: the time now, and when this will end
            val bn = com.israk.dhakaflix.ui.components.isDhakaiya()
            val B = com.israk.dhakaflix.ui.components.Bangla
            var now by remember { mutableStateOf(java.util.Date()) }
            LaunchedEffect(Unit) { while (true) { now = java.util.Date(); delay(5_000) } }
            Spacer(Modifier.width(20.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(B.clock(now, bn), style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.SemiBold)
                if (c.duration > 0) {
                    val end = B.clock(java.util.Date(now.time + (c.duration - c.position).coerceAtLeast(0)), bn)
                    Text(if (bn) "শেষ হইবো $end" else "Ends at $end", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                }
            }
        }
        }
        }

        // ---- bottom: transport slides up from the bottom edge with its own shade
        AnimatedVisibility(
            visible = visible, modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
        ) {
        // (re)focus play/pause whenever the bottom bar appears or a side panel closes
        LaunchedEffect(c.panel) { if (c.panel == null) { delay(60); runCatching { playFr.requestFocus() } } }
        Column(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .8f))))
                .padding(start = 40.dp, end = 40.dp, top = 56.dp, bottom = 24.dp)
        ) {
            SeekPreview(c)
            SeekBar(c)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text(fmtTime(c.position), color = Color.White, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                Text("-" + fmtTime((c.duration - c.position).coerceAtLeast(0)), color = TextDim, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth()) {
                // left: tracks
                Row(Modifier.align(Alignment.CenterStart), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ControlButton(AppIcons.Subtitles, s.subtitles) { c.panel = PlayerController.Panel.SUBS }
                    ControlButton(AppIcons.Audio, s.audio) { c.panel = PlayerController.Panel.AUDIO }
                    if (c.altCopies.isNotEmpty() || c.altPlaying != null) {
                        ControlButton(AppIcons.Server, serverLabel()) { c.panel = PlayerController.Panel.SERVER }
                    }
                }
                // centre: transport
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    ControlButton(AppIcons.Rewind, null) { c.seekBy(-10_000) }
                    PillButton(
                        "", icon = if (c.isPlaying) AppIcons.Pause else Icons.Filled.PlayArrow,
                        primary = true, focusRequester = playFr, onClick = { c.togglePlay() },
                    )
                    ControlButton(AppIcons.Forward, null) { c.seekBy(10_000) }
                }
                // right: next episode + options
                Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (c.hasNext) {
                        PillButton(
                            c.nextItem?.subtitle?.let { "${s.nextEpisode} · $it" } ?: s.nextEpisode,
                            icon = AppIcons.SkipNext, compact = true, onClick = { c.next() },
                        )
                    }
                    ControlButton(Icons.Filled.MoreVert, s.options) { c.panel = PlayerController.Panel.OPTIONS }
                }
            }
        }
        }
    }
}

/** Icon button that reveals its label while focused (keeps the bar clean on TV). */
@Composable
private fun ControlButton(icon: ImageVector, label: String?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(Modifier.onFocusChanged { focused = it.hasFocus }) {
        PillButton(if (focused && label != null) label else "", icon = icon, compact = true, onClick = onClick)
    }
}

@Composable
private fun UpNextCard(c: PlayerController) {
    val s = LocalStrings.current
    val next = c.nextItem ?: return
    val left = ((c.duration - c.position) / 1000).coerceAtLeast(0)
    Column(
        Modifier.padding(end = 40.dp, bottom = 48.dp).widthIn(max = 340.dp)
            .glass(RoundedCornerShape(18.dp), tint = .12f).padding(16.dp)
    ) {
        Text(s.upNext.uppercase() + " · ${left}s", color = LocalAccent.current, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(next.subtitle ?: next.title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillButton(s.playNow, icon = AppIcons.SkipNext, primary = true, compact = true, onClick = { c.next() })
            Spacer(Modifier.width(10.dp))
            Text(s.okForNext, color = TextDim, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SeekBar(c: PlayerController) {
    var focused by remember { mutableStateOf(false) }
    val accent = LocalAccent.current
    val frac = if (c.duration > 0) (c.position.toFloat() / c.duration).coerceIn(0f, 1f) else 0f
    val h by animateDpAsState(if (focused) 8.dp else 5.dp, label = "seekH")
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(26.dp)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.key) {
                    Key.DirectionLeft -> { c.seekBy(-10_000); true }
                    Key.DirectionRight -> { c.seekBy(10_000); true }
                    Key.DirectionCenter, Key.Enter -> { c.togglePlay(); true }
                    else -> false
                }
            }
            .focusable()
            .pointerInput(c.duration) {
                detectTapGestures { o -> c.seekToFraction(o.x / size.width) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val w = maxWidth
        Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = .28f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(if (focused) Color.White else accent))
        }
        if (focused) {
            Box(Modifier.offset(x = w * frac - 9.dp).size(18.dp).clip(CircleShape).background(Color.White))
        }
    }
}

@Composable
private fun SidePanel(c: PlayerController) {
    val s = LocalStrings.current
    val panel = c.panel ?: return
    val title = when (panel) {
        PlayerController.Panel.AUDIO -> s.audio
        PlayerController.Panel.SUBS -> s.subtitles
        PlayerController.Panel.SYNC -> s.subtitleSync
        PlayerController.Panel.AUDIO_SYNC -> s.audioSync
        PlayerController.Panel.SPEED -> s.speed
        PlayerController.Panel.ASPECT -> s.aspect
        PlayerController.Panel.OPTIONS -> s.options
        PlayerController.Panel.SUB_SIZE -> s.subtitleSize
        PlayerController.Panel.SUB_COLOR -> s.subtitleColor
        PlayerController.Panel.SLEEP -> s.sleepTimer
        PlayerController.Panel.SERVER -> serverLabel()
    }
    Column(
        Modifier.padding(16.dp).width(360.dp).fillMaxHeight()
            .glass(RoundedCornerShape(24.dp), tint = .10f)
            .background(Color.Black.copy(alpha = .55f))
            .padding(24.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        when (panel) {
            PlayerController.Panel.AUDIO -> OptionList(
                remember(panel) { c.audioOptions().map { Opt(it.id, it.label, it.selected) } }
            ) { id -> if (id != null) c.selectAudio(id) }
            PlayerController.Panel.SUBS -> {
                val opts = remember(panel) {
                    val list = c.subtitleOptions()
                    listOf(Opt(null, s.off, list.none { it.selected })) + list.map { Opt(it.id, it.label, it.selected) }
                }
                OptionList(opts) { id -> c.selectSubtitle(id) }
            }
            PlayerController.Panel.SPEED -> {
                val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
                OptionList(speeds.map { Opt(it.toString(), "${it}x", it == c.speed) }) { id ->
                    id?.toFloatOrNull()?.let { c.setPlaybackSpeed(it) }
                }
            }
            PlayerController.Panel.ASPECT -> OptionList(
                listOf(s.fit, s.zoom, s.stretch).mapIndexed { i, l -> Opt(i.toString(), l, i == c.scaleMode) }
            ) { id -> id?.toIntOrNull()?.let { c.setScale(it) } }
            PlayerController.Panel.SYNC -> SyncPanel(c.subDelay, { c.shiftSubtitles(it) }, if (c.subtitleDelayAvailable) null else s.syncOnlyExternal)
            PlayerController.Panel.AUDIO_SYNC -> SyncPanel(c.audioDelay, { c.shiftAudio(it) }, null)
            PlayerController.Panel.SLEEP -> OptionList(
                listOf(
                    Opt("0", s.off, c.sleepAt == 0L && !c.sleepEndOfEpisode),
                ) + listOf(15, 30, 45, 60, 90).map { Opt("$it", s.minutesShort.format(it), false) } +
                    listOf(Opt("eoe", s.endOfEpisode, c.sleepEndOfEpisode))
            ) { id -> c.setSleep(if (id == "eoe") null else id?.toIntOrNull() ?: 0) }
            PlayerController.Panel.SERVER -> {
                val dhk = com.israk.dhakaflix.ui.components.isDhakaiya()
                val opts = listOf(Opt("", com.israk.dhakaflix.data.AltServers.serverOf(c.current.url), c.altPlaying == null)) +
                    c.altCopies.mapIndexed { i, a -> Opt("$i", "${a.server}  ·  ${a.label}", c.altPlaying?.url == a.url) }
                if (c.altSearching) Text(if (dhk) "অন্য সার্ভারে খুঁজতাছি…" else "Looking on other servers…", color = TextDim, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp))
                OptionList(opts) { id ->
                    val i = id?.toIntOrNull()
                    c.selectServer(if (i == null) null else c.altCopies.getOrNull(i))
                }
            }
            PlayerController.Panel.SUB_SIZE -> OptionList(
                listOf(s.sizeSmall, s.sizeMedium, s.sizeLarge, "XL").mapIndexed { i, l -> Opt(i.toString(), l, i == c.subSize) }
            ) { id -> id?.toIntOrNull()?.let { c.setSubtitleSize(it) } }
            PlayerController.Panel.SUB_COLOR -> OptionList(
                com.israk.dhakaflix.data.AppSettings.SUB_COLORS.mapIndexed { i, (_, name) -> Opt(i.toString(), "●  $name", i == c.subColor) }
            ) { id -> id?.toIntOrNull()?.let { c.setSubtitleColor(it) } }
            PlayerController.Panel.OPTIONS -> {
                val autoSkip by com.israk.dhakaflix.DhakaFlixApp.instance.settings.autoSkipIntro.collectAsState()
                OptionList(
                listOf(
                    Opt("sync", s.subtitleSync + "  (" + String.format(Locale.US, "%+.1fs", c.subDelay / 1000.0) + ")", false, chevron = true),
                    Opt("subsize", s.subtitleSize + "  (" + listOf(s.sizeSmall, s.sizeMedium, s.sizeLarge, "XL")[c.subSize.coerceIn(0, 3)] + ")", false, chevron = true),
                    Opt("subcolor", s.subtitleColor + "  (" + com.israk.dhakaflix.data.AppSettings.SUB_COLORS[c.subColor.coerceIn(0, 3)].second + ")", false, chevron = true),
                    Opt("async", s.audioSync + "  (" + String.format(Locale.US, "%+.2fs", c.audioDelay / 1000.0) + ")", false, chevron = true),
                    Opt("speed", s.speed + "  (" + c.speed + "x)", false, chevron = true),
                    Opt("aspect", s.aspect, false, chevron = true),
                    Opt("server", serverLabel() + "  (" + (c.altPlaying?.server ?: com.israk.dhakaflix.data.AltServers.serverOf(c.current.url)) + ")", false, chevron = true),
                    Opt("engine", s.switchEngine + "  (" + (if (c.kind == EngineKind.EXO) "ExoPlayer" else "LibVLC") + ")", false),
                    Opt("night", s.nightMode + "  ·  " + s.nightModeHint, c.nightMode),
                    Opt("autoskip", s.autoSkipIntro, autoSkip),
                    Opt("sleep", s.sleepTimer + "  (" + when {
                        c.sleepEndOfEpisode -> s.endOfEpisode
                        c.sleepAt > 0 -> s.sleepIn.format(s.minutesShort.format(((c.sleepAt - SystemClock.elapsedRealtime()) / 60_000L + 1).toInt()))
                        else -> s.off
                    } + ")", false, chevron = true),
                    Opt("vlc", s.openInVlc, false),
                )
            ) { id ->
                when (id) {
                    "sync" -> c.panel = PlayerController.Panel.SYNC
                    "async" -> c.panel = PlayerController.Panel.AUDIO_SYNC
                    "subsize" -> c.panel = PlayerController.Panel.SUB_SIZE
                    "subcolor" -> c.panel = PlayerController.Panel.SUB_COLOR
                    "speed" -> c.panel = PlayerController.Panel.SPEED
                    "aspect" -> c.panel = PlayerController.Panel.ASPECT
                    "server" -> c.panel = PlayerController.Panel.SERVER
                    "engine" -> { c.panel = null; c.switchEngine() }
                    "vlc" -> { c.panel = null; c.openInVlc() }
                    "night" -> c.toggleNightMode()
                    "autoskip" -> c.toggleAutoSkipIntro()
                    "sleep" -> c.panel = PlayerController.Panel.SLEEP
                }
            }
            }
        }
    }
}

private data class Opt(val id: String?, val label: String, val selected: Boolean, val chevron: Boolean = false)

@Composable
private fun OptionList(options: List<Opt>, onPick: (String?) -> Unit) {
    val first = remember { FocusRequester() }
    val focusIdx = options.indexOfFirst { it.selected }.takeIf { it >= 0 } ?: 0
    LaunchedEffect(options.size) { delay(80); runCatching { first.requestFocus() } }
    if (options.isEmpty()) {
        Text("—", color = TextDim)
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(options) { i, o ->
            FocusFrame(
                onClick = { onPick(o.id) }, focusedScale = 1.03f, shape = RoundedCornerShape(14.dp),
                focusRequester = if (i == focusIdx) first else null, modifier = Modifier.fillMaxWidth(),
            ) { focused ->
                Row(
                    Modifier.fillMaxWidth().background(if (focused) Color.White else Color.White.copy(alpha = .07f)).padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(o.label, Modifier.weight(1f), color = if (focused) Color.Black else Color.White, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (o.selected) Icon(Icons.Filled.Check, null, tint = if (focused) Color.Black else LocalAccent.current)
                    if (o.chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = if (focused) Color.Black else TextDim)
                }
            }
        }
    }
}

@Composable
private fun SyncPanel(valueMs: Long, shift: (Long?) -> Unit, note: String?) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { fr.requestFocus() } }
    Text(
        String.format(Locale.US, "%+.2f s", valueMs / 1000.0),
        style = MaterialTheme.typography.displaySmall, color = Color.White, fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(6.dp))
    Text("+ = later   ·   − = earlier", color = TextDim, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(18.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton("−0.5", compact = true, onClick = { shift(-500) })
        PillButton("−0.05", compact = true, focusRequester = fr, onClick = { shift(-50) })
        PillButton("+0.05", compact = true, onClick = { shift(50) })
        PillButton("+0.5", compact = true, onClick = { shift(500) })
    }
    Spacer(Modifier.height(10.dp))
    PillButton("0", icon = Icons.Filled.Refresh, compact = true, onClick = { shift(null) })
    if (note != null) {
        Spacer(Modifier.height(16.dp))
        Text(note, color = TextDim, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ExitPrompt(c: PlayerController) {
    val s = LocalStrings.current
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { fr.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 520.dp).glass(RoundedCornerShape(26.dp), tint = .12f).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(s.stopTitle, style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(listOfNotNull(c.current.title, c.current.subtitle).joinToString(" · ") + "  ·  " + fmtTime(c.position),
                color = TextDim, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PillButton(s.keepWatching, icon = Icons.Filled.PlayArrow, primary = true, focusRequester = fr, onClick = { c.exitAsk = false })
                PillButton(s.stopWatching, icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = { c.exit() })
            }
        }
    }
}

@Composable
private fun ResumePrompt(c: PlayerController, pos: Long) {
    val s = LocalStrings.current
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { fr.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .6f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 540.dp).glass(RoundedCornerShape(26.dp), tint = .12f).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(c.current.title, style = MaterialTheme.typography.titleLarge, color = Color.White, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
            c.current.subtitle?.let { Text(it, color = TextDim, style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PillButton(s.resumeFrom.format(fmtTime(pos)), icon = Icons.Filled.PlayArrow, primary = true, focusRequester = fr, onClick = { c.answerResume(true) })
                PillButton(s.startOver, icon = AppIcons.Rewind, onClick = { c.answerResume(false) })
            }
        }
    }
}

/** Before playback: pick which server to play from (shown only when the title is on more than one). */
@Composable
private fun ServerPrompt(c: PlayerController) {
    val dhk = com.israk.dhakaflix.ui.components.isDhakaiya()
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .7f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 560.dp).heightIn(max = 520.dp).glass(RoundedCornerShape(26.dp), tint = .12f).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(c.current.title, style = MaterialTheme.typography.titleLarge, color = Color.White, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
            c.current.subtitle?.let { Text(it, color = TextDim, style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(6.dp))
            Text(if (dhk) "কোন সার্ভার থেইকা চালামু?" else "Play from which server?", color = TextDim, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(18.dp))
            val opts = listOf(Opt("", com.israk.dhakaflix.data.AltServers.serverOf(c.current.url), true)) +
                c.altCopies.mapIndexed { i, a -> Opt("$i", "${a.server}  ·  ${a.label}", false) }
            OptionList(opts.map { it.copy(selected = false) }) { id ->
                val i = id?.toIntOrNull()
                c.pickServer(if (i == null) null else c.altCopies.getOrNull(i))
            }
        }
    }
}

@Composable
private fun ErrorOverlay(c: PlayerController, message: String) {
    val s = LocalStrings.current
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { fr.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .75f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 640.dp).glass(RoundedCornerShape(26.dp), tint = .12f).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(s.playbackError, style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(message, color = TextDim, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, maxLines = 4)
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(s.retry, icon = Icons.Filled.Refresh, primary = true, focusRequester = fr, onClick = { c.retry() })
                PillButton(s.switchEngine, icon = AppIcons.Engine, onClick = { c.switchEngine() })
                PillButton(s.openInVlc, icon = AppIcons.OpenExternal, onClick = { c.openInVlc() })
            }
        }
    }
}

/** Small frame of the seek target (if the device could grab one). */
@Composable
private fun ThumbImage(c: PlayerController) {
    val img = c.thumbs.image ?: return
    if (kotlin.math.abs(c.thumbs.imageAt - (c.previewTarget / 10_000) * 10_000) > 10_000) return
    androidx.compose.foundation.Image(
        img, null,
        Modifier.size(width = 224.dp, height = 126.dp).clip(RoundedCornerShape(12.dp))
            .border(2.dp, Color.White.copy(alpha = .8f), RoundedCornerShape(12.dp)),
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
    )
}

/** Thumbnail + time that floats above the seek bar while you're seeking. */
@Composable
private fun SeekPreview(c: PlayerController) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(c.previewAt) {
        now = SystemClock.elapsedRealtime(); delay(1600); now = SystemClock.elapsedRealtime()
    }
    val visible = c.previewAt > 0 && now - c.previewAt < 1500
    if (!visible) return
    // zero-height anchor: the preview floats above the seek bar without pushing the controls around
    Box(Modifier.fillMaxWidth().height(0.dp)) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().requiredHeight(176.dp).offset(y = (-96).dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        val frac = if (c.duration > 0) (c.position.toFloat() / c.duration).coerceIn(0f, 1f) else 0f
        val w = 224.dp
        val x = (maxWidth * frac - w / 2).coerceIn(0.dp, maxWidth - w)
        Column(Modifier.offset(x = x).width(w), horizontalAlignment = Alignment.CenterHorizontally) {
            ThumbImage(c)
            Spacer(Modifier.height(6.dp))
            Text(
                fmtTime(c.position),
                Modifier.glass(RoundedCornerShape(50), tint = .14f).padding(horizontal = 12.dp, vertical = 4.dp),
                color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
            )
        }
    }
    }
}

@Composable
private fun serverLabel(): String = if (com.israk.dhakaflix.ui.components.isDhakaiya()) "সার্ভার" else "Server"
