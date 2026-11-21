package com.israk.dhakaflix.data.accounts

import com.israk.dhakaflix.BuildConfig
import com.israk.dhakaflix.DhakaFlixApp
import com.israk.dhakaflix.data.DiagLog
import com.israk.dhakaflix.data.PlayItem
import com.israk.dhakaflix.data.db.ItemEntity
import com.israk.dhakaflix.data.db.ProgressEntity
import com.israk.dhakaflix.data.db.UserStateEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Simkl account: device-code (PIN) sign-in that works with a TV remote, and two-way sync of
 * watched status, watchlist and playback progress — the same account on every device keeps them in step.
 */
class Simkl(private val app: DhakaFlixApp) {
    data class Pin(val code: String, val url: String, val expiresAt: Long)

    private val settings = app.settings
    private val db = app.db
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    private val _pin = MutableStateFlow<Pin?>(null)
    val pin: StateFlow<Pin?> = _pin
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var loginJob: Job? = null
    private val lastScrobble = HashMap<String, Long>()

    val clientId: String get() = settings.simklClientId.value.ifBlank { BuildConfig.SIMKL_CLIENT_ID }
    val hasClient: Boolean get() = clientId.isNotBlank()
    val signedIn: Boolean get() = settings.simklToken.value.isNotBlank() && hasClient

    // ------------------------------------------------------------------ sign-in

    fun startLogin() {
        if (!hasClient) { _error.value = "No Simkl Client ID"; return }
        loginJob?.cancel()
        _error.value = null
        loginJob = app.appScope.launch(Dispatchers.IO) {
            val start = runCatching { getJson("/oauth/pin?client_id=$clientId", auth = false) }
                .getOrElse { _error.value = it.message; return@launch } as? JsonObject ?: return@launch
            val code = start.str("user_code") ?: run { _error.value = "Simkl: no code"; return@launch }
            val url = start.str("verification_url") ?: "https://simkl.com/pin"
            val expires = (start.int("expires_in") ?: 900) * 1000L
            val interval = ((start.int("interval") ?: 5).coerceAtLeast(3)) * 1000L
            _pin.value = Pin(code, url.trimEnd('/'), System.currentTimeMillis() + expires)
            while (isActive && System.currentTimeMillis() < (_pin.value?.expiresAt ?: 0)) {
                delay(interval)
                val r = runCatching { getJson("/oauth/pin/$code?client_id=$clientId", auth = false) as? JsonObject }.getOrNull() ?: continue
                val token = r.str("access_token")
                if (r.str("result") == "OK" && !token.isNullOrBlank()) {
                    settings.setSimklAccount(token, "")
                    val name = runCatching {
                        (post("/users/settings", JsonObject(emptyMap())) as? JsonObject)
                            ?.obj("user")?.str("name")
                    }.getOrNull() ?: "Simkl"
                    settings.setSimklAccount(token, name)
                    _pin.value = null
                    syncNow(firstTime = true)
                    return@launch
                }
            }
            _pin.value = null
        }
    }

    fun cancelLogin() { loginJob?.cancel(); _pin.value = null }

    fun signOut() {
        cancelLogin()
        settings.setSimklAccount("", "")
        settings.setSimklLastSync(0)
    }

    // ------------------------------------------------------------------ full sync

    /** Pull Simkl → this device, then push this device → Simkl. */
    fun syncNow(firstTime: Boolean = false) {
        if (!signedIn) return
        app.appScope.launch(Dispatchers.IO) {
            if (!mutex.tryLock()) return@launch
            _syncing.value = true
            _error.value = null
            try {
                pull()
                if (firstTime || settings.simklLastSync.value == 0L) pushEverything()
                settings.setSimklLastSync(System.currentTimeMillis())
            } catch (t: Throwable) {
                _error.value = t.message
                DiagLog.add("Simkl", t.message ?: t.toString())
            } finally {
                _syncing.value = false
                mutex.unlock()
            }
        }
    }

    /** Sync when the last one is older than [maxAgeMs] (called on app start). */
    fun syncIfStale(maxAgeMs: Long = 30 * 60_000L) {
        if (signedIn && System.currentTimeMillis() - settings.simklLastSync.value > maxAgeMs) syncNow()
    }

    private suspend fun pull() {
        for (type in listOf("movies", "shows", "anime")) {
            val root = runCatching { getJson("/sync/all-items/$type") }.getOrNull() as? JsonObject ?: continue
            val arr = root[type] as? JsonArray ?: continue
            val key = if (type == "movies") "movie" else if (type == "anime") "show" else "show"
            for (el in arr) {
                val o = el as? JsonObject ?: continue
                val media = o.obj(key) ?: o.obj("show") ?: continue
                val tmdb = media.obj("ids")?.tmdb() ?: continue
                val item = db.items().byTmdb(tmdb, if (type == "movies") "MOVIE" else "SERIES") ?: continue
                val u = db.user().get(item.id) ?: UserStateEntity(item.id)
                when (o.str("status")) {
                    "completed" -> if (!u.watched) {
                        db.user().put(u.copy(watched = true, watchedAt = parseTime(o.str("last_watched_at")) ?: System.currentTimeMillis()))
                        db.progress().completeItem(item.id)
                    }
                    "plantowatch" -> if (!u.inWatchlist) {
                        db.user().put(u.copy(inWatchlist = true, watchlistAt = parseTime(o.str("added_to_watchlist_at")) ?: System.currentTimeMillis()))
                    }
                }
            }
        }
        pullPlayback()
    }

