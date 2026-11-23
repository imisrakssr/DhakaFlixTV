package com.israk.dhakaflix.data

import com.israk.dhakaflix.DhakaFlixApp
import com.israk.dhakaflix.data.db.ItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Other servers that can play the same movie / episode as the DhakaFlix file.
 * The player looks them up when a video starts and lists them under "Server", so you can switch
 * mid-film and carry on from the same spot. Matching is by TMDB id first, then title + year.
 */
object AltServers {

    /** One playable copy on another server. */
    data class Alt(val server: String, val label: String, val url: String, val is3D: Boolean = false)

    interface Server {
        val name: String
        suspend fun movie(tmdb: Int?, title: String, year: Int?): List<Alt>
        suspend fun episode(tmdb: Int?, title: String, year: Int?, season: Int, episode: Int): List<Alt>
    }

    private val rongDhong = RongDhong("RongDhong", Defaults.RD_ROOT)
    val servers: List<Server> = listOf(rongDhong)

    /**
     * DhakaFlix is down: a series' seasons and episodes from another server instead (RongDhong first, it is
     * matched by TMDB id; then BokaSoka's index). Returns the server name and its listing.
     */
    suspend fun seriesFallback(meta: ItemEntity): Pair<String, FolderContent>? = withContext(Dispatchers.IO) {
        runCatching { rongDhong.seriesContent(meta.tmdbId, meta.displayTitle, meta.displayYear) }.getOrNull()
            ?.takeIf { it.seasons.isNotEmpty() }?.let { return@withContext "RongDhong" to it }
        val app = DhakaFlixApp.instance
        val titles = listOfNotNull(meta.title, meta.tmdbTitle).map { it.lowercase().trim() }.distinct()
        for (v in app.db.items().otherServerMatches("SERIES", titles, meta.displayYear ?: meta.year)) {
            val c = runCatching { app.library.listItem(v) }.getOrNull() ?: continue
            if (c.seasons.isNotEmpty() || c.videos.isNotEmpty()) return@withContext serverOf(v.id) to c
        }
        null
    }

    /** Episodes of a RongDhong season ("<root>api/tv-shows/<id>#season=<n>", from [seriesFallback]). */
    suspend fun rongDhongSeason(url: String): List<VideoFile> = withContext(Dispatchers.IO) { rongDhong.season(url) }

    /** 1080p first, then 720p, then everything else. */
    fun qualityRank(text: String?): Int {
        val t = text?.lowercase() ?: return 2
        return when {
            "1080p" in t -> 0
            "720p" in t -> 1
            else -> 2
        }
    }

    /** Quality labels of copies seen so far (url → label), for naming the copy a film opened with. */
    val labels = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Which server a stream URL belongs to. */
    fun serverOf(url: String): String = when {
        url.startsWith(Defaults.RD_ROOT) -> "RongDhong"
        url.startsWith(Defaults.BK_ROOT) -> "BokaSoka"
        else -> "DhakaFlix"
    }

    /**
     * Every copy of what [p] is on the other servers (BokaSoka's index, RongDhong's search).
     * Other DhakaFlix copies are not offered: the picker is only for other servers.
     */
    suspend fun find(meta: ItemEntity?, p: PlayItem): List<Alt> = withContext(Dispatchers.IO) {
        val lib = runCatching { libraryCopies(meta, p) }.getOrDefault(emptyList()) +
            runCatching { bokaSokaYearProbe(meta, p) }.getOrDefault(emptyList())
        val ext = runCatching { searchServers(meta, p) }.getOrDefault(emptyList())
        (lib + ext).distinctBy { it.url }.filter { it.url != p.url && !it.is3D }
            .also { list -> list.forEach { labels[it.url] = it.label } }
    }

    /** BokaSoka: its hidden folder index, matched by title + year, then the file inside the folder. */
    private suspend fun libraryCopies(meta: ItemEntity?, p: PlayItem): List<Alt> {
        val app = DhakaFlixApp.instance
        val repo = app.library
        val type = if (meta?.isSeries == true || p.season != null) "SERIES" else "MOVIE"
        val titles = listOfNotNull(meta?.title, meta?.tmdbTitle, p.title).map { it.lowercase().trim() }.distinct()
        if (titles.isEmpty()) return emptyList()
        val names = app.settings.sources.value.associate { it.id to it.name.substringAfter(" · ") }
        val hits = app.db.items().otherServerMatches(type, titles, meta?.displayYear ?: meta?.year)
        return hits.mapNotNull { v ->
            runCatching {
                val content = repo.listItem(v)
                val url = if (type == "MOVIE" || p.season == null) {
                    (content.videos.maxByOrNull { it.size } ?: content.videos.firstOrNull())?.url
                } else {
                    content.videos.firstOrNull { it.season == p.season && it.episode == p.episode }?.url
                        ?: content.seasons.firstOrNull { it.number == p.season }
                            ?.let { s -> repo.listSeason(s).firstOrNull { it.episode == p.episode } }?.url
                } ?: return@runCatching null
                val label = listOfNotNull(names[v.sourceId], v.quality).joinToString(" · ").ifBlank { qualityLabel(v.rawName) }
                Alt(serverOf(url), label, url, is3D = Defaults.is3D(v.rawName))
            }.getOrNull()
        }
    }

