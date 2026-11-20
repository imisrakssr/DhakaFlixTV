package com.israk.dhakaflix.player

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale

data class TrackOption(val id: String, val label: String, val selected: Boolean)

interface EngineListener {
    fun onEnded()
    fun onError(message: String)
    fun onStateChanged()
}

enum class EngineKind { EXO, VLC }

/** Minimal surface both ExoPlayer and LibVLC are driven through. */
interface VideoEngine {
    val kind: EngineKind
    var listener: EngineListener?
    fun createView(ctx: Context): View
    fun load(url: String, startMs: Long, externalSubs: List<String>)
    fun play()
    fun pause()
    val isPlaying: Boolean
    val isBuffering: Boolean
    val position: Long
    val duration: Long
    fun seekTo(ms: Long)
    fun audioTracks(): List<TrackOption>
    fun selectAudio(id: String)
    fun textTracks(): List<TrackOption>
    fun selectText(id: String?)
    /** Show/hide the engine's own subtitle rendering without changing tracks (no re-buffering). */
    fun setEngineSubtitlesVisible(visible: Boolean)
    val supportsSubtitleDelay: Boolean
    fun setSubtitleDelay(ms: Long)
    fun setSpeed(speed: Float)
    /** Audio delay in ms; positive plays sound later (fixes video lagging behind audio). */
    fun setAudioDelay(ms: Long)
    /** 0 = fit, 1 = zoom/crop, 2 = stretch */
    fun setScale(mode: Int)
    fun release()
}

private fun langName(code: String?): String? {
    if (code.isNullOrBlank() || code == "und") return null
    val n = Locale.forLanguageTag(code).displayLanguage
    return if (n.isNullOrBlank() || n == code) code.uppercase() else n
}

// ---------------------------------------------------------------- ExoPlayer

/** Shifts the audio clock the video syncs to, giving ExoPlayer an adjustable A/V offset. */
@OptIn(UnstableApi::class)
private class OffsetAudioSink(sink: AudioSink, private val offsetUs: () -> Long) : ForwardingAudioSink(sink) {
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val p = super.getCurrentPositionUs(sourceEnded)
        return if (p == AudioSink.CURRENT_POSITION_NOT_SET) p else p + offsetUs()
    }
}

@OptIn(UnstableApi::class)
private class SyncRenderersFactory(ctx: Context, private val offsetUs: () -> Long) : DefaultRenderersFactory(ctx) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        val base = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams) ?: return null
        return OffsetAudioSink(base, offsetUs)
    }
}

@OptIn(UnstableApi::class)
class ExoEngine(private val ctx: Context, preferSubs: Boolean) : VideoEngine {
    override val kind = EngineKind.EXO
    override var listener: EngineListener? = null
    private var view: PlayerView? = null
    @Volatile private var audioDelayUs = 0L

