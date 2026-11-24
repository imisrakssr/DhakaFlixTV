package com.israk.dhakaflix.data

import kotlinx.serialization.Serializable

@Serializable
enum class MediaType { MOVIE, SERIES }

/** One browsable category root on a DhakaFlix (h5ai) server. */
@Serializable
data class Source(
    val id: String,
    val name: String,
    val url: String,
    val type: MediaType,
    val enabled: Boolean = true,
    val custom: Boolean = false,
)

@Serializable
data class HomeRow(val key: String, val enabled: Boolean = true)

/** A single playable file handed to the player. */
@Serializable
data class PlayItem(
    val url: String,
    val itemId: String,
    val title: String,
    val subtitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val subs: List<String> = emptyList(),
    val poster: String? = null,
)

object Defaults {
    private const val S7 = "http://172.16.50.7/DHAKA-FLIX-7/"
    private const val S10 = "http://172.16.50.10/DHAKA-FLIX-10/"
    private const val S12 = "http://172.16.50.12/DHAKA-FLIX-12/"
    private const val S14 = "http://172.16.50.14/DHAKA-FLIX-14/"

    /** RongDhong libraries (JSON API, not h5ai): one source per library, id "rd-<library id>". */
    const val RD_ROOT = "http://172.31.255.238/"
    val rongDhong: List<Source> = listOf(
        Triple(4, "English Movies", MediaType.MOVIE),
        Triple(3, "English Movies 1080p", MediaType.MOVIE),
        Triple(6, "Hindi Movies", MediaType.MOVIE),
        Triple(10, "South Indian Movies", MediaType.MOVIE),
        Triple(11, "Hindi Dubbed", MediaType.MOVIE),
        Triple(8, "Kolkata Bangla Movies", MediaType.MOVIE),
        Triple(5, "Foreign Language Movies", MediaType.MOVIE),
        Triple(2, "Animation Movies", MediaType.MOVIE),
        Triple(7, "IMDb Top-250", MediaType.MOVIE),
        Triple(9, "Korean TV & Web Series", MediaType.SERIES),
    ).map { (id, name, type) -> Source("rd-$id", "RongDhong · $name", "${RD_ROOT}api/library/$id", type, enabled = id != 7) }

    fun isRongDhong(id: String) = id.startsWith("rd-")

    /** BokaSoka (http://198.20.20.20/): an h5ai server like DhakaFlix, crawled the same way. */
    const val BK_ROOT = "http://198.20.20.20/"
    private const val BK = "${BK_ROOT}NAS1/"
    val bokaSoka: List<Source> = listOf(
        Source("bk-eng", "BokaSoka · English Movies", "${BK}Movies_Collection/Hollywood_Movies/", MediaType.MOVIE),
        Source("bk-hindi", "BokaSoka · Hindi Movies", "${BK}Bollywood_South_Indian_Movies/Bollywood_Movies/", MediaType.MOVIE),
        Source("bk-south", "BokaSoka · South Indian Movies", "${BK}Bollywood_South_Indian_Movies/South_Indian_Movies/Telegu_Tamil/", MediaType.MOVIE),
        Source("bk-malayalam", "BokaSoka · Malayalam Movies", "${BK}Bollywood_South_Indian_Movies/South_Indian_Movies/Malayalam_Movies/", MediaType.MOVIE),
        Source("bk-kolkata", "BokaSoka · Indian Bangla Movies", "${BK}Movies_Collection/Bangla_Collection/Indian_Bangla_Movies/", MediaType.MOVIE),
        Source("bk-bndub", "BokaSoka · Bangla Dubbed", "${BK}Movies_Collection/Bangla_Collection/Bangla_Dubbed/", MediaType.MOVIE),
        Source("bk-foreign", "BokaSoka · Other Country Movies", "${BK}Movies_Collection/Others_Language_Movies/", MediaType.MOVIE),
        Source("bk-anim", "BokaSoka · Animation Movies", "${BK}Movies_Collection/Animation_Movies/", MediaType.MOVIE),
        Source("bk-imdb", "BokaSoka · IMDb Top-250", "${BK}Movies_Collection/IMDb_Top-250%20Movies/", MediaType.MOVIE, enabled = false),
        Source("bk-engtv", "BokaSoka · English TV & Web Series", "${BK}NAS2/TV_Web_Series/English_Dual_Audio_Series/", MediaType.SERIES),
        Source("bk-hinditv", "BokaSoka · Hindi Web Series", "${BK}NAS2/TV_Web_Series/Hindi_Telegu_Tamil_Series/", MediaType.SERIES),
        Source("bk-bntv", "BokaSoka · Bangla Web Series", "${BK}NAS2/TV_Web_Series/Bangla_Web_Series/", MediaType.SERIES),
        Source("bk-animtv", "BokaSoka · Animation Series", "${BK}NAS2/TV_Web_Series/Animation_Series/", MediaType.SERIES),
        Source("bk-othertv", "BokaSoka · Other TV Shows", "${BK}NAS2/TV_Web_Series/Other_Web_Series/", MediaType.SERIES),
    )

