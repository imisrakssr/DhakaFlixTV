package com.israk.dhakaflix.data

import android.net.Uri
import android.util.Log
import androidx.sqlite.db.SimpleSQLiteQuery
import com.israk.dhakaflix.BuildConfig
import com.israk.dhakaflix.DhakaFlixApp
import com.israk.dhakaflix.data.db.DirCacheEntity
import com.israk.dhakaflix.data.db.ItemEntity
import com.israk.dhakaflix.data.db.ProgressEntity
import com.israk.dhakaflix.data.db.UserStateEntity
import com.israk.dhakaflix.data.net.DirEntry
import com.israk.dhakaflix.data.net.H5aiClient
import com.israk.dhakaflix.data.tmdb.TmdbClient
import com.israk.dhakaflix.data.tmdb.TmdbEpisode
import com.israk.dhakaflix.data.tmdb.TmdbImages
import com.israk.dhakaflix.data.tmdb.TmdbResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.stateIn
import androidx.room.withTransaction
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

data class SyncState(
    val running: Boolean = false,
    val scannedFolders: Int = 0,
    val newItems: Int = 0,
    val currentSource: String = "",
    val failedSources: List<String> = emptyList(),
    val allFailed: Boolean = false,
)

data class VideoFile(
    val name: String,
    val url: String,
    val size: Long,
    val season: Int?,
    val episode: Int?,
    val subs: List<String>,
)

data class SeasonRef(val number: Int, val name: String, val url: String)

data class FolderContent(
    val videos: List<VideoFile>,
    val seasons: List<SeasonRef>,
    val subs: List<String>,
    val poster: String?,
    val backdrop: String?,
)

data class ContinueEntry(val progress: ProgressEntity, val item: ItemEntity)

data class BrowseFilter(
    val type: MediaType? = null,
    val query: String = "",
    val genre: String? = null,
    val sourceId: String? = null,
    val year: Int? = null,
    val sort: SortMode = SortMode.LATEST,
)

class LibraryRepository(private val app: DhakaFlixApp) {

    private val db = app.db
    private val settings = app.settings
    private val scope = app.appScope
    val h5ai = H5aiClient(app.http)
    val tmdb = TmdbClient(app.http) { settings.tmdbKey.value.ifBlank { BuildConfig.TMDB_API_KEY } }
    val mdblist = com.israk.dhakaflix.data.accounts.MdbList(app)
    val simkl = com.israk.dhakaflix.data.accounts.Simkl(app)
    val discovery = Discovery(app)

