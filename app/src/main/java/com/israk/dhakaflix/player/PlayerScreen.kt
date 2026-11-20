package com.israk.dhakaflix.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
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
import com.israk.dhakaflix.ui.theme.LocalAccent
import com.israk.dhakaflix.ui.theme.LocalIsTv
import com.israk.dhakaflix.ui.theme.LocalStrings
import com.israk.dhakaflix.ui.theme.Surface1
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
    LaunchedEffect(c.controlsVisible, c.interaction, c.isPlaying, c.panel) {
        if (c.controlsVisible && c.isPlaying && c.panel == null) {
            delay(5000)
            c.controlsVisible = false
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

        c.subtitleText?.let { SubtitleOverlay(it, raised = c.controlsVisible) }

        if (c.buffering && c.resumeAsk == null && c.error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(54.dp), color = LocalAccent.current, strokeWidth = 4.dp)
        }

        AnimatedVisibility(
            visible = c.controlsVisible && c.resumeAsk == null && c.error == null,
            enter = fadeIn(), exit = fadeOut(),
        ) { Controls(c) }

        AnimatedVisibility(
            visible = c.panel != null,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) { SidePanel(c) }

        c.resumeAsk?.let { ResumePrompt(c, it) }
        c.error?.let { ErrorOverlay(c, it) }
        c.toast?.let {
            Text(
                it, Modifier.align(Alignment.TopCenter).padding(top = 28.dp)
                    .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .7f))
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                color = Color.White, style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun SubtitleOverlay(text: String, raised: Boolean) {
    val bottom by animateDpAsState(if (raised) 150.dp else 42.dp, label = "subPos")
    Box(Modifier.fillMaxSize().padding(bottom = bottom, start = 60.dp, end = 60.dp), contentAlignment = Alignment.BottomCenter) {
        Text(
            text,
            style = TextStyle(
                color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                shadow = Shadow(Color.Black, blurRadius = 8f),
            ),
            modifier = Modifier.background(Color.Black.copy(alpha = .35f), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun Controls(c: PlayerController) {
    val s = LocalStrings.current
    val isTv = LocalIsTv.current
    val playFr = remember { FocusRequester() }
    // (re)focus play/pause when controls appear or a side panel closes
    LaunchedEffect(c.panel) { if (c.panel == null) { delay(60); runCatching { playFr.requestFocus() } } }
    val item = c.current

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(140.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .8f), Color.Transparent))))
        Box(
            Modifier.fillMaxWidth().height(260.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .9f))))
        )
        Row(Modifier.padding(horizontal = 40.dp, vertical = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!isTv) {
                Icon(Icons.Filled.ArrowBack, null, tint = Color.White, modifier = Modifier.size(28.dp).clickable { c.exit() })
                Spacer(Modifier.width(16.dp))
            }
            Column {
                Text(item.title, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                item.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = TextDim, maxLines = 1) }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 40.dp, vertical = 26.dp)) {
            SeekBar(c)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text(fmtTime(c.position), color = Color.White, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                Text("-" + fmtTime((c.duration - c.position).coerceAtLeast(0)), color = TextDim, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("", icon = AppIcons.Rewind, compact = true, onClick = { c.seekBy(-10_000) })
                PillButton(
                    "", icon = if (c.isPlaying) AppIcons.Pause else Icons.Filled.PlayArrow,
                    primary = true, focusRequester = playFr, onClick = { c.togglePlay() },
                )
                PillButton("", icon = AppIcons.Forward, compact = true, onClick = { c.seekBy(10_000) })
                if (c.hasNext) PillButton(s.nextEpisode, icon = AppIcons.SkipNext, compact = true, onClick = { c.next() })
                Spacer(Modifier.weight(1f))
                PillButton(s.audio, icon = AppIcons.Audio, compact = true, onClick = { c.panel = PlayerController.Panel.AUDIO })
                PillButton(s.subtitles, icon = AppIcons.Subtitles, compact = true, onClick = { c.panel = PlayerController.Panel.SUBS })
                PillButton("", icon = AppIcons.Sync, compact = true, onClick = { c.panel = PlayerController.Panel.SYNC })
                PillButton("", icon = AppIcons.Speed, compact = true, onClick = { c.panel = PlayerController.Panel.SPEED })
                PillButton("", icon = AppIcons.Aspect, compact = true, onClick = { c.panel = PlayerController.Panel.ASPECT })
                PillButton(if (c.kind == EngineKind.EXO) "Exo" else "VLC", icon = AppIcons.Engine, compact = true, onClick = { c.switchEngine() })
                PillButton("", icon = AppIcons.OpenExternal, compact = true, onClick = { c.openInVlc() })
            }
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
        PlayerController.Panel.SPEED -> s.speed
        PlayerController.Panel.ASPECT -> s.aspect
    }
    Column(
        Modifier.width(380.dp).fillMaxHeight().background(Surface1.copy(alpha = .97f)).padding(28.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White)
        Spacer(Modifier.height(16.dp))
        when (panel) {
            PlayerController.Panel.AUDIO -> OptionList(
                remember(panel) { c.audioOptions().map { Triple<String?, String, Boolean>(it.id, it.label, it.selected) } }
            ) { id -> if (id != null) c.selectAudio(id) }
            PlayerController.Panel.SUBS -> {
                val opts = remember(panel) {
                    val list = c.subtitleOptions()
                    listOf(Triple<String?, String, Boolean>(null, s.off, list.none { it.selected })) +
                        list.map { Triple<String?, String, Boolean>(it.id, it.label, it.selected) }
                }
                OptionList(opts) { id -> c.selectSubtitle(id) }
            }
            PlayerController.Panel.SPEED -> {
                val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
                OptionList(speeds.map { Triple<String?, String, Boolean>(it.toString(), "${it}x", it == c.speed) }) { id ->
                    id?.toFloatOrNull()?.let { c.setPlaybackSpeed(it) }
                }
            }
            PlayerController.Panel.ASPECT -> OptionList(
                listOf(s.fit, s.zoom, s.stretch).mapIndexed { i, l -> Triple<String?, String, Boolean>(i.toString(), l, i == c.scaleMode) }
            ) { id -> id?.toIntOrNull()?.let { c.setScale(it) } }
            PlayerController.Panel.SYNC -> SyncPanel(c)
        }
    }
}