    /**
     * Sources on servers other than DhakaFlix: merged into the one library (no separate categories in
     * Settings or filters), updated only on a manual refresh; DhakaFlix stays the preferred copy.
     */
    fun isOtherServer(id: String) = id.startsWith("rd-") || id.startsWith("bk-")

    /** 3D copies are never used (library 1 on RongDhong, "[3D]" in a file name). */
    fun is3D(path: String): Boolean {
        val n = path.lowercase()
        return "3d movies" in n || "[3d]" in n || Regex("(^|[^a-z0-9])3d([^a-z0-9]|$)").containsMatchIn(n.substringAfterLast('/'))
    }

    val sources: List<Source> = listOf(
        Source("eng", "English Movies", "${S7}English%20Movies/", MediaType.MOVIE),
        Source("eng1080", "English Movies 1080p", "${S14}English%20Movies%20%281080p%29/", MediaType.MOVIE),
        Source("hindi", "Hindi Movies", "${S14}Hindi%20Movies/", MediaType.MOVIE),
        Source("south", "South Indian Movies", "${S14}SOUTH%20INDIAN%20MOVIES/South%20Movies/", MediaType.MOVIE),
        Source("southdub", "South Hindi Dubbed", "${S14}SOUTH%20INDIAN%20MOVIES/Hindi%20Dubbed/", MediaType.MOVIE),
        Source("kolkata", "Kolkata Bangla Movies", "${S7}Kolkata%20Bangla%20Movies/", MediaType.MOVIE),
        Source("foreign", "Foreign Language Movies", "${S7}Foreign%20Language%20Movies/", MediaType.MOVIE),
        Source("anim", "Animation Movies", "${S14}Animation%20Movies/", MediaType.MOVIE),
        Source("anim1080", "Animation Movies 1080p", "${S14}Animation%20Movies%20%281080p%29/", MediaType.MOVIE),
        Source("imdb250", "IMDb Top-250", "${S14}IMDb%20Top-250%20Movies/", MediaType.MOVIE, enabled = false),
        Source("tvweb", "TV & WEB Series", "${S12}TV-WEB-Series/", MediaType.SERIES),
        Source("korean", "Korean TV & WEB Series", "${S14}KOREAN%20TV%20%26%20WEB%20Series/", MediaType.SERIES),
        Source("cartoon", "Anime & Cartoon Series", "${S10}Anime%20%26%20Cartoon%20TV%20Series/", MediaType.SERIES),
    ) + rongDhong + bokaSoka


