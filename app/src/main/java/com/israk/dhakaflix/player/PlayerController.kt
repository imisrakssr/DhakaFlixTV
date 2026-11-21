package com.israk.dhakaflix.player

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
    initialPlaylist: List<PlayItem>,
    startIndex: Int,
    private val startMode: String,
) : EngineListener {

    enum class Panel { AUDIO, SUBS, SYNC, AUDIO_SYNC, SPEED, ASPECT, OPTIONS, SUB_SIZE, SUB_COLOR, SLEEP }

    val playlist = mutableStateListOf<PlayItem>().apply { addAll(initialPlaylist) }

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
    var subDelay by mutableLongStateOf(settings.subDelay.value)
        private set
    var audioDelay by mutableLongStateOf(settings.audioDelay.value)
        private set

    /** Exit confirmation shown when BACK is pressed during playback. */
    var exitAsk by mutableStateOf(false)
    var speed by mutableFloatStateOf(1f)
        private set
    var scaleMode by mutableIntStateOf(0)
        private set

    /** Library entry of what's playing (for the pause screen) + the current episode's TMDB info. */
    var meta by mutableStateOf<com.israk.dhakaflix.data.db.ItemEntity?>(null)
        private set
    var episodeInfo by mutableStateOf<com.israk.dhakaflix.data.tmdb.TmdbEpisode?>(null)
        private set
    /** "You're watching" screen shown after a while in pause. */
    var pauseInfo by mutableStateOf(false)

    private var tracksRestored = false

    // ---- skip intro (learned per series from the user's own fast-forward)
    private val introStore = settings.store("intro_memory")
    var introWindow by mutableStateOf<Pair<Long, Long>?>(null)
        private set
    private var introSkipped = false
    private var seekOrigin = -1L
    val showSkipIntro: Boolean
        get() {
            val w = introWindow ?: return false
            return !introSkipped && current.episode != null && position in (w.first - 1500)..(w.second - 4000)
        }

    fun skipIntro() {
        val w = introWindow ?: return
        introSkipped = true
        engine?.seekTo(w.second)
        position = w.second
    }

    // ---- sleep timer
    /** elapsedRealtime when playback should pause; 0 = off. */
    var sleepAt by mutableLongStateOf(0L)
        private set
    var sleepEndOfEpisode by mutableStateOf(false)
        private set
    fun setSleep(minutes: Int?) {
        // minutes: null = end of episode, 0 = off
        sleepEndOfEpisode = minutes == null
        sleepAt = if (minutes != null && minutes > 0) SystemClock.elapsedRealtime() + minutes * 60_000L else 0L
        panel = PlayerController.Panel.OPTIONS
    }

    // ---- night mode
    var nightMode by mutableStateOf(settings.nightMode.value)
        private set
    fun toggleNightMode() {
        nightMode = !nightMode
        settings.setNightMode(nightMode)
        engine?.setNightMode(nightMode)
        if (kind == EngineKind.VLC) toast = "VLC: next video"
    }

    private var pendingStart: Long? = null
    private var triedFallback = false
    private var endedHandled = false
    private var lastSave = 0L
    private var seekTarget = -1L
    private var seekJob: kotlinx.coroutines.Job? = null
    private var seekAt = 0L
    private var fetchedNextSeasonFor: Int? = null

    /** Last hidden-controls seek, for the on-screen "+10s" bubble. */
    var seekFlashAt by mutableLongStateOf(0L)
        private set
    var seekFlashDelta by mutableLongStateOf(0L)
        private set

    /** Seek preview frames + when the user last moved the seek position. */
    val thumbs = SeekThumbnailer(scope)
    var previewAt by mutableLongStateOf(0L)
        private set
    val previewTarget: Long get() = seekTarget

    /** "Up next" card in the last 40 seconds of an episode. */
    val showUpNext: Boolean
        get() = hasNext && current.episode != null && duration > 120_000 && duration - position in 1L..40_000L

    val nextItem: PlayItem? get() = playlist.getOrNull(index + 1)

    fun begin() { scope.launch { prepare(index, startMode) } }

    private suspend fun prepare(i: Int, mode: String) {
        index = i
        subsChecked = false
        tracksRestored = false
        pauseInfo = false
        introSkipped = false
        introWindow = introStore.getString(playlist[i].itemId, null)?.split(',')
            ?.mapNotNull { it.toLongOrNull() }?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
        loadMeta(playlist[i])
        prefetchNextSeason()
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

    private fun loadMeta(p: PlayItem) {
        scope.launch {
            val m = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.db.items().get(p.itemId) }
            meta = m
            episodeInfo = null
            val tmdb = m?.tmdbId
            if (m?.isSeries == true && tmdb != null && p.season != null && p.episode != null) {
                episodeInfo = repo.tmdbSeason(tmdb, p.season)[p.episode]
            }
        }
    }

    /** When the last loaded episode starts, append the next season so "Next" keeps working. */
    private fun prefetchNextSeason() {
        val cur = current
        val season = cur.season ?: return
        if (index != playlist.lastIndex || fetchedNextSeasonFor == season) return
        fetchedNextSeasonFor = season
        scope.launch {
            val more = repo.nextSeasonPlaylist(cur.itemId, season)
            if (more.isNotEmpty() && playlist.none { it.url == more.first().url }) playlist.addAll(more)
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

    var subSize by mutableIntStateOf(settings.subSize.value)
        private set
    var subColor by mutableIntStateOf(settings.subColor.value)
        private set
    val subScale: Float get() = com.israk.dhakaflix.data.AppSettings.SUB_SCALES[subSize.coerceIn(0, 3)]
    val subColorArgb: Int get() = com.israk.dhakaflix.data.AppSettings.SUB_COLORS[subColor.coerceIn(0, 3)].first

    private fun createEngine(): VideoEngine =
        (if (kind == EngineKind.EXO) ExoEngine(activity, settings.subsDefault.value)
        else VlcEngine(activity, settings.subsDefault.value, subScale, subColorArgb))
            .also { it.listener = this; it.setAudioDelay(audioDelay); it.setSubtitleStyle(subScale, subColorArgb); it.setNightMode(nightMode) }

    fun setSubtitleSize(i: Int) {
        subSize = i; settings.setSubSize(i)
        engine?.setSubtitleStyle(subScale, subColorArgb)
        if (kind == EngineKind.VLC) toast = "VLC: next video"
        panel = Panel.OPTIONS
    }

    fun setSubtitleColor(i: Int) {
        subColor = i; settings.setSubColor(i)
        engine?.setSubtitleStyle(subScale, subColorArgb)
        if (kind == EngineKind.VLC) toast = "VLC: next video"
        panel = Panel.OPTIONS
    }

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
        val mem = TrackMemory.forFile(item.url) ?: TrackMemory.forTitle(item.itemId)
        mem?.aspect?.let { scaleMode = it }
        e.load(item.url, startAt, engineSubs)
        buffering = true
        showControls()
        scope.launch {
            delay(1500)
            engine?.let {
                if (speed != 1f) it.setSpeed(speed)
                if (scaleMode != 0) it.setScale(scaleMode)
                if (it.supportsSubtitleDelay && subDelay != 0L) it.setSubtitleDelay(subDelay)
                if (audioDelay != 0L) it.setAudioDelay(audioDelay)
            }
        }
        val memSub = mem?.sub
        if (e.kind == EngineKind.EXO && memSub != null && memSub.startsWith("ext:")) {
            // remembered external subtitle: this file's own, or (next episode) its first external one
            val url = memSub.removePrefix("ext:").takeIf { it in item.subs } ?: item.subs.firstOrNull { SubtitleParser.isOwnRendered(it) }
            if (url != null) { subsChecked = true; selectOwnSub(url) }
        } else if (memSub == null && e.kind == EngineKind.EXO && settings.subsDefault.value) {
            // external .srt next to the video is usually the better match: load it right away
            item.subs.firstOrNull { SubtitleParser.isOwnRendered(it) }?.let { subsChecked = true; selectOwnSub(it) }
        }
    }

    // ------------------------------------------------------------ periodic

    private var subsChecked = false

    /** "Subtitles on by default": if nothing got picked automatically, turn the first one on. */
    private fun ensureDefaultSubs(e: VideoEngine) {
        if (subsChecked || !settings.subsDefault.value) return
        subsChecked = true
        if (ownSubUrl != null) return
        val tracks = e.textTracks()
        if (tracks.any { it.selected }) return
        val own = current.subs.firstOrNull { SubtitleParser.isOwnRendered(it) }
        when {
            e.kind == EngineKind.EXO && own != null -> selectOwnSub(own)
            tracks.isNotEmpty() -> e.selectText(tracks.first().id)
        }
    }

    /** Re-apply the audio / subtitle remembered for this file (or title). */
    private fun restoreTracks(e: VideoEngine) {
        tracksRestored = true
        val item = current
        val mem = TrackMemory.forFile(item.url) ?: TrackMemory.forTitle(item.itemId) ?: return
        fun pick(list: List<TrackOption>, id: String?, label: String?): TrackOption? =
            list.firstOrNull { it.id == id && it.label == label } ?: list.firstOrNull { label != null && it.label == label }
                ?: list.firstOrNull { it.id == id }
        if (mem.audioId != null || mem.audioLabel != null) {
            val a = pick(e.audioTracks(), mem.audioId, mem.audioLabel)
            if (a != null && !a.selected) e.selectAudio(a.id)
        }
        when (val sub = mem.sub) {
            null -> Unit
            "off" -> { subsChecked = true; clearOwnSubs(); e.selectText(null) }
            else -> if (!sub.startsWith("ext:")) {
                val t = pick(e.textTracks(), sub, mem.subLabel)
                if (t != null) { subsChecked = true; clearOwnSubs(); if (!t.selected) e.selectText(t.id) }
            }
        }
    }

    fun tick() {
        val e = engine ?: return
        if (seekJob?.isActive != true && SystemClock.elapsedRealtime() - seekAt > 900) position = e.position
        duration = e.duration
        if (!tracksRestored && duration > 0 && position > 1000) restoreTracks(e)
        if (!subsChecked && duration > 0 && position > 1500) ensureDefaultSubs(e)
        isPlaying = e.isPlaying
        buffering = e.isBuffering
        if (ownSubUrl != null) subtitleText = SubtitleParser.at(cues, position, subDelay)
        if (settings.autoSkipIntro.value && showSkipIntro && position > (introWindow?.first ?: 0)) { skipIntro(); toast = "⏭ " + "Intro" }
        if (sleepAt > 0 && SystemClock.elapsedRealtime() >= sleepAt) {
            sleepAt = 0
            e.pause(); isPlaying = false
            save()
            toast = sleepMessage
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastSave > 10_000 && duration > 0 && position > 0) {
            lastSave = now
            save(false)
        }
    }

    /** Set by the screen (localised). */
    var sleepMessage = "Sleep timer"

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

    fun toggleControls() { if (pauseInfo) { pauseInfo = false; showControls() } else if (controlsVisible) controlsVisible = false else showControls() }

    fun togglePlay() {
        val e = engine ?: return
        if (e.isPlaying) { e.pause(); save(); flushSync() } else e.play()
        isPlaying = !isPlaying
        showControls()
    }

    fun seekBy(delta: Long) {
        val e = engine ?: return
        val now = SystemClock.elapsedRealtime()
        val fresh = !(seekTarget >= 0 && now - seekAt < 1200)
        if (fresh) seekOrigin = e.position
        val base = if (!fresh) seekTarget else e.position
        val max = if (duration > 0) duration - 1000 else Long.MAX_VALUE
        val t = (base + delta).coerceIn(0, max)
        seekTarget = t; seekAt = now
        position = t
        previewAt = now
        thumbs.request(current.url, t)
        // coalesce rapid presses into one real seek (each seek restarts buffering)
        seekJob?.cancel()
        seekJob = scope.launch {
            delay(350)
            engine?.seekTo(seekTarget)
            learnIntro(seekOrigin, seekTarget)
        }
        if (!controlsVisible) {
            seekFlashDelta = if (now - seekFlashAt < 1500) seekFlashDelta + delta else delta
            seekFlashAt = now
        } else showControls()
    }

    /** A 30 s – 3 min jump forward in the first 10 minutes of an episode is almost always "skip the intro". */
    private fun learnIntro(from: Long, to: Long) {
        if (current.episode == null || from < 0 || from > 10 * 60_000L) return
        val d = to - from
        if (d !in 30_000L..180_000L) return
        introWindow = from to to
        introSkipped = true
        introStore.edit().putString(current.itemId, "$from,$to").apply()
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

    fun selectAudio(id: String) {
        val label = audioOptions().firstOrNull { it.id == id }?.label
        engine?.selectAudio(id); panel = null; showControls()
        TrackMemory.update(current.url, current.itemId) { it.copy(audioId = id, audioLabel = label) }
    }

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
        val label = id?.let { i -> subtitleOptions().firstOrNull { it.id == i }?.label }
        TrackMemory.update(current.url, current.itemId) { it.copy(sub = id ?: "off", subLabel = label) }
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
                engine?.setEngineSubtitlesVisible(false)
            } else toast = "Subtitle could not be loaded"
        }
    }

    private fun clearOwnSubs() { ownSubUrl = null; cues = emptyList(); subtitleText = null }

    val subtitleDelayAvailable: Boolean get() = ownSubUrl != null || engine?.supportsSubtitleDelay == true

    fun shiftSubtitles(deltaMs: Long?) {
        subDelay = if (deltaMs == null) 0L else (subDelay + deltaMs).coerceIn(-60_000, 60_000)
        settings.setSubDelay(subDelay)
        if (ownSubUrl == null) engine?.takeIf { it.supportsSubtitleDelay }?.setSubtitleDelay(subDelay)
        interaction++
    }

    fun shiftAudio(deltaMs: Long?) {
        audioDelay = if (deltaMs == null) com.israk.dhakaflix.data.AppSettings.DEFAULT_AUDIO_DELAY else (audioDelay + deltaMs).coerceIn(-3_000, 3_000)
        engine?.setAudioDelay(audioDelay)
        settings.setAudioDelay(audioDelay)
        interaction++
    }

    fun setPlaybackSpeed(v: Float) { speed = v; engine?.setSpeed(v); panel = null; showControls() }

    fun setScale(mode: Int) {
        scaleMode = mode; engine?.setScale(mode); panel = null; showControls()
        TrackMemory.update(current.url, current.itemId) { it.copy(aspect = mode) }
    }

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
        if (pauseInfo) {
            // any key just wakes the player up again
            if (down && e.repeatCount == 0) { pauseInfo = false; showControls() }
            return true
        }
        if (resumeAsk != null || error != null || panel != null || exitAsk) { if (down) interaction++; return false }
        if (controlsVisible) { if (down) interaction++; return false }
        // Controls hidden: the remote drives playback directly
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down && e.repeatCount == 0) { if (showSkipIntro) skipIntro() else if (showUpNext) next() else togglePlay() }
                return true
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
            pauseInfo -> { pauseInfo = false; showControls() }
            exitAsk -> exitAsk = false
            panel != null -> panel = PlayerController.Panel.OPTIONS.takeIf { panel in setOf(Panel.SYNC, Panel.AUDIO_SYNC, Panel.SPEED, Panel.ASPECT, Panel.SUB_SIZE, Panel.SUB_COLOR, Panel.SLEEP) }
            resumeAsk != null -> activity.finish()
            error != null -> { save(); activity.finish() }
            controlsVisible -> controlsVisible = false
            else -> exitAsk = true
        }
    }

    // ------------------------------------------------------------ engine callbacks

    override fun onEnded() {
        activity.runOnUiThread {
            if (endedHandled) return@runOnUiThread
            endedHandled = true
            save(true)
            if (sleepEndOfEpisode) { sleepEndOfEpisode = false; toast = sleepMessage; activity.finish(); return@runOnUiThread }
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
                com.israk.dhakaflix.data.DiagLog.add("Player", "${kind.name}: $message · ${com.israk.dhakaflix.data.LibraryRepository.fileName(current.url)}")
            }
        }
    }

    override fun onStateChanged() {}

    override fun onSubtitleText(text: String?) { if (ownSubUrl == null) subtitleText = text }

    fun onStop() { save(); flushSync(); engine?.pause() }

    /** Tell Simkl where we stopped, so another device can resume from here. */
    private fun flushSync() {
        val e = engine ?: return
        repo.simkl.onProgress(current, e.position, e.duration, force = true)
    }

    private var released = false

    fun exit() { save(); activity.finish() }

    fun release() {
        if (released) return
        released = true
        save()
        flushSync()
        repo.discovery.refreshBecause()
        engine?.pause()
        engine?.release()
        engine = null
        thumbs.release()
    }
}