    /** Paused-at positions from other devices become Continue Watching entries here. */
    private suspend fun pullPlayback() {
        val arr = runCatching { getJson("/sync/playback") }.getOrNull() as? JsonArray ?: return
        for (el in arr.take(40)) {
            val o = el as? JsonObject ?: continue
            val pct = o["progress"]?.jsonPrimitive?.doubleOrNull ?: continue
            val pausedAt = parseTime(o.str("paused_at")) ?: continue
            val isEpisode = o.str("type") == "episode"
            val media = (if (isEpisode) o.obj("show") else o.obj("movie")) ?: continue
            val tmdb = media.obj("ids")?.tmdb() ?: continue
            val item = db.items().byTmdb(tmdb, if (isEpisode) "SERIES" else "MOVIE") ?: continue
            val ep = o.obj("episode")
            val season = ep?.int("season")
            val number = ep?.int("number") ?: ep?.int("episode")
            val local = if (isEpisode && season != null && number != null) db.progress().episode(item.id, season, number)
            else if (!isEpisode) db.progress().latestFor(item.id) else null
            if (local != null && local.updatedAt >= pausedAt) continue
            val fileUrl = local?.fileUrl ?: findFile(item, season, number) ?: continue
            val dur = local?.durationMs?.takeIf { it > 0 } ?: ((item.runtime ?: if (isEpisode) 45 else 120) * 60_000L)
            db.progress().put(
                ProgressEntity(
                    fileUrl = fileUrl, itemId = item.id,
                    label = com.israk.dhakaflix.data.LibraryRepository.episodeLabel(season, number),
                    season = season, episode = number,
                    positionMs = (dur * pct / 100.0).toLong(), durationMs = dur,
                    updatedAt = pausedAt, completed = false,
                )
            )
        }
    }

    private suspend fun findFile(item: ItemEntity, season: Int?, episode: Int?): String? = runCatching {
        val repo = app.library
        val content = repo.listItem(item)
        if (!item.isSeries) return@runCatching content.videos.maxByOrNull { it.size }?.url
        content.videos.firstOrNull { it.season == season && it.episode == episode }?.url?.let { return@runCatching it }
        val ref = content.seasons.firstOrNull { it.number == season } ?: return@runCatching null
        repo.listSeason(ref).firstOrNull { it.episode == episode }?.url
    }.getOrNull()

    private suspend fun pushEverything() {
        val states = db.user().all()
        val items = db.items().getMany(states.map { it.itemId }).associateBy { it.id }
        val watched = states.filter { it.watched }.mapNotNull { items[it.itemId]?.takeIf { i -> i.tmdbId != null } }
        val wl = states.filter { it.inWatchlist && !it.watched }.mapNotNull { items[it.itemId]?.takeIf { i -> i.tmdbId != null } }
        if (watched.isNotEmpty()) post("/sync/history", historyBody(watched.map { it to null }))
        if (wl.isNotEmpty()) post("/sync/add-to-list", listBody(wl, "plantowatch"))
        // finished episodes
        val done = db.progress().all().filter { it.completed && it.season != null && it.episode != null }
        val shows = db.items().getMany(done.map { it.itemId }.distinct()).associateBy { it.id }
        val eps = done.mapNotNull { p -> shows[p.itemId]?.takeIf { it.tmdbId != null }?.let { it to (p.season!! to p.episode!!) } }
        if (eps.isNotEmpty()) post("/sync/history", historyBody(eps))
    }

    // ------------------------------------------------------------------ live pushes (fire & forget)

    fun onWatched(itemId: String, watched: Boolean) = push(itemId) { item ->
        if (watched) post("/sync/history", historyBody(listOf(item to null)))
        else post("/sync/history/remove", historyBody(listOf(item to null)))
    }

    fun onWatchlist(itemId: String, inList: Boolean) = push(itemId) { item ->
        if (inList) post("/sync/add-to-list", listBody(listOf(item), "plantowatch"))
        else {
            val u = db.user().get(itemId)
            if (u?.watched != true) post("/sync/history/remove", historyBody(listOf(item to null)))
        }
    }

    fun onEpisodeDone(itemId: String, season: Int, episode: Int) = push(itemId) { item ->
        post("/sync/history", historyBody(listOf(item to (season to episode))))
    }

