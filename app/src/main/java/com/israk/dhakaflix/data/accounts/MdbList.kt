package com.israk.dhakaflix.data.accounts

import com.israk.dhakaflix.DhakaFlixApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/** One rating from MDBList, already formatted for display ("7.8", "91%"). */
data class ExtRating(val source: String, val label: String, val value: String)

/** IMDb / Rotten Tomatoes / Metacritic / Letterboxd / Trakt ratings via mdblist.com (user's own free key). */
class MdbList(private val app: DhakaFlixApp) {
    enum class Status { NOT_SET, CHECKING, CONNECTED, INVALID, OFFLINE }

    private val _status = MutableStateFlow(Status.NOT_SET)
    val status: StateFlow<Status> = _status
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = app.settings.store("mdblist_cache")
    private val mem = HashMap<String, List<ExtRating>>()

    val key: String get() = app.settings.mdblistKey.value
    val hasKey: Boolean get() = key.isNotBlank()

    fun verify() {
        if (!hasKey) { _status.value = Status.NOT_SET; return }
        _status.value = Status.CHECKING
        app.appScope.launch {
            _status.value = withContext(Dispatchers.IO) {
                runCatching {
                    app.http.newCall(Request.Builder().url("https://api.mdblist.com/user?apikey=$key").build()).execute().use { r ->
                        when {
                            r.isSuccessful -> Status.CONNECTED
                            r.code == 401 || r.code == 403 -> Status.INVALID
                            else -> Status.OFFLINE
                        }
                    }
                }.getOrElse { Status.OFFLINE }
            }
        }
    }

    /** Ratings for a TMDB id; cached for a week. Empty when no key / not found. */
    suspend fun ratings(tmdbId: Int, tv: Boolean): List<ExtRating> = withContext(Dispatchers.IO) {
        if (!hasKey) return@withContext emptyList()
        val ck = (if (tv) "show:" else "movie:") + tmdbId
        mem[ck]?.let { return@withContext it }
        val saved = cache.getString(ck, null)
        val savedAt = cache.getLong("$ck@", 0L)
        if (saved != null && System.currentTimeMillis() - savedAt < WEEK) {
            parse(saved).also { mem[ck] = it }.let { return@withContext it }
        }
        val body = fetch("https://api.mdblist.com/tmdb/${if (tv) "show" else "movie"}/$tmdbId?apikey=$key")
            ?: fetch("https://mdblist.com/api/?apikey=$key&tm=$tmdbId&m=${if (tv) "show" else "movie"}")
            ?: return@withContext saved?.let { parse(it) } ?: emptyList()
        cache.edit().putString(ck, body).putLong("$ck@", System.currentTimeMillis()).apply()
        parse(body).also { mem[ck] = it }
    }

    private fun fetch(url: String): String? = runCatching {
        app.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.code == 401 || r.code == 403) _status.value = Status.INVALID
            if (r.isSuccessful) r.body?.string() else null
        }
    }.getOrNull()

    private fun parse(body: String): List<ExtRating> = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val arr = root["ratings"] as? JsonArray ?: return emptyList()
        arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val src = o["source"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val v = o["value"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            if (v <= 0) return@mapNotNull null
            when (src) {
                "imdb" -> ExtRating(src, "IMDb", String.format(java.util.Locale.US, "%.1f", v))
                "tomatoes" -> ExtRating(src, "Rotten Tomatoes", "${v.toInt()}%")
                "popcorn", "tomatoesaudience" -> ExtRating(src, "Audience", "${v.toInt()}%")
                "metacritic" -> ExtRating(src, "Metacritic", "${v.toInt()}")
                "letterboxd" -> ExtRating(src, "Letterboxd", String.format(java.util.Locale.US, "%.1f", v))
                "trakt" -> ExtRating(src, "Trakt", "${v.toInt()}%")
                else -> null
            }
        }.distinctBy { it.source }
    }.getOrDefault(emptyList())

    private companion object { const val WEEK = 7L * 24 * 3600 * 1000 }
}
