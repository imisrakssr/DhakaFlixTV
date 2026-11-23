package com.israk.dhakaflix.data.tmdb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@Serializable
data class TmdbResult(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
) {
    val displayTitle get() = title ?: name ?: ""
    val year: Int? get() = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
}

@Serializable
data class TmdbPage(val results: List<TmdbResult> = emptyList())

@Serializable
data class TmdbVideo(val key: String = "", val site: String = "", val type: String = "", val official: Boolean = false,
                     @SerialName("iso_639_1") val lang: String? = null)

@Serializable
data class TmdbVideos(val results: List<TmdbVideo> = emptyList())

@Serializable
data class TmdbImage(@SerialName("file_path") val filePath: String, @SerialName("iso_639_1") val lang: String? = null,
                     @SerialName("vote_average") val vote: Double? = null)

@Serializable
data class TmdbTranslationData(val title: String? = null, val name: String? = null, val overview: String? = null)

@Serializable
data class TmdbTranslation(@SerialName("iso_639_1") val lang: String? = null, @SerialName("iso_3166_1") val country: String? = null,
                           val data: TmdbTranslationData? = null)

@Serializable
data class TmdbTranslations(val translations: List<TmdbTranslation> = emptyList())

@Serializable
data class TmdbImageSet(val logos: List<TmdbImage> = emptyList())

@Serializable
data class TmdbGenre(val id: Int, val name: String)