    /** Playback position for other devices; throttled unless [force]. */
    fun onProgress(play: PlayItem, positionMs: Long, durationMs: Long, force: Boolean) {
        if (!signedIn || durationMs <= 0 || positionMs < 20_000) return
        val now = System.currentTimeMillis()
        if (!force && now - (lastScrobble[play.url] ?: 0L) < 120_000) return
        lastScrobble[play.url] = now
        val pct = (positionMs * 100.0 / durationMs).coerceIn(0.0, 99.0)
        push(play.itemId) { item ->
            val body = buildJsonObject {
                put("progress", pct)
                if (item.isSeries && play.season != null && play.episode != null) {
                    putJsonObject("show") { putJsonObject("ids") { put("tmdb", item.tmdbId) } }
                    putJsonObject("episode") { put("season", play.season); put("number", play.episode) }
                } else {
                    putJsonObject("movie") { putJsonObject("ids") { put("tmdb", item.tmdbId) } }
                }
            }
            post("/scrobble/pause", body)
        }
    }

    private fun push(itemId: String, block: suspend (ItemEntity) -> Unit) {
        if (!signedIn) return
        app.appScope.launch(Dispatchers.IO) {
            val item = db.items().get(itemId)?.takeIf { it.tmdbId != null } ?: return@launch
            runCatching { block(item) }.onFailure { DiagLog.add("Simkl", it.message ?: it.toString()) }
        }
    }

    private fun historyBody(list: List<Pair<ItemEntity, Pair<Int, Int>?>>): JsonObject = buildJsonObject {
        val now = isoNow()
        val movies = list.filter { !it.first.isSeries }
        val shows = list.filter { it.first.isSeries }.groupBy { it.first.tmdbId }
        if (movies.isNotEmpty()) putJsonArray("movies") {
            movies.forEach { (m, _) -> add(buildJsonObject { put("watched_at", now); putJsonObject("ids") { put("tmdb", m.tmdbId) } }) }
        }
        if (shows.isNotEmpty()) putJsonArray("shows") {
            shows.forEach { (tmdb, entries) ->
                add(buildJsonObject {
                    putJsonObject("ids") { put("tmdb", tmdb) }
                    val eps = entries.mapNotNull { it.second }
                    if (eps.isEmpty()) put("watched_at", now)
                    else putJsonArray("seasons") {
                        eps.groupBy { it.first }.forEach { (season, e) ->
                            add(buildJsonObject {
                                put("number", season)
                                putJsonArray("episodes") { e.forEach { add(buildJsonObject { put("number", it.second); put("watched_at", now) }) } }
                            })
                        }
                    }
                })
            }
        }
    }

    private fun listBody(items: List<ItemEntity>, to: String): JsonObject = buildJsonObject {
        val movies = items.filter { !it.isSeries }
        val shows = items.filter { it.isSeries }
        if (movies.isNotEmpty()) putJsonArray("movies") {
            movies.forEach { add(buildJsonObject { put("to", to); putJsonObject("ids") { put("tmdb", it.tmdbId) } }) }
        }
        if (shows.isNotEmpty()) putJsonArray("shows") {
            shows.forEach { add(buildJsonObject { put("to", to); putJsonObject("ids") { put("tmdb", it.tmdbId) } }) }
        }
    }

    // ------------------------------------------------------------------ http

    private fun url(path: String): String {
        val sep = if ('?' in path) "&" else "?"
        return "https://api.simkl.com$path${sep}app-name=dhakaflixtv&app-version=${BuildConfig.VERSION_NAME}" +
            if ("client_id=" in path) "" else "&client_id=$clientId"
    }

    private fun builder(path: String, auth: Boolean) = Request.Builder().url(url(path))
        .header("simkl-api-key", clientId)
        .header("User-Agent", "DhakaFlixTV/${BuildConfig.VERSION_NAME}")
        .apply { if (auth) header("Authorization", "Bearer ${settings.simklToken.value}") }

    private suspend fun getJson(path: String, auth: Boolean = true): JsonElement? = withContext(Dispatchers.IO) {
        app.http.newCall(builder(path, auth).build()).execute().use { r ->
            if (r.code == 401 && auth) { settings.setSimklAccount("", ""); throw java.io.IOException("Simkl sign-in expired") }
            if (!r.isSuccessful) throw java.io.IOException("Simkl HTTP ${r.code}")
            val b = r.body?.string().orEmpty()
            if (b.isBlank() || b == "null") null else json.parseToJsonElement(b)
        }
    }

    private suspend fun post(path: String, body: JsonObject): JsonElement? = withContext(Dispatchers.IO) {
        val req = builder(path, true).post(body.toString().toRequestBody("application/json".toMediaType())).build()
        app.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("Simkl $path HTTP ${r.code}")
            val b = r.body?.string().orEmpty()
            if (b.isBlank()) null else runCatching { json.parseToJsonElement(b) }.getOrNull()
        }
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(k: String): Int? = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
    private fun JsonObject.tmdb(): Int? = int("tmdb")

    private fun isoNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    private fun parseTime(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        for (p in listOf("yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ssX")) {
            runCatching {
                return SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)?.time
            }
        }
        return null
    }
}