    val homeRows: List<HomeRow> = listOf(
        HomeRow(RowKeys.CONTINUE),
        HomeRow(RowKeys.NEW_EPISODES),
        HomeRow(RowKeys.LATEST),
        HomeRow(RowKeys.TOP10),
        HomeRow(RowKeys.LATEST_MOVIES),
        HomeRow(RowKeys.LATEST_SERIES),
        HomeRow(RowKeys.WATCHLIST),
        HomeRow(RowKeys.BECAUSE),
        HomeRow(RowKeys.TOP_RATED),
        HomeRow(RowKeys.genre("Action")),
        HomeRow(RowKeys.genre("Comedy")),
        HomeRow(RowKeys.genre("Thriller")),
        HomeRow(RowKeys.genre("Science Fiction")),
        HomeRow(RowKeys.genre("Horror"), enabled = false),
        HomeRow(RowKeys.genre("Romance"), enabled = false),
        HomeRow(RowKeys.genre("Animation"), enabled = false),
        HomeRow(RowKeys.genre("Crime"), enabled = false),
        HomeRow(RowKeys.genre("Drama"), enabled = false),
        HomeRow(RowKeys.genre("Adventure"), enabled = false),
        HomeRow(RowKeys.genre("Documentary"), enabled = false),
    )
}

/**
 * Streaming-style categories (Hollywood, Bollywood, K-Drama …) instead of server folder names. A title is in
 * a category when any of its copies is in one of the category's folders ([sources]), or — for series — when
 * TMDB says its original language is one of [langs] ([loose] folders count only while the language is unknown).
 * [otherLangs]: every language not claimed by another category.
 */
data class Category(
    val key: String, val en: String, val dhk: String, val type: MediaType,
    val sources: Set<String> = emptySet(),
    val langs: Set<String> = emptySet(),
    val loose: Set<String> = emptySet(),
    val otherLangs: Boolean = false,
) {
    fun label(dhakaiya: Boolean) = if (dhakaiya) dhk else en
}

object Categories {
    private val indian = setOf("hi", "ta", "te", "ml", "kn", "mr", "pa", "gu", "ur")
    val all: List<Category> = listOf(
        Category("hollywood", "Hollywood", "হলিউড", MediaType.MOVIE,
            setOf("eng", "eng1080", "imdb250", "rd-4", "rd-3", "rd-7", "bk-eng", "bk-imdb")),
        Category("bollywood", "Bollywood", "বলিউড", MediaType.MOVIE, setOf("hindi", "rd-6", "bk-hindi")),
        Category("south", "South Indian", "সাউথ ইন্ডিয়ান", MediaType.MOVIE, setOf("south", "rd-10", "bk-south", "bk-malayalam")),
        Category("dubbed", "Hindi Dubbed", "হিন্দি ডাবড", MediaType.MOVIE, setOf("southdub", "rd-11")),
        Category("bangla", "Bangla", "বাংলা", MediaType.MOVIE, setOf("kolkata", "rd-8", "bk-kolkata", "bk-bndub")),
        Category("world", "World Cinema", "দুনিয়ার সিনেমা", MediaType.MOVIE, setOf("foreign", "rd-5", "bk-foreign")),
        Category("animation", "Animation", "অ্যানিমেশন", MediaType.MOVIE, setOf("anim", "anim1080", "rd-2", "bk-anim")),
        // series: by language (DhakaFlix's "TV & WEB Series" mixes every language in one folder)
        Category("english_tv", "English Series", "ইংলিশ সিরিজ", MediaType.SERIES,
            langs = setOf("en"), loose = setOf("tvweb", "bk-engtv", "bk-othertv")),
        Category("indian_tv", "Indian Web Series", "ইন্ডিয়ান ওয়েব সিরিজ", MediaType.SERIES, setOf("bk-hinditv"), langs = indian),
        Category("bangla_tv", "Bangla Web Series", "বাংলা ওয়েব সিরিজ", MediaType.SERIES, setOf("bk-bntv"), langs = setOf("bn")),
        Category("kdrama", "K-Drama", "কে-ড্রামা", MediaType.SERIES, setOf("korean", "rd-9"), langs = setOf("ko")),
        Category("anime", "Anime & Cartoons", "অ্যানিমে আর কার্টুন", MediaType.SERIES, setOf("cartoon", "bk-animtv"), langs = setOf("ja")),
        Category("intl_tv", "International Series", "আন্তর্জাতিক সিরিজ", MediaType.SERIES, otherLangs = true),
    )

