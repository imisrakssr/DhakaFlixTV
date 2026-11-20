package com.israk.dhakaflix.player

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.israk.dhakaflix.DhakaFlixApp
import com.israk.dhakaflix.data.PlayItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Holds all player state; the Compose UI only reads it and calls the actions below. */
class PlayerController(
    private val activity: ComponentActivity,
    private val app: DhakaFlixApp,
    val playlist: List<PlayItem>,
    startIndex: Int,
    private val startMode: String,
) : EngineListener {

    enum class Panel { AUDIO, SUBS, SYNC, SPEED, ASPECT }

    private val repo = app.library
    private val settings = app.settings
    private val scope = activity.lifecycleScope

    var index by mutableIntStateOf(startIndex)
        private set
    val current: PlayItem get() = playlist[index]
    val hasNext: Boolean get() = index < playlist.lastIndex

    var kind by mutableStateOf(if (settings.engine.value == "vlc") EngineKind.VLC else EngineKind.EXO)
        private set
    var engine by mutableStateOf<VideoEngine?>(null)
        private set
    var viewGeneration by mutableIntStateOf(0)
        private set

    var isPlaying by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(true)
        private set
    var position by mutableLongStateOf(0L)
        private set
    var duration by mutableLongStateOf(0L)
        private set

    var controlsVisible by mutableStateOf(true)
    var interaction by mutableIntStateOf(0)
        private set
    var panel by mutableStateOf<Panel?>(null)
    var resumeAsk by mutableStateOf<Long?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var toast by mutableStateOf<String?>(null)

    var ownSubUrl by mutableStateOf<String?>(null)
        private set
    private var cues: List<Cue> = emptyList()
    var subtitleText by mutableStateOf<String?>(null)
        private set
    var subDelay by mutableLongStateOf(0L)
        private set
    var speed by mutableFloatStateOf(1f)
        private set
    var scaleMode by mutableIntStateOf(0)
        private set

    private var pendingStart: Long? = null
    private var triedFallback = false
    private var endedHandled = false
    private var lastSave = 0L
    private var seekTarget = -1L
    private var seekAt = 0L

    fun begin() { scope.launch { prepare(index, startMode) } }

    private suspend fun prepare(i: Int, mode: String) {
        index = i
        error = null; panel = null; endedHandled = false; triedFallback = false
        clearOwnSubs()
        position = 0; duration = 0
        val saved = repo.savedPosition(playlist[i].url)
        val resumable = saved?.takeIf {
            !it.completed && it.positionMs > 20_000 && (it.durationMs == 0L || it.positionMs < it.durationMs * 0.95)
        }?.positionMs
        when {
            mode == PlayerActivity.MODE_START || resumable == null -> start(0)
            mode == PlayerActivity.MODE_RESUME -> start(resumable)
            else -> { engine?.pause(); resumeAsk = resumable }
        }
    }

    fun answerResume(resume: Boolean) {
        val p = resumeAsk ?: 0L
        resumeAsk = null
        start(if (resume) p else 0L)
    }

    private fun start(pos: Long) {
        pendingStart = pos
        val e = engine
        if (e == null || e.kind != kind) recreateEngine() else loadInto(e)
    }

    private fun recreateEngine() {
        engine?.release()
        engine = createEngine()
        viewGeneration++
    }

    private fun createEngine(): VideoEngine =
        (if (kind == EngineKind.EXO) ExoEngine(activity, settings.subsDefault.value) else VlcEngine(activity))
            .also { it.listener = this }

    /** Called by the AndroidView factory whenever [viewGeneration] changes. */
    fun viewFor(ctx: Context): View {
        val e = engine ?: createEngine().also { engine = it }
        val v = e.createView(ctx)
        loadInto(e)
        return v
    }

    private fun loadInto(e: VideoEngine) {
        val startAt = pendingStart ?: return
        pendingStart = null
        val item = current
        val engineSubs = if (e.kind == EngineKind.VLC) item.subs else item.subs.filterNot { SubtitleParser.isOwnRendered(it) }
        e.load(item.url, startAt, engineSubs)
        buffering = true
        showControls()
        scope.launch {
            delay(1500)
            engine?.let {
                if (speed != 1f) it.setSpeed(speed)
                if (scaleMode != 0) it.setScale(scaleMode)
                if (it.supportsSubtitleDelay && subDelay != 0L) it.setSubtitleDelay(subDelay)
            }
        }
        if (e.kind == EngineKind.EXO && settings.subsDefault.value) {
            item.subs.firstOrNull { SubtitleParser.isOwnRendered(it) }?.let { selectOwnSub(it) }
        }
    }

    // ------------------------------------------------------------ periodic

    fun tick() {
        val e = engine ?: return
        position = e.position
        duration = e.duration
        isPlaying = e.isPlaying
        buffering = e.isBuffering
        subtitleText = if (ownSubUrl != null) SubtitleParser.at(cues, position, subDelay) else null
        val now = SystemClock.elapsedRealtime()
        if (now - lastSave > 10_000 && duration > 0 && position > 0) {
            lastSave = now
            save(false)
        }
    }

    fun save(forceCompleted: Boolean = false) {
        val e = engine ?: return
        val pos = e.position
        val dur = e.duration
        if (dur <= 0) return
        val done = forceCompleted || pos > dur * 0.92
        val item = current
        app.appScope.launch { repo.saveProgress(item, if (done) dur else pos, dur, done) }
    }

    // ------------------------------------------------------------ actions

    fun showControls() { controlsVisible = true; interaction++ }

    fun toggleControls() { if (controlsVisible) controlsVisible = false else showControls() }

    fun togglePlay() {
        val e = engine ?: return
        if (e.isPlaying) e.pause() else e.play()
        isPlaying = !isPlaying
        showControls()
    }

    fun seekBy(delta: Long) {
        val e = engine ?: return
        val now = SystemClock.elapsedRealtime()
        val base = if (seekTarget >= 0 && now - seekAt < 1200) seekTarget else e.position
        val max = if (duration > 0) duration - 1000 else Long.MAX_VALUE
        val t = (base + delta).coerceIn(0, max)
        seekTarget = t; seekAt = now
        e.seekTo(t)
        position = t
        showControls()
    }

    fun seekToFraction(f: Float) {
        if (duration <= 0) return
        engine?.seekTo((duration * f).toLong())
        showControls()
    }

    fun next(auto: Boolean = false) {
        if (!hasNext) { save(auto); activity.finish(); return }
        if (auto && !settings.autoNext.value) { activity.finish(); return }
        if (!auto) save()
        scope.launch { prepare(index + 1, PlayerActivity.MODE_RESUME) }
    }

    fun switchEngine() {
        val pos = engine?.position ?: 0L
        save()
        kind = if (kind == EngineKind.EXO) EngineKind.VLC else EngineKind.EXO
        error = null
        toast = if (kind == EngineKind.VLC) "LibVLC" else "ExoPlayer"
        clearOwnSubs()
        start(pos)
    }

    fun retry() {
        val pos = engine?.position ?: 0L
        error = null
        clearOwnSubs()
        pendingStart = pos
        recreateEngine() // the new view's factory loads pendingStart
    }

    fun openInVlc() {
        engine?.pause()
        save()
        ExternalPlayer.openInVlc(activity, current, engine?.position ?: 0L)
    }

    fun audioOptions(): List<TrackOption> = engine?.audioTracks() ?: emptyList()

    fun selectAudio(id: String) { engine?.selectAudio(id); panel = null; showControls() }

    /** Embedded/engine tracks plus our own sync-able external files (ExoPlayer only). */
    fun subtitleOptions(): List<TrackOption> {
        val e = engine ?: return emptyList()
        val engineTracks = e.textTracks().map { if (ownSubUrl != null) it.copy(selected = false) else it }
        val own = if (e.kind == EngineKind.EXO) current.subs.filter { SubtitleParser.isOwnRendered(it) }.map {
            TrackOption("ext:$it", "⧉ " + android.net.Uri.decode(it.substringAfterLast('/')), it == ownSubUrl)
        } else emptyList()
        return engineTracks + own
    }

    fun selectSubtitle(id: String?) {
        val e = engine ?: return
        when {
            id == null -> { clearOwnSubs(); e.selectText(null) }
            id.startsWith("ext:") -> selectOwnSub(id.removePrefix("ext:"))
            else -> { clearOwnSubs(); e.selectText(id) }
        }
        panel = null
        showControls()
    }

    private fun selectOwnSub(url: String) {
        scope.launch {
            val list = runCatching { SubtitleParser.load(app.http, url) }.getOrDefault(emptyList())
            if (list.isNotEmpty()) {
                cues = list
                ownSubUrl = url
                engine?.selectText(null)
            } else toast = "Subtitle could not be loaded"
        }
    }

    private fun clearOwnSubs() { ownSubUrl = null; cues = emptyList(); subtitleText = null }

    val subtitleDelayAvailable: Boolean get() = ownSubUrl != null || engine?.supportsSubtitleDelay == true

    fun shiftSubtitles(deltaMs: Long?) {
        subDelay = if (deltaMs == null) 0L else (subDelay + deltaMs).coerceIn(-60_000, 60_000)
        if (ownSubUrl == null) engine?.takeIf { it.supportsSubtitleDelay }?.setSubtitleDelay(subDelay)
        interaction++
    }

    fun setPlaybackSpeed(v: Float) { speed = v; engine?.setSpeed(v); panel = null; showControls() }

    fun setScale(mode: Int) { scaleMode = mode; engine?.setScale(mode); panel = null; showControls() }

    // ------------------------------------------------------------ keys

    fun handleKey(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { if (!down) onBack(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_SPACE -> { if (down && e.repeatCount == 0) togglePlay(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (down) { engine?.play(); showControls() }; return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (down) { engine?.pause(); showControls() }; return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) seekBy(30_000); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) seekBy(-30_000); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { if (down && hasNext) next(); return true }
        }
        if (resumeAsk != null || error != null || panel != null) { if (down) interaction++; return false }
        if (controlsVisible) { if (down) interaction++; return false }
        // Controls hidden: the remote drives playback directly
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down && e.repeatCount == 0) togglePlay(); return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) seekBy(-step(e)); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) seekBy(step(e)); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                if (down) showControls(); return true
            }
        }
        return false
    }

    private fun step(e: KeyEvent): Long = 10_000L * (1 + (e.repeatCount / 4)).coerceAtMost(6)

    private fun onBack() {
        when {
            panel != null -> panel = null
            resumeAsk != null -> activity.finish()
            controlsVisible && error == null -> controlsVisible = false
            else -> { save(); activity.finish() }
        }
    }

    // ------------------------------------------------------------ engine callbacks

    override fun onEnded() {
        activity.runOnUiThread {
            if (endedHandled) return@runOnUiThread
            endedHandled = true
            save(true)
            next(auto = true)
        }
    }

    override fun onError(message: String) {
        activity.runOnUiThread {
            if (kind == EngineKind.EXO && settings.engine.value == "auto" && !triedFallback) {
                triedFallback = true
                val pos = maxOf(engine?.position ?: 0L, 0L)
                toast = "ExoPlayer → LibVLC"
                kind = EngineKind.VLC
                clearOwnSubs()
                start(pos)
            } else {
                error = message
                controlsVisible = false
            }
        }
    }

    override fun onStateChanged() {}

    fun onStop() { save(); engine?.pause() }

    fun exit() { save(); activity.finish() }

    fun release() {
        save()
        engine?.release()
        engine = null
    }
}