    /** Ids of followed series that got new episodes (for the poster badge). */
    val newEpisodeIds: StateFlow<Set<String>> = db.items().newEpisodes()
        .map { l -> l.map { it.id }.toSet() }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptySet())

    private val _sync = MutableStateFlow(SyncState())
    val sync: StateFlow<SyncState> = _sync
    private val _enriching = MutableStateFlow(false)
    val enriching: StateFlow<Boolean> = _enriching

    private val _tmdbStatus = MutableStateFlow(TmdbClient.Status.NOT_SET)
    val tmdbStatus: StateFlow<TmdbClient.Status> = _tmdbStatus

    private var syncJob: Job? = null
    private var enrichJob: Job? = null
    private var posterJob: Job? = null
    private val net = Semaphore(4)
    private val seasonCache = ConcurrentHashMap<String, Map<Int, TmdbEpisode>>()

    val itemCount: Flow<Int> get() = db.items().countFlow().throttleLatest(1500).distinctUntilChanged()
    val pendingMeta: Flow<Int> get() = db.items().pendingCount().throttleLatest(2000).distinctUntilChanged()

    // ------------------------------------------------------------------ sync

    /** Incremental sync when the library is older than [maxAgeMs]; cheap because unchanged folders are skipped. */
    fun syncIfStale(maxAgeMs: Long = TEN_MINUTES) {
        scope.launch(Dispatchers.IO) {
            val count = db.items().countFlow().first()
            val stale = System.currentTimeMillis() - settings.lastSync.value > maxAgeMs
            // also when another server's hidden index (BokaSoka) has never been built
            val otherMissing = settings.sources.value.any { it.enabled && Defaults.isOtherServer(it.id) && db.items().countForSource(it.id) == 0 }
            if (count == 0 || stale || otherMissing) startSync(force = false, manual = false) else { startEnrich(); startPosterScan() }
        }
    }

    fun verifyTmdb() {
        scope.launch(Dispatchers.IO) {
            _tmdbStatus.value = if (tmdb.hasKey) TmdbClient.Status.CHECKING else TmdbClient.Status.NOT_SET
            _tmdbStatus.value = tmdb.verify()
        }
    }

    /** [manual] = asked for in Settings / Home; automatic syncs leave the other servers (RongDhong) alone. */
    fun startSync(force: Boolean, manual: Boolean = true) {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch(Dispatchers.IO) {
            runSync(force, manual)
            startEnrich()
            if (!tmdb.hasKey) startPosterScan()
        }
    }

    private suspend fun runSync(force: Boolean, manual: Boolean = true) {
        val all = settings.sources.value
        val sources = all.filter { it.enabled }
        _sync.value = SyncState(running = true)
        db.items().deleteNotInSources(sources.map { it.id }.ifEmpty { listOf("__none__") })
        val cache = if (force) emptyMap() else db.dirs().all().associate { it.url to it.modified }
        val newItems = AtomicInteger(0)
        val folders = AtomicInteger(0)
        val failed = mutableListOf<String>()
        for (src in sources) {
            // other servers update only when you refresh by hand (or the very first time, to fill the library)
            if (!manual && Defaults.isOtherServer(src.id) && db.items().countForSource(src.id) > 0) continue
            _sync.update { it.copy(currentSource = src.name) }
            val srcCache = if (db.items().countForSource(src.id) == 0) emptyMap() else cache
            try {
                crawl(src, src.url, 0, srcCache, newItems, folders)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "source ${src.name} failed", e)
                failed += src.name
                DiagLog.add("Library", "${src.name}: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        db.items().recomputePrimary()
        db.items().hideOtherServers()
        if (failed.size < sources.size) settings.setLastSync(System.currentTimeMillis())
        _sync.value = SyncState(
            running = false,
            scannedFolders = folders.get(),
            newItems = newItems.get(),
            failedSources = failed,
            allFailed = sources.isNotEmpty() && failed.size == sources.size,
        )
    }

    private suspend fun crawl(
        src: Source, url: String, depth: Int, cache: Map<String, Long>,
        newItems: AtomicInteger, folders: AtomicInteger,
    ): Unit = coroutineScope {
        val entries = net.withPermit { h5ai.list(url) }
        _sync.update { it.copy(scannedFolders = folders.incrementAndGet()) }
        val now = System.currentTimeMillis()
        val leaves = ArrayList<ItemEntity>()
        val subdirs = ArrayList<DirEntry>()
        for (e in entries) {
            if (!e.isDir) continue
            if (Defaults.is3D(e.name)) continue // 3D copies are never used
            val p = NameParser.parseFolder(e.name, src.type)
            if (p != null) {
                leaves += ItemEntity(
                    id = e.url, type = src.type.name, sourceId = src.id, parentUrl = url,
                    rawName = e.name, title = p.title, year = p.year, quality = p.quality,
                    tags = p.tags.joinToString(","), uploadedAt = e.modified, firstSeenAt = now,
                    groupKey = e.url,
                ).let {
                    // other servers: a hidden index for the server picker, no TMDB lookups or poster scans
                    if (Defaults.isOtherServer(src.id)) it.copy(metaState = ItemEntity.META_HIDDEN, posterChecked = true) else it
                }
            } else if (depth < MAX_DEPTH && !isJunkFolder(e.name)) {
                subdirs += e
            }
        }
        val before = db.items().idsByParent(url).size
        db.items().upsertListing(url, leaves)
        if (leaves.size > before) {
            _sync.update { it.copy(newItems = newItems.addAndGet(leaves.size - before)) }
        }
        subdirs.map { d ->
            async {
                if (d.modified != 0L && cache[d.url] == d.modified) return@async
                try {
                    crawl(src, d.url, depth + 1, cache, newItems, folders)
                    db.dirs().put(DirCacheEntity(d.url, d.modified))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "crawl failed ${d.url}", e)
                }
            }
        }.awaitAll()
    }

    private fun isJunkFolder(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("sub") || n == "sample" || n == "samples" || n == "extras" || n.startsWith("featurette") || n.startsWith("season ")
    }

    // ------------------------------------------------------------ metadata

    fun startEnrich() {
        if (enrichJob?.isActive == true || !tmdb.hasKey) return
        enrichJob = scope.launch(Dispatchers.IO) {
            _enriching.value = true
            try { enrichLoop() } finally { _enriching.value = false }
            startPosterScan()
        }
    }

    /** Uses the poster image stored inside the server folder when TMDB has none. */
    fun startPosterScan() {
        if (posterJob?.isActive == true) return
        posterJob = scope.launch(Dispatchers.IO) {
            val anyMeta = !tmdb.hasKey
            while (true) {
                val batch = db.items().needingPoster(anyMeta, 40)
                if (batch.isEmpty()) break
                val found = coroutineScope {
                    batch.map { item ->
                        async {
                            net.withPermit {
                                val entries = runCatching { h5ai.list(item.id) }.getOrNull()
                                item.id to entries?.let { pickPoster(it).first }
                            }
                        }
                    }.awaitAll()
                }
                db.withTransaction { found.forEach { (id, poster) -> db.items().setPosterChecked(id, poster) } }
            }
        }
    }

    /** (poster, backdrop) image URLs found in a folder listing. */
    private fun pickPoster(entries: List<DirEntry>): Pair<String?, String?> {
        val images = entries.filter { !it.isDir && NameParser.ext(it.name) in NameParser.imageExt }
        fun has(vararg k: String) = images.firstOrNull { e -> k.any { e.name.contains(it, ignoreCase = true) } }
        val backdrop = has("_VL_", "backdrop", "fanart", "banner")
        val poster = has("_AL_", "poster", "folder", "cover")
            ?: images.firstOrNull { it != backdrop }
            ?: backdrop
        return poster?.url to backdrop?.url
    }

    /** Called after the user adds a TMDB key. */
    fun retryMetadata() {
        scope.launch(Dispatchers.IO) {
            db.items().retryNotFound()
            startEnrich()
        }
    }

    private sealed interface Outcome {
        class Match(val item: ItemEntity, val r: TmdbResult) : Outcome
        class Miss(val id: String) : Outcome
        object Skip : Outcome
        object Fail : Outcome
    }

    private suspend fun enrichLoop() {
        val sem = Semaphore(8)
        var batches = 0
        while (true) {
            val batch = db.items().pendingMeta(40)
            if (batch.isEmpty()) break
            val outcomes = coroutineScope {
                batch.map { item -> async { sem.withPermit { lookup(item) } } }.awaitAll()
            }
            if (outcomes.all { it is Outcome.Fail }) break // TMDB unreachable: stop, retry next launch
            // one transaction per batch = one UI refresh instead of 40 (keeps the TV UI smooth)
            db.withTransaction { outcomes.forEach { applyOutcome(it) } }
            if (++batches % 5 == 0) db.items().recomputePrimary()
        }
        db.items().recomputePrimary()
    }

    private suspend fun applyOutcome(o: Outcome) {
        when (o) {
            is Outcome.Match -> applyResult(o.item, o.r, ItemEntity.META_MATCHED)
            is Outcome.Miss -> db.items().setMetaState(o.id, ItemEntity.META_NOT_FOUND)
            else -> Unit
        }
    }

    /** @return false on network failure (item stays pending). */
    private suspend fun enrichOne(item: ItemEntity): Boolean {
        val o = lookup(item)
        applyOutcome(o)
        return o !is Outcome.Fail
    }

    private suspend fun lookup(item: ItemEntity): Outcome {
        val tv = item.isSeries
        val q = NameParser.searchQuery(item.title)
        return try {
            var results = tmdb.search(tv, q, item.year)
            if (results.isEmpty() && item.year != null) results = tmdb.search(tv, q, null)
            val best = pickBest(results, item.title, item.year)
            if (best == null) Outcome.Miss(item.id) else Outcome.Match(item, best)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TmdbClient.RateLimited) {
            delay(2500); Outcome.Skip
        } catch (e: kotlinx.serialization.SerializationException) {
            Outcome.Miss(item.id)
        } catch (e: TmdbClient.HttpError) {
            // a bad request for this title will never succeed: don't retry it forever
            if (e.code in 400..499) Outcome.Miss(item.id) else Outcome.Fail
        } catch (e: Exception) {
            Log.w(TAG, "tmdb failed for ${item.title}", e)
            Outcome.Fail
        }
    }

    private suspend fun applyResult(item: ItemEntity, r: TmdbResult, state: Int) {
        val genres = TmdbImages.genreNames(r.genreIds, item.isSeries)
        db.items().applyMeta(
            id = item.id, state = state, tmdbId = r.id, tmdbTitle = r.displayTitle.ifBlank { null },
            posterPath = r.posterPath, backdropPath = r.backdropPath, overview = r.overview?.ifBlank { null },
            rating = r.voteAverage, voteCount = r.voteCount,
            genres = if (genres.isEmpty()) null else genres.joinToString("|", "|", "|"),
            releaseYear = r.year, groupKey = "${item.type}:${r.id}",
        )
    }

    private fun pickBest(results: List<TmdbResult>, title: String, year: Int?): TmdbResult? {
        if (results.isEmpty()) return null
        val nt = NameParser.norm(NameParser.searchQuery(title))
        return results.take(8).withIndex().maxByOrNull { (i, r) ->
            var s = -i * 0.6
            val rt = NameParser.norm(r.displayTitle)
            if (rt == nt) s += 4.0 else if (rt.isNotEmpty() && (rt.contains(nt) || nt.contains(rt))) s += 1.5
            val ry = r.year
            if (year != null && ry != null) {
                s += when (abs(ry - year)) { 0 -> 3.0; 1 -> 1.5; else -> -2.0 }
            }
            if (r.posterPath != null) s += 0.5
            s
        }?.value
    }

    suspend fun ensureDetails(id: String): Unit = withContext(Dispatchers.IO) {
        var item = db.items().get(id) ?: return@withContext
        if (item.metaState == ItemEntity.META_PENDING && tmdb.hasKey) {
            enrichOne(item)
            item = db.items().get(id) ?: return@withContext
        }
        val tmdbId = item.tmdbId ?: return@withContext
        if (item.detailsFetched) return@withContext
        runCatching { tmdb.details(item.isSeries, tmdbId) }.onSuccess { d ->
            val genres = d.genres.flatMap { TmdbImages.normaliseGenre(it.name) }.distinct()
            db.items().applyDetails(
                id = id,
                runtime = d.runtime ?: d.episodeRunTime.firstOrNull(),
                castJson = tmdb.castToJson(d.credits?.cast?.take(16) ?: emptyList()),
                tagline = d.tagline?.ifBlank { null },
                genres = if (genres.isEmpty()) null else genres.joinToString("|", "|", "|"),
                overview = d.overview?.ifBlank { null },
                posterPath = d.posterPath, backdropPath = d.backdropPath,
            )
        }.onFailure { Log.w(TAG, "details failed", it) }
        Unit
    }

    suspend fun searchTmdb(tv: Boolean, query: String, year: Int?): List<TmdbResult> = withContext(Dispatchers.IO) {
        runCatching {
            val r = tmdb.search(tv, query, year)
            if (r.isEmpty() && year != null) tmdb.search(tv, query, null) else r
        }.getOrDefault(emptyList())
    }

    suspend fun applyManualMatch(itemId: String, r: TmdbResult): Unit = withContext(Dispatchers.IO) {
        val item = db.items().get(itemId) ?: return@withContext
        applyResult(item, r, ItemEntity.META_MANUAL)
        db.items().recomputePrimary()
        ensureDetails(itemId)
    }

    suspend fun tmdbSeason(tmdbId: Int, season: Int): Map<Int, TmdbEpisode> = withContext(Dispatchers.IO) {
        val bn = app.settings.language.value == "dhk"
        val k = "$tmdbId:$season" + if (bn) ":bn" else ""
        seasonCache[k]?.let { return@withContext it }
        val en = runCatching { tmdb.season(tmdbId, season).episodes.associateBy { it.episodeNumber } }.getOrDefault(emptyMap())
        val out = if (!bn || en.isEmpty()) en else {
            // Dhakaiya: Bengali episode plots where someone translated them, English otherwise
            val b = runCatching { tmdb.season(tmdbId, season, "bn-BD").episodes.associateBy { it.episodeNumber } }.getOrDefault(emptyMap())
            en.mapValues { (n, e) ->
                val be = b[n]
                fun bangla(s: String?) = s?.takeIf { it.isNotBlank() && it.any { ch -> ch in '\u0980'..'\u09FF' } }
                // episode names stay in English; only the plot is translated
                e.copy(overview = bangla(be?.overview) ?: e.overview)
            }
        }
        out.also { if (it.isNotEmpty()) seasonCache[k] = it }
    }

    // --------------------------------------------------------------- folders

    suspend fun listItem(item: ItemEntity): FolderContent = withContext(Dispatchers.IO) {
        val entries = h5ai.list(item.id)
        val files = entries.filter { !it.isDir }
        val dirs = entries.filter { it.isDir }
        val subs = files.filter { NameParser.ext(it.name) in NameParser.subExt }.map { it.url }.toMutableList()
        dirs.filter { it.name.lowercase().startsWith("sub") }.forEach { d ->
            runCatching { h5ai.list(d.url) }.getOrNull()
                ?.filter { !it.isDir && NameParser.ext(it.name) in NameParser.subExt }
                ?.forEach { subs += it.url }
        }
        val (poster, backdrop) = pickPoster(entries)
        if (poster != null && item.localPoster == null) db.items().setPosterChecked(item.id, poster)

        val seasons = if (item.isSeries) {
            dirs.filterNot { it.name.lowercase().startsWith("sub") }
                .mapIndexed { i, d -> SeasonRef(NameParser.seasonNumber(d.name) ?: (i + 1), d.name, d.url) }
                .sortedBy { it.number }
        } else emptyList()
        val videos = toVideos(files, subs, null)
        FolderContent(videos, seasons, subs, poster, backdrop)
    }

    suspend fun listSeason(season: SeasonRef): List<VideoFile> = withContext(Dispatchers.IO) {
        // RongDhong season (DhakaFlix was down and the series came from there)
        if (season.url.startsWith(Defaults.RD_ROOT)) return@withContext AltServers.rongDhongSeason(season.url)
        val entries = h5ai.list(season.url)
        val files = entries.filter { !it.isDir }
        val subs = files.filter { NameParser.ext(it.name) in NameParser.subExt }.map { it.url }.toMutableList()
        entries.filter { it.isDir && it.name.lowercase().startsWith("sub") }.forEach { d ->
            runCatching { h5ai.list(d.url) }.getOrNull()
                ?.filter { !it.isDir && NameParser.ext(it.name) in NameParser.subExt }
                ?.forEach { subs += it.url }
        }
        toVideos(files, subs, season.number)
    }

    private fun toVideos(files: List<DirEntry>, subs: List<String>, seasonNo: Int?): List<VideoFile> {
        val vids = files.filter { NameParser.ext(it.name) in NameParser.videoExt }
        val single = vids.size == 1
        return vids.map { f ->
            val ep = NameParser.episode(f.name)
            VideoFile(
                name = f.name, url = f.url, size = f.sizeBytes,
                season = ep.season ?: seasonNo, episode = ep.episode,
                subs = if (single) subs else matchSubs(f.name, ep, subs),
            )
        }.sortedWith(compareBy<VideoFile>({ it.season ?: 0 }, { it.episode ?: Int.MAX_VALUE }, { it.name.lowercase() }))
    }

    private fun matchSubs(video: String, ep: NameParser.EpisodeInfo, subs: List<String>): List<String> {
        val base = video.substringBeforeLast('.').lowercase()
        return subs.filter { s ->
            val sn = fileName(s).lowercase()
            sn.startsWith(base) || (ep.episode != null && NameParser.episode(sn).let {
                it.episode == ep.episode && (it.season == null || ep.season == null || it.season == ep.season)
            })
        }
    }

    fun playlistFor(item: ItemEntity, videos: List<VideoFile>): List<PlayItem> {
        val poster = TmdbImages.url(item.backdropPath, "w780") ?: TmdbImages.url(item.posterPath, "w342") ?: item.localPoster
        return videos.map { v ->
            PlayItem(
                url = v.url, itemId = item.id, title = item.displayTitle,
                subtitle = if (item.isSeries) episodeLabel(v.season, v.episode) ?: v.name
                else if (videos.size > 1) v.name else null,
                season = if (item.isSeries) v.season else null,
                episode = if (item.isSeries) v.episode else null,
                subs = v.subs, poster = poster,
            )
        }
    }

    /** Rebuilds a playlist (rest of the season) for a Continue Watching entry. */
    suspend fun playlistFromProgress(p: ProgressEntity): Pair<List<PlayItem>, Int> = withContext(Dispatchers.IO) {
        val item = db.items().get(p.itemId)
        if (item != null) {
            runCatching {
                val videos = if (item.isSeries) {
                    val parent = p.fileUrl.substringBeforeLast('/') + "/"
                    if (parent == item.id) listItem(item).videos
                    else listSeason(SeasonRef(p.season ?: 1, "", parent))
                } else listItem(item).videos
                val list = playlistFor(item, videos)
                val idx = list.indexOfFirst { it.url == p.fileUrl }
                if (idx >= 0) return@withContext list to idx
            }
        }
        listOf(
            PlayItem(
                url = p.fileUrl, itemId = p.itemId, title = item?.displayTitle ?: fileName(p.fileUrl),
                subtitle = p.label, season = p.season, episode = p.episode,
            )
        ) to 0
    }

    // ------------------------------------------------------------ user data

    suspend fun saveProgress(play: PlayItem, positionMs: Long, durationMs: Long, completed: Boolean): Unit =
        withContext(Dispatchers.IO) {
            db.progress().put(
                ProgressEntity(
                    fileUrl = play.url, itemId = play.itemId,
                    label = episodeLabel(play.season, play.episode) ?: play.subtitle,
                    season = play.season, episode = play.episode,
                    positionMs = positionMs, durationMs = durationMs,
                    updatedAt = System.currentTimeMillis(), completed = completed,
                )
            )
            val isMovie = play.season == null && play.episode == null && db.items().get(play.itemId)?.isSeries != true
            when {
                // a finished movie marks the title watched
                completed && isMovie -> { setWatched(play.itemId, true); simkl.onMovieDone(play.itemId) }
                completed && play.season != null && play.episode != null -> simkl.onEpisodeDone(play.itemId, play.season, play.episode)
                // an episode whose file name gave no number: never mark the whole series watched
                completed -> Unit
                else -> simkl.onProgress(play, positionMs, durationMs, force = false)
            }
            Unit
        }

    suspend fun savedPosition(url: String): ProgressEntity? = withContext(Dispatchers.IO) { db.progress().get(url) }

    suspend fun toggleWatchlist(id: String): Unit = withContext(Dispatchers.IO) {
        val u = db.user().get(id) ?: UserStateEntity(id)
        db.user().put(u.copy(inWatchlist = !u.inWatchlist, watchlistAt = System.currentTimeMillis()))
        simkl.onWatchlist(id, !u.inWatchlist)
    }

    suspend fun setWatched(id: String, watched: Boolean): Unit = withContext(Dispatchers.IO) {
        val u = db.user().get(id) ?: UserStateEntity(id)
        db.user().put(u.copy(watched = watched, watchedAt = System.currentTimeMillis()))
        if (watched) db.progress().completeItem(id)
        if (u.watched != watched) simkl.onWatched(id, watched)
        Unit
    }

    suspend fun setInWatchlist(id: String, inList: Boolean): Unit = withContext(Dispatchers.IO) {
        val u = db.user().get(id) ?: UserStateEntity(id)
        db.user().put(u.copy(inWatchlist = inList, watchlistAt = System.currentTimeMillis()))
        if (u.inWatchlist != inList) simkl.onWatchlist(id, inList)
    }

    /** "Remove from My Library": out of the watchlist, history and continue watching. */
    suspend fun removeFromLibrary(id: String): Unit = withContext(Dispatchers.IO) {
        val u = db.user().get(id) ?: UserStateEntity(id)
        db.user().put(u.copy(inWatchlist = false, watched = false))
        db.progress().completeItem(id)
        simkl.onContinueRemoved(id)
        // tell Simkl too, or the next sync puts it straight back
        if (u.watched) simkl.onWatched(id, false)
        if (u.inWatchlist) simkl.onWatchlist(id, false)
    }

    suspend fun removeFromContinue(itemId: String): Unit = withContext(Dispatchers.IO) {
        db.progress().completeItem(itemId)
        simkl.onContinueRemoved(itemId)
    }

    // ---------------------------------------------------------------- flows

    fun continueWatching(): Flow<List<ContinueEntry>> = db.progress().inProgress().map { list ->
        val latest = list.distinctBy { it.itemId }.take(20)
        val items = db.items().getMany(latest.map { it.itemId }).associateBy { it.id }
        latest.mapNotNull { p -> items[p.itemId]?.let { ContinueEntry(p, it) } }
    }.distinctUntilChanged()

    fun featured(): Flow<List<ItemEntity>> = db.items().featured(8).throttleLatest(ROW_THROTTLE).distinctUntilChanged()

    /** Episodes of the season after [season] for a series, so playback can run across seasons. */
    suspend fun nextSeasonPlaylist(itemId: String, season: Int): List<PlayItem> = withContext(Dispatchers.IO) {
        val item = db.items().get(itemId) ?: return@withContext emptyList()
        runCatching {
            val next = listItem(item).seasons.firstOrNull { it.number > season } ?: return@runCatching emptyList()
            playlistFor(item, listSeason(next))
        }.getOrDefault(emptyList())
    }

    fun rowFlow(key: String): Flow<List<ItemEntity>> = rawRowFlow(key).throttleLatest(ROW_THROTTLE).distinctUntilChanged()

    private fun rawRowFlow(key: String): Flow<List<ItemEntity>> {
        val dao = db.items()
        return when {
            key == RowKeys.LATEST -> dao.latest(null, ROW_LIMIT)
            key == RowKeys.LATEST_MOVIES -> dao.latest(MediaType.MOVIE.name, ROW_LIMIT)
            key == RowKeys.LATEST_SERIES -> dao.latest(MediaType.SERIES.name, ROW_LIMIT)
            key == RowKeys.WATCHLIST -> db.user().watchlist()
            key == RowKeys.TOP_RATED -> dao.topRated(ROW_LIMIT)
            key == RowKeys.NEW_EPISODES -> dao.newEpisodes()
            key == RowKeys.TOP10 -> discovery.top10
            key == RowKeys.BECAUSE -> discovery.because.map { it?.items ?: emptyList() }
            key.startsWith("genre:") -> dao.byGenre(key.removePrefix("genre:"), ROW_LIMIT)
            key.startsWith("src:") -> dao.latestInSource(key.removePrefix("src:"), ROW_LIMIT)
            else -> flowOf(emptyList())
        }
    }

    fun browseQuery(f: BrowseFilter): SimpleSQLiteQuery {
        val where = mutableListOf("isPrimary = 1")
        val args = mutableListOf<Any>()
        f.type?.let { where += "type = ?"; args += it.name }
        f.query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { w ->
            where += "(title LIKE ? OR tmdbTitle LIKE ? OR rawName LIKE ?)"
            repeat(3) { args += "%$w%" }
        }
        f.genre?.let { where += "genres LIKE ?"; args += "%|$it|%" }
        f.sourceId?.let { where += "sourceId = ?"; args += it }
        f.year?.let { where += "COALESCE(year, releaseYear) = ?"; args += it }
        val order = when (f.sort) {
            SortMode.LATEST -> "uploadedAt DESC"
            SortMode.YEAR -> "COALESCE(year, releaseYear, 0) DESC, uploadedAt DESC"
            SortMode.AZ -> "title COLLATE NOCASE ASC"
            SortMode.RATING -> "COALESCE(rating, 0) DESC, voteCount DESC"
        }
        return SimpleSQLiteQuery("SELECT * FROM items WHERE ${where.joinToString(" AND ")} ORDER BY $order", args.toTypedArray())
    }

    companion object {
        private const val TAG = "Library"
        private const val MAX_DEPTH = 4
        private const val ROW_LIMIT = 30
        private const val ROW_THROTTLE = 3000L
        const val TEN_MINUTES = 10L * 60 * 1000
        const val THIRTY_MINUTES = 30L * 60 * 1000

        fun fileName(url: String): String = Uri.decode(url.trimEnd('/').substringAfterLast('/'))

        fun episodeLabel(season: Int?, episode: Int?): String? =
            if (episode == null) null else String.format("S%02dE%02d", season ?: 1, episode)
    }
}

/** Emits the first value at once, then at most one value per [periodMs] (always the latest). */
fun <T> Flow<T>.throttleLatest(periodMs: Long): Flow<T> = conflate().transform {
    emit(it)
    delay(periodMs)
}
