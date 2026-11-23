package com.israk.dhakaflix.player

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.TreeMap
import java.util.concurrent.Executors

/**
 * Seek preview frames for the seek bar (TV remote and phone drag).
 *
 * - While a video plays, a strip of ~60 small frames across the whole film is filled in slowly in the
 *   background (never while buffering), so scrubbing shows a picture straight away.
 * - While seeking, the exact spot is fetched too; until it arrives the nearest frame already known is shown.
 * - One frame at a time on one background thread, small RGB_565 bitmaps (≈40 KB each) for 1 GB TV sticks.
 * If the device can't read frames from the file, the preview just shows the time.
 */
class SeekThumbnailer(private val scope: CoroutineScope, private val lowEnd: Boolean) {
    private val worker = Executors.newSingleThreadExecutor()
    private val dispatcher = worker.asCoroutineDispatcher()
    private var retriever: MediaMetadataRetriever? = null
    private var sourceUrl: String? = null
    private var broken = false

    private val frames = TreeMap<Long, Bitmap>()   // position (ms) -> frame, guarded by `this`
    private val maxFrames = 90
    @Volatile private var busy = false
    @Volatile private var wanted = -1L            // exact position the user is seeking to
    private var url: String? = null
    private var lastPrefetch = 0L

    /** Frame to show (nearest known to the seek target) and the position it is from. */
    var image by mutableStateOf<ImageBitmap?>(null)
        private set
    var imageAt by mutableStateOf(-1L)
        private set

    private fun reset(newUrl: String) {
        url = newUrl
        synchronized(this) { frames.clear() }
        image = null; imageAt = -1; wanted = -1
    }

    /** The user is seeking to [positionMs]: show the nearest frame now, fetch the exact one. */
    fun request(url: String, positionMs: Long) {
        if (url != this.url) reset(url)
        val bucket = (positionMs / 5_000) * 5_000
        showNearest(bucket)
        if (imageAt != bucket) { wanted = bucket; next() }
    }

    /** Called every tick while playing: slowly fill the strip across the whole film. */
    fun prefetch(url: String, durationMs: Long, playingSmoothly: Boolean) {
        if (url != this.url) reset(url)
        if (broken || durationMs <= 0 || !playingSmoothly || busy) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPrefetch < (if (lowEnd) 4_000 else 1_500)) return
        lastPrefetch = now
        val step = (durationMs / 60).coerceAtLeast(10_000)
        val target = synchronized(this) {
            (0 until 60).asSequence().map { (it * step + step / 2).coerceAtMost(durationMs - 1_000) }
                .firstOrNull { p -> frames.floorKey(p + step / 2)?.let { it >= p - step / 2 } != true }
        } ?: return
        fetch(url, target)
    }

    private fun next() {
        val u = url ?: return
        val w = wanted
        if (w < 0 || busy || broken) return
        fetch(u, w)
    }

    private fun fetch(u: String, at: Long) {
        busy = true
        scope.launch {
            val bmp = withContext(dispatcher) { grab(u, at) }
            busy = false
            if (u != url) return@launch
            if (bmp != null) {
                synchronized(this@SeekThumbnailer) {
                    frames[at] = bmp
                    while (frames.size > maxFrames) {
                        // drop the frame farthest from where the user is looking
                        val focus = if (wanted >= 0) wanted else at
                        val far = frames.keys.maxByOrNull { kotlin.math.abs(it - focus) } ?: break
                        frames.remove(far)
                    }
                }
                if (wanted >= 0) showNearest(wanted)
            }
            if (wanted == at) wanted = -1 else next()
        }
    }

    private fun showNearest(pos: Long) {
        val (at, bmp) = synchronized(this) {
            val lo = frames.floorEntry(pos)
            val hi = frames.ceilingEntry(pos)
            val e = listOfNotNull(lo, hi).minByOrNull { kotlin.math.abs(it.key - pos) } ?: return
            e.key to e.value
        }
        if (at != imageAt) { image = bmp.asImageBitmap(); imageAt = at }
    }

    private fun grab(u: String, ms: Long): Bitmap? {
        if (broken && u == sourceUrl) return null
        return try {
            if (u != sourceUrl || retriever == null) {
                runCatching { retriever?.release() }
                sourceUrl = u
                broken = false
                retriever = MediaMetadataRetriever().apply { setDataSource(u, mapOf("User-Agent" to "DhakaFlixTV")) }
            }
            val r = retriever ?: return null
            val raw = if (Build.VERSION.SDK_INT >= 27) {
                r.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 256, 144)
            } else {
                r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { Bitmap.createScaledBitmap(it, 256, 144, true) }
            } ?: return null
            raw.copy(Bitmap.Config.RGB_565, false).also { if (it !== raw) raw.recycle() }
        } catch (e: Throwable) {
            broken = true
            null
        }
    }

    fun release() {
        worker.execute { runCatching { retriever?.release() }; retriever = null }
        worker.shutdown()
    }
}