@Composable
private fun OptionList(options: List<Triple<String?, String, Boolean>>, onPick: (String?) -> Unit) {
    val first = remember { FocusRequester() }
    val focusIdx = options.indexOfFirst { it.third }.takeIf { it >= 0 } ?: 0
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    if (options.isEmpty()) {
        Text("—", color = TextDim)
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(options) { i, (id, label, selected) ->
            FocusFrame(
                onClick = { onPick(id) }, focusedScale = 1.03f, shape = RoundedCornerShape(12.dp),
                focusRequester = if (i == focusIdx) first else null, modifier = Modifier.fillMaxWidth(),
            ) { focused ->
                Row(
                    Modifier.fillMaxWidth().background(if (focused) Color.White else Color.White.copy(alpha = .06f)).padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, Modifier.weight(1f), color = if (focused) Color.Black else Color.White, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (selected) Icon(Icons.Filled.Check, null, tint = if (focused) Color.Black else LocalAccent.current)
                }
            }
        }
    }
}

@Composable
private fun SyncPanel(c: PlayerController) {
    val s = LocalStrings.current
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { fr.requestFocus() } }
    Text(
        String.format(Locale.US, "%+.2f s", c.subDelay / 1000.0),
        style = MaterialTheme.typography.displaySmall, color = Color.White, fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(6.dp))
    Text("+ = later   ·   − = earlier", color = TextDim, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(18.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton("−0.5", compact = true, onClick = { c.shiftSubtitles(-500) })
        PillButton("−0.1", compact = true, focusRequester = fr, onClick = { c.shiftSubtitles(-100) })
        PillButton("+0.1", compact = true, onClick = { c.shiftSubtitles(100) })
        PillButton("+0.5", compact = true, onClick = { c.shiftSubtitles(500) })
    }
    Spacer(Modifier.height(10.dp))
    PillButton("0", icon = Icons.Filled.Refresh, compact = true, onClick = { c.shiftSubtitles(null) })
    if (!c.subtitleDelayAvailable) {
        Spacer(Modifier.height(16.dp))
        Text(s.syncOnlyExternal, color = TextDim, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ResumePrompt(c: PlayerController, pos: Long) {
    val s = LocalStrings.current
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { fr.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .75f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(22.dp)).background(Surface1).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(c.current.title, style = MaterialTheme.typography.titleLarge, color = Color.White, textAlign = TextAlign.Center)
            c.current.subtitle?.let { Text(it, color = TextDim, style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PillButton(s.resumeFrom.format(fmtTime(pos)), icon = Icons.Filled.PlayArrow, primary = true, focusRequester = fr, onClick = { c.answerResume(true) })
                PillButton(s.startOver, icon = AppIcons.Rewind, onClick = { c.answerResume(false) })
            }
        }
    }
}

@Composable
private fun ErrorOverlay(c: PlayerController, message: String) {
    val s = LocalStrings.current
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { fr.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .8f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 620.dp).clip(RoundedCornerShape(22.dp)).background(Surface1).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(s.playbackError, style = MaterialTheme.typography.titleLarge, color = Color.White)
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