    private val player: ExoPlayer = ExoPlayer.Builder(
        ctx,
        SyncRenderersFactory(ctx) { audioDelayUs }
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true),
    )
        // LAN streaming on a 1 GB TV stick: modest buffer, quick start
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(8_000, 30_000, 1_000, 2_000)
                .setTargetBufferBytes(32 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()
        )
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                DefaultDataSource.Factory(
                    ctx,
                    DefaultHttpDataSource.Factory()
                        .setUserAgent("DhakaFlixTV")
                        .setConnectTimeoutMs(15_000)
                        .setReadTimeoutMs(20_000)
                        .setAllowCrossProtocolRedirects(true),
                ),
            )
        )
        .setHandleAudioBecomingNoisy(true)
        .build().apply {
        // jump to the nearest keyframe: seeking / skipping starts almost instantly
        setSeekParameters(SeekParameters.CLOSEST_SYNC)
        trackSelectionParameters = trackSelectionParameters.buildUpon().apply {
            if (preferSubs) {
                // pick a subtitle at load time (no re-buffer later): English, Bangla, or an untagged one
                setPreferredTextLanguages("en", "eng", "bn", "ben")
                setSelectUndeterminedTextLanguage(true)
            }
        }.build()
        addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) listener?.onEnded()
                listener?.onStateChanged()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { listener?.onStateChanged() }
            override fun onTracksChanged(tracks: Tracks) { listener?.onStateChanged() }
            override fun onPlayerError(error: PlaybackException) {
                listener?.onError("${error.errorCodeName}: ${error.message ?: ""}")
            }
        })
    }

    override fun createView(ctx: Context): View = PlayerView(ctx).apply {
        useController = false
        player = this@ExoEngine.player
        setShutterBackgroundColor(Color.BLACK)
        keepScreenOn = true
        subtitleView?.setFractionalTextSize(0.0533f * 1.1f)
    }.also { view = it }

    override fun load(url: String, startMs: Long, externalSubs: List<String>) {
        val subs = externalSubs.filter { it.endsWith(".ass", true) || it.endsWith(".ssa", true) }.map {
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(it))
                .setMimeType(MimeTypes.TEXT_SSA)
                .setLabel(Uri.decode(it.substringAfterLast('/')))
                .build()
        }
        val item = MediaItem.Builder().setUri(url).setSubtitleConfigurations(subs).build()
        player.setMediaItem(item, startMs.coerceAtLeast(0))
        player.prepare()
        player.playWhenReady = true
    }

    override fun play() = player.play()
    override fun pause() = player.pause()
    override val isPlaying get() = player.isPlaying
    override val isBuffering get() = player.playbackState == Player.STATE_BUFFERING
    override val position get() = player.currentPosition
    override val duration get() = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
    override fun seekTo(ms: Long) {
        // never let "+10s" snap back to an earlier keyframe (or "-10s" forward)
        player.setSeekParameters(if (ms >= player.currentPosition) SeekParameters.NEXT_SYNC else SeekParameters.PREVIOUS_SYNC)
        player.seekTo(ms.coerceAtLeast(0))
    }

    private fun tracksOf(type: Int): List<TrackOption> {
        val out = mutableListOf<TrackOption>()
        player.currentTracks.groups.forEachIndexed { gi, g ->
            if (g.type != type) return@forEachIndexed
            for (ti in 0 until g.length) {
                out += TrackOption("$gi:$ti", describe(g.getTrackFormat(ti), type, out.size + 1), g.isTrackSelected(ti))
            }
        }
        return out
    }

    private fun describe(f: Format, type: Int, n: Int): String {
        val parts = mutableListOf<String>()
        f.label?.takeIf { it.isNotBlank() }?.let { parts += it }
        langName(f.language)?.let { if (parts.none { p -> p.contains(it, true) }) parts += it }
        val mime = f.sampleMimeType ?: ""
        if (type == C.TRACK_TYPE_AUDIO) {
            when {
                mime.contains("eac3") -> parts += "E-AC3"
                mime.contains("ac3") -> parts += "AC3"
                mime.contains("dts") -> parts += "DTS"
                mime.contains("mp4a") -> parts += "AAC"
                mime.contains("opus") -> parts += "Opus"
                mime.contains("mpeg") -> parts += "MP3"
            }
            when (f.channelCount) { 1 -> parts += "Mono"; 2 -> parts += "Stereo"; 6 -> parts += "5.1"; 8 -> parts += "7.1" }
        } else {
            when {
                mime.contains("subrip") -> parts += "SRT"
                mime.contains("ssa") -> parts += "ASS"
                mime.contains("pgs") -> parts += "PGS"
                mime.contains("vobsub") -> parts += "VobSub"
                mime.contains("vtt") -> parts += "VTT"
            }
            if ((f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0) parts += "Forced"
        }
        return if (parts.isEmpty()) "Track $n" else parts.joinToString(" · ")
    }

    private var textHidden = false

    override fun audioTracks() = tracksOf(C.TRACK_TYPE_AUDIO)
    override fun textTracks() = tracksOf(C.TRACK_TYPE_TEXT).map { if (textHidden) it.copy(selected = false) else it }
    override fun selectAudio(id: String) = select(C.TRACK_TYPE_AUDIO, id)

    override fun selectText(id: String?) {
        if (id == null) {
            // hiding instead of disabling the track avoids a full re-buffer
            setEngineSubtitlesVisible(false)
            return
        }
        setEngineSubtitlesVisible(true)
        val already = tracksOf(C.TRACK_TYPE_TEXT).firstOrNull { it.id == id }?.selected == true
        if (!already) select(C.TRACK_TYPE_TEXT, id)
    }

    override fun setEngineSubtitlesVisible(visible: Boolean) {
        textHidden = !visible
        view?.subtitleView?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun select(type: Int, id: String) {
        val (gi, ti) = id.split(":").map { it.toInt() }
        val g = player.currentTracks.groups.getOrNull(gi) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, ti))
            .build()
    }

    override val supportsSubtitleDelay = false
    override fun setSubtitleDelay(ms: Long) {}
    override fun setSpeed(speed: Float) = player.setPlaybackSpeed(speed)
    override fun setAudioDelay(ms: Long) { audioDelayUs = ms * 1000 }

    override fun setScale(mode: Int) {
        view?.resizeMode = when (mode) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    override fun release() {
        listener = null
        view?.player = null
        player.release()
    }
}

