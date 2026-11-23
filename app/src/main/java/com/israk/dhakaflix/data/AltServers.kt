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
    data class Alt(val server: String, val label: String, val url: String)

    interface Server {
        val name: String
        suspend fun movie(tmdb: Int?, title: String, year: Int?): List<Alt>
        suspend fun episode(tmdb: Int?, title: String, year: Int?, season: Int, episode: Int): List<Alt>
    }

    val servers: List<Server> = listOf(
        RongDhong("RongDhong", "http://172.31.255.238/"),
    )

    /** Every other copy of what [p] is, across all extra servers (in parallel, quietly skipping dead ones). */
    suspend fun find(meta: ItemEntity?, p: PlayItem): List<Alt> = withContext(Dispatchers.IO) {
        val title = meta?.displayTitle ?: p.title
        val year = meta?.displayYear
        val tmdb = meta?.tmdbId
        val isEpisode = (meta?.isSeries == true || p.season != null) && p.season != null && p.episode != null
        coroutineScope {
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
            val file = o.s("file_path")?.substringAfterLast('/') ?: ""
            AltServers.Alt(name, AltServers.qualityLabel(file), movieUrl(id))
        }.distinctBy { it.url }
    }

    override suspend fun episode(tmdb: Int?, title: String, year: Int?, season: Int, episode: Int): List<AltServers.Alt> {
        var shows = if (tmdb != null) get("tv-shows", mapOf("tmdb_id" to "$tmdb", "limit" to "10", "fields" to "id,title,year,tmdb_id")).rows()
            .filter { it.s("tmdb_id") == "$tmdb" } else emptyList()
        if (shows.isEmpty()) shows = get("search", mapOf("search" to title)).rows()
            .filter { it.s("type") != "movie" && titleMatches(it, title, year) }
        val out = mutableListOf<AltServers.Alt>()
        for (show in shows.take(4)) {
            val id = show.s("id") ?: continue
            val d = get("tv-shows/$id", mapOf("fields" to "episodes", "episode_fields" to "id,name,season_number,episode_number")) as? JsonObject ?: continue
            val ep = (d["episodes"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { it.i("season_number") == season && it.i("episode_number") == episode } ?: continue
            val epId = ep.s("id") ?: continue
            val lib = (d["Library"] as? JsonObject)?.s("name")
            out += AltServers.Alt(name, lib ?: "S%02dE%02d".format(season, episode), episodeUrl(epId))
        }
        return out.distinctBy { it.url }
    }
}