    /**
     * BokaSoka English films sit in "<year>_Hollywood" folders (some in a "######" subfolder): look there
     * directly, so they are found even before the index is built.
     */
    private suspend fun bokaSokaYearProbe(meta: ItemEntity?, p: PlayItem): List<Alt> {
        if (meta?.isSeries == true || p.season != null) return emptyList()
        val year = meta?.displayYear ?: meta?.year ?: return emptyList()
        val want = listOfNotNull(meta?.title, meta?.tmdbTitle, p.title).map { norm(it) }.toSet()
        val h5ai = DhakaFlixApp.instance.library.h5ai
        val root = Defaults.BK_ROOT + "NAS1/Movies_Collection/Hollywood_Movies/${year}_Hollywood/"
        val top = runCatching { h5ai.list(root) }.getOrNull() ?: return emptyList()
        val dirs = top.filter { it.isDir } + top.filter { it.isDir && it.name.startsWith("#") }
            .flatMap { d -> runCatching { h5ai.list(d.url) }.getOrDefault(emptyList()).filter { it.isDir } }
        val out = mutableListOf<Alt>()
        for (d in dirs) {
            val parsed = NameParser.parseFolder(d.name, MediaType.MOVIE) ?: continue
            if (norm(parsed.title) !in want || Defaults.is3D(d.name)) continue
            val files = runCatching { h5ai.list(d.url) }.getOrDefault(emptyList())
                .filter { !it.isDir && NameParser.ext(it.name) in NameParser.videoExt }
            val f = files.maxByOrNull { it.sizeBytes } ?: continue
            out += Alt("BokaSoka", listOfNotNull("English Movies", parsed.quality).joinToString(" · "), f.url)
            if (out.size >= 3) break
        }
        return out
    }