// ---------------------------------------------------------------- LibVLC

class VlcEngine(ctx: Context, private val preferSubs: Boolean) : VideoEngine {
    override val kind = EngineKind.VLC
    override var listener: EngineListener? = null
    private val libVlc = LibVLC(
        ctx.applicationContext,
        arrayListOf("--http-reconnect", "--network-caching=1500", "--audio-time-stretch", "--subsdec-encoding=UTF-8"),
    )
    private val mp = MediaPlayer(libVlc)
    private var layout: VLCVideoLayout? = null
    private var buffering = false
    private var released = false

    init {
        mp.setEventListener(object : MediaPlayer.EventListener {
            override fun onEvent(event: MediaPlayer.Event) {
                when (event.type) {
                    MediaPlayer.Event.EndReached -> listener?.onEnded()
                    MediaPlayer.Event.EncounteredError -> listener?.onError("VLC could not play this file")
                    MediaPlayer.Event.Buffering -> { buffering = event.buffering < 100f; listener?.onStateChanged() }
                    MediaPlayer.Event.Playing, MediaPlayer.Event.Paused, MediaPlayer.Event.ESAdded -> {
                        buffering = false; listener?.onStateChanged()
                    }
                    else -> Unit
                }
            }
        })
    }

    override fun createView(ctx: Context): View = VLCVideoLayout(ctx).also { layout = it }

    override fun load(url: String, startMs: Long, externalSubs: List<String>) {
        val l = layout ?: return
        l.post {
            if (released) return@post
            if (!mp.vlcVout.areViewsAttached()) mp.attachViews(l, null, true, false)
            val media = Media(libVlc, Uri.parse(url))
            media.setHWDecoderEnabled(true, false)
            if (startMs > 0) media.addOption(":start-time=${startMs / 1000.0}")
            mp.media = media
            media.release()
            externalSubs.forEachIndexed { i, sub -> mp.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(sub), i == 0 && preferSubs) }
            mp.play()
        }
    }

    override fun play() { mp.play() }
    override fun pause() { mp.pause() }
    override val isPlaying get() = mp.isPlaying
    override val isBuffering get() = buffering
    override val position get() = mp.time.coerceAtLeast(0)
    override val duration get() = mp.length.coerceAtLeast(0)
    override fun seekTo(ms: Long) { mp.setTime(ms.coerceAtLeast(0)) }

    override fun audioTracks(): List<TrackOption> {
        val cur = mp.audioTrack
        return mp.audioTracks?.filter { it.id >= 0 }?.map { TrackOption(it.id.toString(), it.name ?: "Track ${it.id}", it.id == cur) } ?: emptyList()
    }

    override fun selectAudio(id: String) { mp.setAudioTrack(id.toInt()) }

    override fun textTracks(): List<TrackOption> {
        val cur = mp.spuTrack
        return mp.spuTracks?.filter { it.id >= 0 }?.map { TrackOption(it.id.toString(), it.name ?: "Subtitle ${it.id}", it.id == cur) } ?: emptyList()
    }

    override fun selectText(id: String?) { mp.setSpuTrack(id?.toInt() ?: -1) }
    override fun setEngineSubtitlesVisible(visible: Boolean) { if (!visible) mp.setSpuTrack(-1) }

    override val supportsSubtitleDelay = true
    override fun setSubtitleDelay(ms: Long) { mp.setSpuDelay(ms * 1000) }
    override fun setSpeed(speed: Float) { mp.setRate(speed) }
    override fun setAudioDelay(ms: Long) { mp.setAudioDelay(ms * 1000) }

    override fun setScale(mode: Int) {
        mp.setVideoScale(
            when (mode) {
                1 -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
                2 -> MediaPlayer.ScaleType.SURFACE_FILL
                else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
            }
        )
    }

    override fun release() {
        released = true
        listener = null
        runCatching { mp.setEventListener(null) }
        runCatching { mp.stop() }
        runCatching { mp.detachViews() }
        mp.release()
        libVlc.release()
    }
}