    /**
     * Categories offered for [type] (null = both): built-in ones that have something to show, plus one per
     * enabled source of your own that isn't in any of them (named after the source).
     */
    fun available(type: MediaType?, sources: List<Source>): List<Category> {
        val on = sources.filter { it.enabled && (type == null || it.type == type) }
        val ids = on.map { it.id }.toSet()
        val hasSeries = on.any { it.type == MediaType.SERIES }
        val built = all.filter { c ->
            (type == null || c.type == type) &&
                ((c.sources + c.loose).any { it in ids } || (c.type == MediaType.SERIES && hasSeries && (c.langs.isNotEmpty() || c.otherLangs)))
        }
        val covered = all.flatMap { it.sources + it.loose }.toSet()
        val own = on.filter { it.id !in covered && it.id != "rd-1" }
            .map { Category("src:${it.id}", it.name, it.name, it.type, setOf(it.id)) }
        return built + own
    }

    fun find(key: String?, sources: List<Source>): Category? =
        key?.let { k -> all.firstOrNull { it.key == k } ?: sources.firstOrNull { "src:${it.id}" == k }?.let { Category(k, it.name, it.name, it.type, setOf(it.id)) } }

    /** SQL condition (on one copy's row) for being in [c], with its arguments. */
    fun condition(c: Category): Pair<String, List<Any>> {
        val parts = mutableListOf<String>()
        val args = mutableListOf<Any>()
        fun inList(col: String, vals: Collection<String>) = "$col IN (" + vals.joinToString(",") { "?" } + ")"
        if (c.sources.isNotEmpty()) { parts += inList("sourceId", c.sources); args += c.sources }
        if (c.langs.isNotEmpty()) { parts += inList("lang", c.langs); args += c.langs }
        if (c.loose.isNotEmpty()) { parts += "(" + inList("sourceId", c.loose) + " AND (lang IS NULL OR lang = ''))"; args += c.loose }
        if (c.otherLangs) {
            val claimed = all.filter { it.type == c.type }.flatMap { it.langs }.toSet()
            parts += "(lang IS NOT NULL AND lang != '' AND " + inList("lang", claimed).replace(" IN (", " NOT IN (") + ")"
            args += claimed
        }
        if (parts.isEmpty()) return "0" to emptyList()
        return "(" + parts.joinToString(" OR ") + ")" to args
    }

    /** "this card's title is in the category" (any of its copies), for queries on primary rows. */
    fun groupCondition(c: Category): Pair<String, List<Any>> {
        val (cond, args) = condition(c)
        return "groupKey IN (SELECT groupKey FROM items WHERE $cond)" to args
    }
}

object RowKeys {
    const val CONTINUE = "continue"
    const val LATEST = "latest"
    const val LATEST_MOVIES = "latest_movies"
    const val LATEST_SERIES = "latest_series"
    const val WATCHLIST = "watchlist"
    const val TOP_RATED = "top_rated"
    const val NEW_EPISODES = "new_episodes"
    const val TOP10 = "top10"
    const val BECAUSE = "because"
    fun genre(name: String) = "genre:$name"
    fun source(id: String) = "src:$id"
    fun category(key: String) = "cat:$key"
}

val ALL_GENRES = listOf(
    "Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama",
    "Family", "Fantasy", "History", "Horror", "Music", "Mystery", "Romance",
    "Science Fiction", "Thriller", "War", "Western", "Kids", "Reality",
)

enum class SortMode { LATEST, YEAR, AZ, RATING }
