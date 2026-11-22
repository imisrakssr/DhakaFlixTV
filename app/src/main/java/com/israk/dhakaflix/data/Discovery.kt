package com.israk.dhakaflix.data

import com.israk.dhakaflix.DhakaFlixApp
import com.israk.dhakaflix.data.db.ItemEntity
import com.israk.dhakaflix.data.tmdb.TmdbImages
import com.israk.dhakaflix.data.tmdb.TmdbResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * TMDB-powered extras that only ever show titles that are actually on the server:
 * "Top 10 this week", "Because you watched …", title logos and trailers.
 */
class Discovery(private val app: DhakaFlixApp) {
    data class Because(val seedTitle: String, val items: List<ItemEntity>)

    private val db get() = app.db
    private val tmdb get() = app.library.tmdb
    private val prefs = app.settings.store("discovery_cache")
    private val mutex = Mutex()

    private val _top10 = MutableStateFlow<List<ItemEntity>>(emptyList())
    val top10: StateFlow<List<ItemEntity>> = _top10
    private val _because = MutableStateFlow<Because?>(null)
    val because: StateFlow<Because?> = _because

    private var lastRefresh = 0L
    private val logoMem = HashMap<String, String?>()
    private val trailerMem = HashMap<String, String?>()

    /** Recompute both rows (cheap: a handful of TMDB calls). */
    fun refresh(force: Boolean = false) {
        if (!tmdb.hasKey) return
        val now = System.currentTimeMillis()
        if (!force && now - lastRefresh < 3 * 3600_000L && _top10.value.isNotEmpty()) return
        lastRefresh = now
        app.appScope.launch(Dispatchers.IO) {
            mutex.withLock {
                runCatching { _top10.value = computeTop10() }.onFailure { DiagLog.add("Discovery", "Top 10: ${it.message}") }
                runCatching { _because.value = computeBecause() }.onFailure { DiagLog.add("Discovery", "Because: ${it.message}") }
            }
        }
    }

    /** "Because you watched" follows what you just finished: refresh it after playback. */
    fun refreshBecause() {
        if (!tmdb.hasKey) return
        app.appScope.launch(Dispatchers.IO) {
            mutex.withLock { runCatching { _because.value = computeBecause() } }
        }
    }

    private suspend fun local(r: TmdbResult, fallbackTv: Boolean? = null): ItemEntity? {
        val tv = when (r.mediaType) { "tv" -> true; "movie" -> false; else -> fallbackTv ?: return null }
        return db.items().byTmdb(r.id, if (tv) "SERIES" else "MOVIE")
    }

    private suspend fun computeTop10(): List<ItemEntity> {
        val out = LinkedHashMap<String, ItemEntity>()
        for (page in 1..5) {
            val results = tmdb.trending(page)
            for (r in results) {
                val item = local(r) ?: continue
                out.putIfAbsent(item.id, item)
                if (out.size >= 10) return out.values.toList()
            }
            if (results.isEmpty()) break
        }
        return out.values.toList()
    }

    private suspend fun computeBecause(): Because? {
        val recent = db.progress().inProgress().first().map { it.itemId } +
            db.user().watched().first().map { it.id }
        val seeds = db.items().getMany(recent.distinct().take(6)).filter { it.tmdbId != null }
            .sortedBy { recent.indexOf(it.id) }.take(3)
        val watched = db.user().watchedIds().first().toHashSet()
        for (seed in seeds) {
            val tv = seed.isSeries
            val id = seed.tmdbId ?: continue
            val cand = runCatching { tmdb.recommendations(tv, id) + tmdb.recommendations(tv, id, 2) + tmdb.similar(tv, id) }.getOrDefault(emptyList())
            val items = LinkedHashMap<String, ItemEntity>()
            for (r in cand) {
                val it = local(r, tv) ?: continue
                if (it.id == seed.id || it.id in watched) continue
                items.putIfAbsent(it.id, it)
                if (items.size >= 20) break
            }
            if (items.size >= 4) return Because(seed.displayTitle, items.values.toList())
        }
        return null
    }

    /** Transparent title-logo image (TMDB), or null when there's none. Cached on disk. */
    /** Bengali title and plot for Dhakaiya mode (null parts = no translation; callers fall back to English). */
    data class Bangla(val title: String?, val overview: String?)
    private val banglaMem = HashMap<String, Bangla>()

