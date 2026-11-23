package com.israk.dhakaflix.data.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * RongDhong (http://172.31.255.238/) — a catalogue with a JSON API under /api, every title already
 * matched to TMDB. Used for the RongDhong library sources and their episodes / streams.
 */
class RongDhongClient(private val http: OkHttpClient, root: String) {
    val root = root.trimEnd('/') + "/"
    private val api = this.root + "api/"
    private val json = Json { ignoreUnknownKeys = true }

    data class Episode(val id: String, val name: String?, val season: Int, val episode: Int)

    fun get(path: String, params: Map<String, String> = emptyMap()): JsonElement {
        val url = (api + path).toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("RongDhong HTTP ${r.code}")
            json.parseToJsonElement(r.body?.string() ?: throw IOException("RongDhong: empty answer"))
        }
    }

    /** A cheap fingerprint of a whole catalogue ("movies" / "tv-shows"): count + newest change. */
    fun fingerprint(kind: String): String {
        val o = get(kind, mapOf("limit" to "1", "sort" to "updatedAt", "sort_order" to "DESC", "fields" to "id,updatedAt")) as? JsonObject
            ?: throw IOException("RongDhong: bad answer")
        val first = (o["data"] as? JsonArray)?.firstOrNull() as? JsonObject
        return "${o.str("total")}|${first?.str("id")}|${first?.str("updatedAt")}"
    }

    /**
     * Every title of a catalogue, page by page, handed to [onPage] as it arrives (keeps memory low on 1 GB
     * TV sticks). Throws if any page fails, so the caller can avoid replacing a library with half of it.
     */
    fun all(kind: String, onPage: (List<JsonObject>) -> Unit) {
        val fields = if (kind == "movies")
            "id,title,original_title,year,tmdb_id,poster_path,backdrop_path,genre,online_rating,overview,release_date,createdAt,updatedAt,file_path"
        else "id,title,original_title,year,tmdb_id,poster_path,backdrop_path,genre,online_rating,overview,release_date,createdAt,updatedAt,path"
        var page = 1
        while (true) {
            val o = get(kind, mapOf("limit" to "500", "page" to "$page", "fields" to fields)) as? JsonObject
                ?: throw IOException("RongDhong: bad page $page")
            onPage((o["data"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList())
            val pages = o.str("pages")?.toIntOrNull() ?: 1
            if (page >= pages || page > 200) break
            page++
        }
    }

    fun episodes(showId: String): List<Episode> {
        val o = get("tv-shows/$showId", mapOf("fields" to "episodes", "episode_fields" to "id,name,season_number,episode_number")) as? JsonObject
            ?: return emptyList()
        return (o["episodes"] as? JsonArray)?.mapNotNull { e ->
            val x = e as? JsonObject ?: return@mapNotNull null
            Episode(
                id = x.str("id") ?: return@mapNotNull null, name = x.str("name"),
                season = x.str("season_number")?.toIntOrNull() ?: 1, episode = x.str("episode_number")?.toIntOrNull() ?: 0,
            )
        }?.sortedWith(compareBy({ it.season }, { it.episode })) ?: emptyList()
    }

    fun movieStream(id: String) = api + "stream/video/stream?type=movies&id=$id&action=play"
    fun episodeStream(id: String) = api + "stream/video/stream?type=tv_shows&id=$id&action=play"
    fun posterUrl(isTv: Boolean, id: String) = api + "stream/image/${if (isTv) "tv" else "movie"}/$id/poster?size=342"

    /** Item ids look like "<root>api/movies/123" / "<root>api/tv-shows/45". */
    fun itemId(kind: String, id: String) = api + "$kind/$id"
    fun idOf(itemId: String): String = itemId.trimEnd('/').substringAfterLast('/').substringBefore('#')

    /** Season "folders" are virtual: "<show item id>#season=2". */
    fun seasonRef(itemId: String, season: Int) = "$itemId#season=$season"
    fun seasonOf(ref: String): Int? = ref.substringAfter("#season=", "").toIntOrNull()

    fun owns(url: String) = url.startsWith(root)

    companion object {
        fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    }
}