    private suspend fun searchServers(meta: ItemEntity?, p: PlayItem): List<Alt> {
        val title = meta?.displayTitle ?: p.title
        val year = meta?.displayYear
        val tmdb = meta?.tmdbId
        val isEpisode = (meta?.isSeries == true || p.season != null) && p.season != null && p.episode != null
        return coroutineScope {
            servers.map { s ->
                async {
                    runCatching {
                        if (isEpisode) s.episode(tmdb, title, year, p.season!!, p.episode!!) else s.movie(tmdb, title, year)
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
    }

    internal fun norm(s: String): String =
        s.lowercase().replace("&", "and").replace(Regex("[^a-z0-9]+"), " ").trim()
            .removePrefix("the ").trim()

    /** "Avatar (2009) EXTENDED 1080p BluRay x265 …" → "1080p · Extended · x265". */
    internal fun qualityLabel(fileName: String): String {
        val n = fileName.lowercase()
        val parts = mutableListOf<String>()
        Regex("(2160p|4k|1080p|720p|480p)").find(n)?.let { parts += if (it.value == "4k") "2160p" else it.value }
        if ("3d" in Regex("[^a-z0-9]").split(n)) parts += "3D"
        if ("extended" in n) parts += "Extended"
        if ("imax" in n) parts += "IMAX"
        if ("dual audio" in n || "dual.audio" in n) parts += "Dual audio"
        if ("hindi" in n && "english" !in n) parts += "Hindi"
        if ("x265" in n || "hevc" in n) parts += "x265"
        return parts.distinct().joinToString(" · ").ifBlank { fileName.substringBeforeLast('.').take(40) }
    }
}

/** RongDhong (a "FTP-Content" catalogue with a JSON API under /api). */
private class RongDhong(override val name: String, base: String) : AltServers.Server {
    private val root = base.trimEnd('/') + "/"
    private val api = root + "api/"
    private val json = Json { ignoreUnknownKeys = true }
    private val http by lazy {
        DhakaFlixApp.instance.http.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    }

    private fun get(path: String, params: Map<String, String> = emptyMap()): JsonElement? {
        val url = (api + path).toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.string()?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
        }
    }

    private fun JsonObject.s(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    private fun JsonObject.i(k: String) = s(k)?.toIntOrNull()
    private fun JsonElement?.rows(): List<JsonObject> = when (this) {
        is JsonArray -> mapNotNull { it as? JsonObject }
        is JsonObject -> (this["data"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        else -> emptyList()
    }

    private fun titleMatches(o: JsonObject, title: String, year: Int?): Boolean {
        val want = AltServers.norm(title)
        val got = listOfNotNull(o.s("title"), o.s("original_title")).map { AltServers.norm(it.replace('-', ' ')) }
        if (got.none { it == want }) return false
        val y = o.i("year")?.takeIf { it > 0 } ?: return true
        return year == null || kotlin.math.abs(y - year) <= 1
    }

    private fun movieUrl(id: String) = api + "stream/video/stream?type=movies&id=$id&action=play"
    private fun episodeUrl(id: String) = api + "stream/video/stream?type=tv_shows&id=$id&action=play"

    override suspend fun movie(tmdb: Int?, title: String, year: Int?): List<AltServers.Alt> {
        val fields = "id,title,original_title,year,tmdb_id,file_path"
        var rows = if (tmdb != null) get("movies", mapOf("tmdb_id" to "$tmdb", "limit" to "10", "fields" to fields)).rows()
            .filter { it.s("tmdb_id") == "$tmdb" } else emptyList()
        if (rows.isEmpty()) rows = get("search", mapOf("search" to title)).rows()
            .filter { (it.s("type") ?: "movie") == "movie" && titleMatches(it, title, year) }
        return rows.mapNotNull { o ->
            val id = o.s("id") ?: return@mapNotNull null
            val path = o.s("file_path") ?: ""
            AltServers.Alt(name, AltServers.qualityLabel(path.substringAfterLast('/')), movieUrl(id), is3D = Defaults.is3D(path))
        }.distinctBy { it.url }
    }

    private val episodeCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<JsonObject>>>()

    private fun episodesOf(showId: String): List<JsonObject> {
        episodeCache[showId]?.let { (t, l) -> if (System.currentTimeMillis() - t < 120_000) return l }
        val d = get("tv-shows/$showId", mapOf("fields" to "episodes", "episode_fields" to "id,name,season_number,episode_number")) as? JsonObject
        val l = (d?.get("episodes") as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        episodeCache[showId] = System.currentTimeMillis() to l
        return l
    }

    private fun findShows(tmdb: Int?, title: String, year: Int?): List<JsonObject> {
        var shows = if (tmdb != null) get("tv-shows", mapOf("tmdb_id" to "$tmdb", "limit" to "10", "fields" to "id,title,year,tmdb_id")).rows()
            .filter { it.s("tmdb_id") == "$tmdb" } else emptyList()
        if (shows.isEmpty()) shows = get("search", mapOf("search" to title)).rows()
            .filter { it.s("type") != "movie" && titleMatches(it, title, year) }
        return shows
    }

    /** Seasons of the show as virtual folders "<api>tv-shows/<id>#season=<n>". */
    fun seriesContent(tmdb: Int?, title: String, year: Int?): FolderContent? {
        for (show in findShows(tmdb, title, year).take(3)) {
            val id = show.s("id") ?: continue
            val seasons = episodesOf(id).mapNotNull { it.i("season_number") }.distinct().sorted()
            if (seasons.isEmpty()) continue
            return FolderContent(emptyList(), seasons.map { SeasonRef(it, "Season $it", api + "tv-shows/$id#season=$it") }, emptyList(), null, null)
        }
        return null
    }

    fun season(url: String): List<VideoFile> {
        val n = url.substringAfter("#season=", "").toIntOrNull() ?: return emptyList()
        val id = url.substringBefore('#').trimEnd('/').substringAfterLast('/')
        return episodesOf(id).filter { it.i("season_number") == n }.mapNotNull { e ->
            val epId = e.s("id") ?: return@mapNotNull null
            val ep = e.i("episode_number")
            val label = "S%02dE%02d".format(n, ep ?: 0)
            VideoFile(e.s("name")?.takeIf { it.isNotBlank() }?.let { "$label · $it" } ?: label, episodeUrl(epId), 0, n, ep?.takeIf { it > 0 }, emptyList())
        }.sortedBy { it.episode ?: 0 }
    }

    override suspend fun episode(tmdb: Int?, title: String, year: Int?, season: Int, episode: Int): List<AltServers.Alt> {
        val out = mutableListOf<AltServers.Alt>()
        for (show in findShows(tmdb, title, year).take(4)) {
            val id = show.s("id") ?: continue
            val ep = episodesOf(id).firstOrNull { it.i("season_number") == season && it.i("episode_number") == episode } ?: continue
            val epId = ep.s("id") ?: continue
            out += AltServers.Alt(name, "S%02dE%02d".format(season, episode), episodeUrl(epId))
        }
        return out.distinctBy { it.url }
    }
}