@Serializable
data class TmdbCast(
    val name: String,
    val character: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
data class TmdbCredits(val cast: List<TmdbCast> = emptyList())

@Serializable
data class TmdbDetails(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
    val genres: List<TmdbGenre> = emptyList(),
    val runtime: Int? = null,
    @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val credits: TmdbCredits? = null,
)

@Serializable
data class TmdbEpisode(
    @SerialName("episode_number") val episodeNumber: Int,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("still_path") val stillPath: String? = null,
    val runtime: Int? = null,
)

@Serializable
data class TmdbSeason(val episodes: List<TmdbEpisode> = emptyList())

object TmdbImages {
    fun url(path: String?, size: String): String? = when {
        path.isNullOrBlank() -> null
        path.startsWith("http") -> path
        else -> "https://image.tmdb.org/t/p/$size$path"
    }

    private val movieGenres = mapOf(
        28 to listOf("Action"), 12 to listOf("Adventure"), 16 to listOf("Animation"), 35 to listOf("Comedy"),
        80 to listOf("Crime"), 99 to listOf("Documentary"), 18 to listOf("Drama"), 10751 to listOf("Family"),
        14 to listOf("Fantasy"), 36 to listOf("History"), 27 to listOf("Horror"), 10402 to listOf("Music"),
        9648 to listOf("Mystery"), 10749 to listOf("Romance"), 878 to listOf("Science Fiction"),
        10770 to listOf("TV Movie"), 53 to listOf("Thriller"), 10752 to listOf("War"), 37 to listOf("Western"),
    )
    private val tvGenres = mapOf(
        10759 to listOf("Action", "Adventure"), 16 to listOf("Animation"), 35 to listOf("Comedy"),
        80 to listOf("Crime"), 99 to listOf("Documentary"), 18 to listOf("Drama"), 10751 to listOf("Family"),
        10762 to listOf("Kids"), 9648 to listOf("Mystery"), 10763 to listOf("News"), 10764 to listOf("Reality"),
        10765 to listOf("Science Fiction", "Fantasy"), 10766 to listOf("Soap"), 10767 to listOf("Talk"),
        10768 to listOf("War"), 37 to listOf("Western"),
    )

    fun genreNames(ids: List<Int>, tv: Boolean): List<String> =
        ids.flatMap { (if (tv) tvGenres[it] else movieGenres[it]) ?: emptyList() }.distinct()

    fun normaliseGenre(name: String): List<String> = when (name) {
        "Action & Adventure" -> listOf("Action", "Adventure")
        "Sci-Fi & Fantasy" -> listOf("Science Fiction", "Fantasy")
        "War & Politics" -> listOf("War")
        else -> listOf(name)
    }
}

class TmdbClient(private val http: OkHttpClient, private val key: () -> String) {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    private val castSer = ListSerializer(TmdbCast.serializer())

    val hasKey: Boolean get() = key().isNotBlank()

    suspend fun search(tv: Boolean, query: String, year: Int?): List<TmdbResult> {
        val params = mutableMapOf("query" to query, "include_adult" to "false")
        if (year != null) params[if (tv) "first_air_date_year" else "year"] = year.toString()
        return json.decodeFromString(TmdbPage.serializer(), get(if (tv) "search/tv" else "search/movie", params)).results
    }

    suspend fun details(tv: Boolean, id: Int): TmdbDetails =
        json.decodeFromString(TmdbDetails.serializer(), get(if (tv) "tv/$id" else "movie/$id", mapOf("append_to_response" to "credits")))

    suspend fun season(tvId: Int, season: Int, lang: String = "en-US"): TmdbSeason =
        json.decodeFromString(TmdbSeason.serializer(), get("tv/$tvId/season/$season", emptyMap(), lang))

    /** Bengali title / plot from TMDB's community translations (null when nobody translated it). */
    suspend fun bengali(tv: Boolean, id: Int): TmdbTranslationData? {
        val all = json.decodeFromString(TmdbTranslations.serializer(), get(if (tv) "tv/$id/translations" else "movie/$id/translations", emptyMap()))
        return all.translations.firstOrNull { it.lang == "bn" && it.country == "BD" }?.data
            ?: all.translations.firstOrNull { it.lang == "bn" }?.data
    }

    suspend fun recommendations(tv: Boolean, id: Int, page: Int = 1): List<TmdbResult> =
        json.decodeFromString(TmdbPage.serializer(), get(if (tv) "tv/$id/recommendations" else "movie/$id/recommendations", mapOf("page" to "$page"))).results

    suspend fun similar(tv: Boolean, id: Int): List<TmdbResult> =
        json.decodeFromString(TmdbPage.serializer(), get(if (tv) "tv/$id/similar" else "movie/$id/similar", emptyMap())).results

    suspend fun trending(page: Int): List<TmdbResult> =
        json.decodeFromString(TmdbPage.serializer(), get("trending/all/week", mapOf("page" to "$page"))).results

    suspend fun videos(tv: Boolean, id: Int): List<TmdbVideo> =
        json.decodeFromString(TmdbVideos.serializer(), get(if (tv) "tv/$id/videos" else "movie/$id/videos", emptyMap())).results

    suspend fun logos(tv: Boolean, id: Int): List<TmdbImage> =
        json.decodeFromString(TmdbImageSet.serializer(), get(if (tv) "tv/$id/images" else "movie/$id/images", mapOf("include_image_language" to "en,null"))).logos

    fun castToJson(cast: List<TmdbCast>): String = json.encodeToString(castSer, cast)
    fun castFromJson(s: String?): List<TmdbCast> =
        if (s.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(castSer, s) }.getOrDefault(emptyList())

    private suspend fun get(path: String, params: Map<String, String>, lang: String = "en-US"): String = withContext(Dispatchers.IO) {
        val k = key().trim()
        if (k.isEmpty()) throw IOException("TMDB key missing")
        val bearer = k.startsWith("eyJ")
        val url = "https://api.themoviedb.org/3/$path".toHttpUrl().newBuilder().apply {
            params.forEach { (a, b) -> addQueryParameter(a, b) }
            addQueryParameter("language", lang)
            if (!bearer) addQueryParameter("api_key", k)
        }.build()
        val req = Request.Builder().url(url).apply {
            if (bearer) header("Authorization", "Bearer $k")
            header("Accept", "application/json")
        }.build()
        http.newCall(req).execute().use { r ->
            if (r.code == 429) throw RateLimited()
            if (r.code == 401) throw Unauthorized()
            if (!r.isSuccessful) throw HttpError(r.code)
            r.body?.string().orEmpty()
        }
    }

    class RateLimited : IOException("TMDB rate limited")
    class Unauthorized : IOException("TMDB key rejected")
    class HttpError(val code: Int) : IOException("TMDB HTTP $code")

    enum class Status { NOT_SET, CHECKING, CONNECTED, INVALID, OFFLINE }

    /** Checks the key against /configuration. */
    suspend fun verify(): Status {
        if (!hasKey) return Status.NOT_SET
        return try {
            get("configuration", emptyMap()); Status.CONNECTED
        } catch (e: Unauthorized) {
            Status.INVALID
        } catch (e: Exception) {
            Status.OFFLINE
        }
    }
}