    suspend fun bangla(item: ItemEntity): Bangla = withContext(Dispatchers.IO) {
        val none = Bangla(null, null)
        val id = item.tmdbId ?: return@withContext none
        val k = (if (item.isSeries) "tv:" else "mv:") + id
        banglaMem[k]?.let { return@withContext it }
        prefs.getString("bn:$k", null)?.let { s ->
            val i = s.indexOf('\u0001')
            return@withContext Bangla(s.substring(0, i).ifBlank { null }, s.substring(i + 1).ifBlank { null }).also { banglaMem[k] = it }
        }
        if (!tmdb.hasKey) return@withContext none
        val d = runCatching { tmdb.bengali(item.isSeries, id) }.getOrElse { return@withContext none }
        val b = Bangla((d?.title ?: d?.name)?.trim()?.ifBlank { null }, d?.overview?.trim()?.ifBlank { null })
        prefs.edit().putString("bn:$k", (b.title ?: "") + "\u0001" + (b.overview ?: "")).apply()
        banglaMem[k] = b
        b
    }

    /** Dhakaiya mode: a Bengali logo if TMDB has one; otherwise null when a Bengali title exists (shown as
     *  text), else the usual English logo. */
    suspend fun logoUrlBangla(item: ItemEntity): String? = withContext(Dispatchers.IO) {
        val id = item.tmdbId ?: return@withContext null
        val k = "bn" + (if (item.isSeries) "tv:" else "mv:") + id
        if (logoMem.containsKey(k)) return@withContext logoMem[k]
        if (prefs.contains("logo:$k")) return@withContext prefs.getString("logo:$k", null)?.ifBlank { null }.also { logoMem[k] = it }
        if (!tmdb.hasKey) return@withContext null
        val bnLogo = runCatching { tmdb.logos(item.isSeries, id, "bn").filter { it.lang == "bn" }.maxByOrNull { it.vote ?: 0.0 }?.filePath }
            .getOrElse { return@withContext null }
        val url = when {
            bnLogo != null -> TmdbImages.url(bnLogo, "w500")
            bangla(item).title != null -> null
            else -> logoUrl(item)
        }
        prefs.edit().putString("logo:$k", url ?: "").apply()
        logoMem[k] = url
        url
    }

    suspend fun logoUrl(item: ItemEntity): String? = withContext(Dispatchers.IO) {
        val id = item.tmdbId ?: return@withContext null
        val k = (if (item.isSeries) "tv:" else "mv:") + id
        if (logoMem.containsKey(k)) return@withContext logoMem[k]
        if (prefs.contains("logo:$k")) return@withContext prefs.getString("logo:$k", null)?.ifBlank { null }.also { logoMem[k] = it }
        if (!tmdb.hasKey) return@withContext null
        val path = runCatching {
            val logos = tmdb.logos(item.isSeries, id)
            (logos.filter { it.lang == "en" }.ifEmpty { logos }).maxByOrNull { it.vote ?: 0.0 }?.filePath
        }.getOrElse { return@withContext null }
        val url = TmdbImages.url(path, "w500")
        prefs.edit().putString("logo:$k", url ?: "").apply()
        logoMem[k] = url
        url
    }

    /** YouTube key of the best trailer, or null. */
    suspend fun trailerKey(item: ItemEntity): String? = withContext(Dispatchers.IO) {
        val id = item.tmdbId ?: return@withContext null
        val k = (if (item.isSeries) "tv:" else "mv:") + id
        if (trailerMem.containsKey(k)) return@withContext trailerMem[k]
        if (!tmdb.hasKey) return@withContext null
        val v = runCatching { tmdb.videos(item.isSeries, id) }.getOrElse { return@withContext null }
            .filter { it.site == "YouTube" && it.key.isNotBlank() }
        val best = v.sortedWith(compareByDescending<com.israk.dhakaflix.data.tmdb.TmdbVideo> { it.type == "Trailer" }
            .thenByDescending { it.official }.thenByDescending { it.lang == "en" }).firstOrNull()?.key
        trailerMem[k] = best
        best
    }
}
