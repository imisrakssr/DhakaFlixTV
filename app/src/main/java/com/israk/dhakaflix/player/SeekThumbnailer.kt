package com.israk.dhakaflix.player

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Best-effort seek preview frames. Grabs the nearest keyframe of the remote file on a single
 * background thread (Android's retriever prefers software decoders, so playback isn't disturbed).
 * If the device can't do it the preview simply doesn't appear.
 */
class SeekThumbnailer(private val scope: CoroutineScope) {
    private val worker = Executors.newSingleThreadExecutor()
    private val dispatcher = worker.asCoroutineDispatcher()
    private var retriever: MediaMetadataRetriever? = null
    private var sourceUrl: String? = null
    private var broken = false
    private val cache = LruCache<Long, Bitmap>(30)
    private var job: Job? = null

    /** Latest preview and the position (bucketed to 10 s) it shows. */
    var image by mutableStateOf<ImageBitmap?>(null)
        private set
    var imageAt by mutableStateOf(-1L)
        private set

    fun request(url: String, positionMs: Long) {
        val bucket = (positionMs / 10_000) * 10_000
        if (url != sourceUrl) { cache.evictAll(); image = null; imageAt = -1 }
        cache.get(bucket)?.let { image = it.asImageBitmap(); imageAt = bucket; return }
        job?.cancel()
        job = scope.launch {
            delay(220)
            val bmp = withContext(dispatcher) { grab(url, bucket) } ?: return@launch
            cache.put(bucket, bmp)
            image = bmp.asImageBitmap()
            imageAt = bucket
        }
    }

    private fun grab(url: String, ms: Long): Bitmap? {
        if (broken && url == sourceUrl) return null
        return try {
            if (url != sourceUrl || retriever == null) {
                runCatching { retriever?.release() }
                sourceUrl = url
                broken = false
                retriever = MediaMetadataRetriever().apply { setDataSource(url, mapOf("User-Agent" to "DhakaFlixTV")) }
            }
            val r = retriever ?: return null
            if (Build.VERSION.SDK_INT >= 27) {
                r.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
            } else {
                r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { Bitmap.createScaledBitmap(it, 320, 180, true) }
            }
        } catch (e: Throwable) {
            broken = true
            null
        }
    }

    fun release() {
        job?.cancel()
        worker.execute { runCatching { retriever?.release() }; retriever = null }
        worker.shutdown()
    }
}
