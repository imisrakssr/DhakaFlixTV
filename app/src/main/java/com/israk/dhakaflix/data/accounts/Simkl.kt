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
    data class Pin(val code: String, val url: String, val expiresAt: Long, val link: String)

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
            // New Simkl apps are AUTH V2 (device flow); older ones only know the V1 PIN flow.
            val v2 = runCatching { form("/oauth2/device", mapOf("client_id" to clientId, "scope" to "media:read media:write")) }
            val start = v2.getOrNull()
            if (start != null && start.first in 200..299) loginV2(start.second ?: return@launch)
            else if (start != null && start.first == 401) loginV1()
            else _error.value = "Simkl: " + (start?.second?.str("error_description") ?: start?.second?.str("error")
                ?: v2.exceptionOrNull()?.message ?: "HTTP ${start?.first}")
        }
    }

    private suspend fun loginV2(start: JsonObject) {
        val device = start.str("device_code") ?: run { _error.value = "Simkl: no device code"; return }
        val code = start.str("user_code") ?: return
        val base = (start.str("verification_uri") ?: "https://simkl.com/pin").trimEnd('/')
        val link = start.str("verification_uri_complete") ?: "$base?user_code=$code"
        var interval = ((start.int("interval") ?: 5).coerceAtLeast(3)) * 1000L
        _pin.value = Pin(code, base, System.currentTimeMillis() + (start.int("expires_in") ?: 900) * 1000L, link)
        while (System.currentTimeMillis() < (_pin.value?.expiresAt ?: 0)) {
            delay(interval)
            val r = runCatching {
                form("/oauth2/token", mapOf(
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    "client_id" to clientId, "device_code" to device,
                ))
            }.getOrNull() ?: continue
            val body = r.second
            val token = body?.str("access_token")
            when {
                r.first in 200..299 && !token.isNullOrBlank() -> {
                    saveTokens(body)
                    finishLogin(token)
                    return
                }
                body?.str("error") == "slow_down" -> interval += 5000
                body?.str("error") == "expired_token" -> break
                r.first == 401 -> { _error.value = "Simkl: client ID rejected"; break }
            }
        }
        _pin.value = null
    }

    private suspend fun loginV1() {
        val start = runCatching { getJson("/oauth/pin?client_id=$clientId", auth = false) }
            .getOrElse { _error.value = it.message; return } as? JsonObject ?: return
        val code = start.str("user_code") ?: run { _error.value = "Simkl: no code"; return }
        val url = (start.str("verification_uri") ?: start.str("verification_url") ?: "https://simkl.com/pin").trimEnd('/')
        val interval = ((start.int("interval") ?: 5).coerceAtLeast(3)) * 1000L
        _pin.value = Pin(code, url, System.currentTimeMillis() + (start.int("expires_in") ?: 900) * 1000L, "$url/$code")
        while (System.currentTimeMillis() < (_pin.value?.expiresAt ?: 0)) {
            delay(interval)
            val r = runCatching { getJson("/oauth/pin/$code?client_id=$clientId", auth = false) as? JsonObject }.getOrNull() ?: continue
            val token = r.str("access_token")
            if (r.str("result") == "OK" && !token.isNullOrBlank()) { finishLogin(token); return }
        }
        _pin.value = null
    }

    private suspend fun finishLogin(token: String) {
        settings.setSimklAccount(token, "")
        val name = runCatching {
            (post("/users/settings", JsonObject(emptyMap())) as? JsonObject)?.obj("user")?.str("name")
        }.getOrNull() ?: "Simkl"
        settings.setSimklAccount(token, name)
        _pin.value = null
        syncNow(firstTime = true)
    }

    private fun saveTokens(body: JsonObject) {
        val exp = (body.int("expires_in") ?: 7 * 24 * 3600) * 1000L
        settings.setSimklRefresh(body.str("refresh_token") ?: settings.simklRefresh, System.currentTimeMillis() + exp)
        body.str("access_token")?.let { settings.setSimklAccount(it, settings.simklUser.value) }
    }

    /** V2 access tokens last 7 days: renew with the refresh token before / when they run out. */
    private suspend fun refresh(): Boolean {
        val rt = settings.simklRefresh.takeIf { it.isNotBlank() } ?: return false
        val r = runCatching { form("/oauth2/token", mapOf("grant_type" to "refresh_token", "client_id" to clientId, "refresh_token" to rt)) }.getOrNull()
        val body = r?.second
        if (r == null || r.first !in 200..299 || body?.str("access_token") == null) return false
        saveTokens(body)
        return true
    }

    private suspend fun ensureFresh() {
        val exp = settings.simklExpiresAt
        if (exp > 0 && System.currentTimeMillis() > exp - 24 * 3600_000L) refresh()
    }

    /** POST x-www-form-urlencoded; returns HTTP code + JSON body. */
    private suspend fun form(path: String, fields: Map<String, String>): Pair<Int, JsonObject?> = withContext(Dispatchers.IO) {
        val fb = okhttp3.FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        val req = Request.Builder().url("https://api.simkl.com$path")
            .header("User-Agent", "dhakaflixtv/${BuildConfig.VERSION_NAME}").post(fb).build()
        app.http.newCall(req).execute().use { r ->
            r.code to runCatching { json.parseToJsonElement(r.body?.string().orEmpty()) as? JsonObject }.getOrNull()
        }
    }

    fun cancelLogin() { loginJob?.cancel(); _pin.value = null }

    fun signOut() {
        cancelLogin()
        settings.setSimklAccount("", "")
        settings.setSimklRefresh("", 0)
        settings.setSimklLastSync(0)
    }

    // ------------------------------------------------------------------ full sync

    /** What Simkl had at the last pull, so a removal on another device can be told apart from a new local add. */
    private val snap = app.settings.store("simkl_snapshot")
    private var lastPull = 0L

    /** Pull Simkl → this device, then push anything this device has that Simkl doesn't. */
    fun syncNow(firstTime: Boolean = false) {
        if (!signedIn) return
        app.appScope.launch(Dispatchers.IO) {
            if (!mutex.tryLock()) return@launch
            _syncing.value = true
            _error.value = null
            try {
                pull()
                pushMissing(firstTime || settings.simklLastSync.value == 0L)
                lastPull = System.currentTimeMillis()
                settings.setSimklLastSync(lastPull)
            } catch (t: Throwable) {
                _error.value = t.message
                DiagLog.add("Simkl", t.message ?: t.toString())
            } finally {
                _syncing.value = false
                mutex.unlock()
            }
        }
    }

    /** Called when the app comes to the front and every few minutes while it's open. */
    fun syncIfStale(maxAgeMs: Long = 2 * 60_000L) {
        if (signedIn && System.currentTimeMillis() - lastPull > maxAgeMs) syncNow()
    }

    private data class Remote(val watched: MutableMap<String, Long> = HashMap(), val list: MutableMap<String, Long> = HashMap(),
                              val episodes: MutableMap<String, MutableSet<Pair<Int, Int>>> = HashMap())

    private suspend fun pull() {
        val r = Remote()
        for (type in listOf("movies", "shows", "anime")) {
            val q = if (type == "movies") "" else "?extended=full&episode_watched_at=yes"
            val root = runCatching { getJson("/sync/all-items/$type$q") }.getOrNull() as? JsonObject ?: continue
            val arr = root[type] as? JsonArray ?: continue
            for (el in arr) {
                val o = el as? JsonObject ?: continue
                val media = o.obj(if (type == "movies") "movie" else "show") ?: o.obj("show") ?: o.obj("anime") ?: continue
                val item = match(media, type != "movies") ?: continue
                when (o.str("status")) {
                    "completed" -> r.watched[item.id] = parseTime(o.str("last_watched_at")) ?: System.currentTimeMillis()
                    "plantowatch" -> r.list[item.id] = parseTime(o.str("added_to_watchlist_at")) ?: System.currentTimeMillis()
                }
                (o["seasons"] as? JsonArray)?.forEach { se ->
                    val so = se as? JsonObject ?: return@forEach
                    val sn = so.int("number") ?: return@forEach
                    (so["episodes"] as? JsonArray)?.forEach { ee ->
                        val en = (ee as? JsonObject)?.int("number") ?: return@forEach
                        r.episodes.getOrPut(item.id) { HashSet() } += sn to en
                    }
                }
            }
        }
        applyRemote(r)
        pullPlayback()
    }

    private suspend fun applyRemote(r: Remote) {
        val prevWatched = snap.getStringSet("watched", emptySet()) ?: emptySet()
        val prevList = snap.getStringSet("list", emptySet()) ?: emptySet()
        val firstPull = !snap.contains("watched")
        val local = db.user().all().associateBy { it.itemId }

        // additions from other devices
        r.watched.forEach { (id, at) ->
            val u = local[id] ?: UserStateEntity(id)
            if (!u.watched) { db.user().put(u.copy(watched = true, watchedAt = at)); db.progress().completeItem(id) }
        }
        r.list.forEach { (id, at) ->
            val u = db.user().get(id) ?: UserStateEntity(id)
            if (!u.inWatchlist) db.user().put(u.copy(inWatchlist = true, watchlistAt = at))
        }
        // removals made on other devices: was on Simkl last time, isn't now
        if (!firstPull) {
            for (id in prevWatched - r.watched.keys) {
                val u = db.user().get(id) ?: continue
                if (u.watched) db.user().put(u.copy(watched = false))
            }
            for (id in prevList - r.list.keys) {
                val u = db.user().get(id) ?: continue
                if (u.inWatchlist && id !in r.watched) db.user().put(u.copy(inWatchlist = false))
            }
        }
        snap.edit().putStringSet("watched", r.watched.keys).putStringSet("list", r.list.keys).apply()

        // finished episodes from other devices (a few shows per sync: each needs a folder listing)
        var listed = 0
        for ((id, eps) in r.episodes) {
            if (listed >= 12) break
            val done = db.progress().all().filter { it.itemId == id && it.completed }.mapNotNull { p -> p.season?.let { s -> p.episode?.let { e -> s to e } } }.toSet()
            val missing = eps - done
            if (missing.isEmpty()) continue
            val item = db.items().get(id) ?: continue
            listed++
            val files = episodeFiles(item)
            for ((sn, en) in missing) {
                val url = files[sn to en] ?: continue
                val existing = db.progress().get(url)
                db.progress().put(
                    ProgressEntity(
                        fileUrl = url, itemId = id, label = com.israk.dhakaflix.data.LibraryRepository.episodeLabel(sn, en),
                        season = sn, episode = en, positionMs = existing?.durationMs ?: 0, durationMs = existing?.durationMs ?: 0,
                        updatedAt = existing?.updatedAt ?: 0L, completed = true,
                    )
                )
            }
        }
    }

    /** Continue Watching from other devices (Simkl keeps paused positions). */
    private suspend fun pullPlayback() {
        val arr = runCatching { getJson("/sync/playback") }.getOrNull() as? JsonArray ?: return
        for (el in arr.take(50)) {
            val o = el as? JsonObject ?: continue
            val pct = o["progress"]?.jsonPrimitive?.doubleOrNull ?: continue
            if (pct < 1 || pct > 95) continue
            val pausedAt = parseTime(o.str("paused_at")) ?: System.currentTimeMillis() - 60_000
            val ep = o.obj("episode")
            val isEpisode = o.str("type") == "episode" || ep != null
            val media = (if (isEpisode) o.obj("show") ?: o.obj("anime") else o.obj("movie")) ?: continue
            val item = match(media, isEpisode) ?: continue
            val season = ep?.int("season") ?: ep?.int("tvdb_season")
            val number = ep?.int("number") ?: ep?.int("episode") ?: ep?.int("tvdb_number")
            if (isEpisode && (season == null || number == null)) continue
            val local = if (isEpisode) db.progress().episode(item.id, season!!, number!!) else db.progress().latestFor(item.id)
            if (local != null && (local.updatedAt >= pausedAt || local.completed && local.updatedAt > pausedAt - 60_000)) continue
            val fileUrl = local?.fileUrl ?: findFile(item, season, number) ?: continue
            val dur = local?.durationMs?.takeIf { it > 0 } ?: ((item.runtime?.takeIf { it > 0 } ?: if (isEpisode) 45 else 120) * 60_000L)
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

    /** Find the library entry for a Simkl media object: TMDB id first, then title + year. */
    private suspend fun match(media: JsonObject, tv: Boolean): ItemEntity? {
        val type = if (tv) "SERIES" else "MOVIE"
        media.obj("ids")?.tmdb()?.let { t -> db.items().byTmdb(t, type)?.let { return it } }
        val title = media.str("title") ?: return null
        val year = media.int("year") ?: return null
        return db.items().byTitleYear(title.lowercase(), year, type)
    }

    private suspend fun episodeFiles(item: ItemEntity): Map<Pair<Int, Int>, String> = runCatching {
        val repo = app.library
        val content = repo.listItem(item)
        val out = HashMap<Pair<Int, Int>, String>()
        content.videos.forEach { v -> if (v.season != null && v.episode != null) out[v.season to v.episode] = v.url }
        content.seasons.forEach { ref -> repo.listSeason(ref).forEach { v -> if (v.season != null && v.episode != null) out.putIfAbsent(v.season to v.episode, v.url) } }
        out
    }.getOrDefault(emptyMap())

    private suspend fun findFile(item: ItemEntity, season: Int?, episode: Int?): String? = runCatching {
        val repo = app.library
        val content = repo.listItem(item)
        if (!item.isSeries) return@runCatching content.videos.maxByOrNull { it.size }?.url
        content.videos.firstOrNull { it.season == season && it.episode == episode }?.url?.let { return@runCatching it }
        val ref = content.seasons.firstOrNull { it.number == season } ?: return@runCatching null
        repo.listSeason(ref).firstOrNull { it.episode == episode }?.url
    }.getOrNull()

    /** Push what Simkl doesn't know yet. On the first sync that's everything; later only local additions. */
    private suspend fun pushMissing(everything: Boolean) {
        val remoteWatched = snap.getStringSet("watched", emptySet()) ?: emptySet()
        val remoteList = snap.getStringSet("list", emptySet()) ?: emptySet()
        val states = db.user().all()
        val items = db.items().getMany(states.map { it.itemId }).associateBy { it.id }
        val watched = states.filter { it.watched && (everything || it.itemId !in remoteWatched) }.mapNotNull { items[it.itemId]?.takeIf { i -> i.tmdbId != null } }
        val wl = states.filter { it.inWatchlist && !it.watched && (everything || it.itemId !in remoteList) }.mapNotNull { items[it.itemId]?.takeIf { i -> i.tmdbId != null } }
        if (watched.isNotEmpty()) post("/sync/history", historyBody(watched.map { it to null }))
        if (wl.isNotEmpty()) post("/sync/add-to-list", listBody(wl, "plantowatch"))
        if (everything) {
            val done = db.progress().all().filter { it.completed && it.season != null && it.episode != null }
            val shows = db.items().getMany(done.map { it.itemId }.distinct()).associateBy { it.id }
            val eps = done.mapNotNull { p -> shows[p.itemId]?.takeIf { it.tmdbId != null }?.let { it to (p.season!! to p.episode!!) } }
            if (eps.isNotEmpty()) post("/sync/history", historyBody(eps))
        }
        // remember them as on Simkl now, so the next pull doesn't treat them as "removed elsewhere"
        snap.edit()
            .putStringSet("watched", remoteWatched + watched.map { it.id })
            .putStringSet("list", remoteList + wl.map { it.id })
            .apply()
    }

    // ------------------------------------------------------------------ live pushes (fire & forget)

    private fun snapUpdate(key: String, id: String, add: Boolean) {
        val cur = snap.getStringSet(key, emptySet()) ?: emptySet()
        snap.edit().putStringSet(key, if (add) cur + id else cur - id).apply()
    }

    fun onWatched(itemId: String, watched: Boolean) = push(itemId) { item ->
        if (watched) post("/sync/history", historyBody(listOf(item to null)))
        else post("/sync/history/remove", historyBody(listOf(item to null)))
        snapUpdate("watched", itemId, watched)
    }

    fun onWatchlist(itemId: String, inList: Boolean) = push(itemId) { item ->
        if (inList) post("/sync/add-to-list", listBody(listOf(item), "plantowatch"))
        else {
            val u = db.user().get(itemId)
            if (u?.watched != true) post("/sync/history/remove", historyBody(listOf(item to null)))
        }
        snapUpdate("list", itemId, inList)
    }

    fun onEpisodeDone(itemId: String, season: Int, episode: Int) = push(itemId) { item ->
        runCatching { post("/scrobble/stop", scrobbleBody(item, season, episode, 100.0)) }
        post("/sync/history", historyBody(listOf(item to (season to episode))))
    }

    /** Movie finished: stop the scrobble so the paused position disappears on other devices. */
    fun onMovieDone(itemId: String) = push(itemId) { item ->
        if (!item.isSeries) runCatching { post("/scrobble/stop", scrobbleBody(item, null, null, 100.0)) }
    }

    private val started = HashSet<String>()

    /** Playback position for other devices' Continue Watching; throttled unless [force]. */
    fun onProgress(play: PlayItem, positionMs: Long, durationMs: Long, force: Boolean) {
        if (!signedIn || durationMs <= 0 || positionMs < 20_000) return
        val now = System.currentTimeMillis()
        if (!force && now - (lastScrobble[play.url] ?: 0L) < 60_000) return
        lastScrobble[play.url] = now
        val pct = (positionMs * 100.0 / durationMs).coerceIn(1.0, 95.0)
        push(play.itemId) { item ->
            val body = scrobbleBody(item, play.season, play.episode, pct)
            // a scrobble session starts with /start; the paused position is what other devices resume from
            if (started.add(play.url)) runCatching { post("/scrobble/start", body) }
            runCatching { post("/scrobble/pause", body) }.onFailure {
                // some sessions need a fresh start before they can be paused again
                started.remove(play.url)
                runCatching { post("/scrobble/start", body); post("/scrobble/pause", body) }.getOrThrow()
            }
        }
    }

    private fun scrobbleBody(item: ItemEntity, season: Int?, episode: Int?, pct: Double): JsonObject = buildJsonObject {
        put("progress", Math.round(pct * 100) / 100.0)
        if (item.isSeries && season != null && episode != null) {
            putJsonObject("show") { put("title", item.displayTitle); item.displayYear?.let { put("year", it) }; putJsonObject("ids") { put("tmdb", item.tmdbId) } }
            putJsonObject("episode") { put("season", season); put("number", episode) }
        } else {
            putJsonObject("movie") { put("title", item.displayTitle); item.displayYear?.let { put("year", it) }; putJsonObject("ids") { put("tmdb", item.tmdbId) } }
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

    private suspend fun getJson(path: String, auth: Boolean = true, retried: Boolean = false): JsonElement? = withContext(Dispatchers.IO) {
        if (auth) ensureFresh()
        app.http.newCall(builder(path, auth).build()).execute().use { r ->
            if (r.code == 401 && auth) {
                if (!retried && refresh()) return@withContext getJson(path, auth, true)
                settings.setSimklAccount("", ""); throw java.io.IOException("Simkl sign-in expired")
            }
            if (!r.isSuccessful) throw java.io.IOException("Simkl HTTP ${r.code}")
            val b = r.body?.string().orEmpty()
            if (b.isBlank() || b == "null") null else json.parseToJsonElement(b)
        }
    }

    private suspend fun post(path: String, body: JsonObject, retried: Boolean = false): JsonElement? = withContext(Dispatchers.IO) {
        ensureFresh()
        val req = builder(path, true).post(body.toString().toRequestBody("application/json".toMediaType())).build()
        app.http.newCall(req).execute().use { r ->
            if (r.code == 401 && !retried && refresh()) return@withContext post(path, body, true)
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
        // 2024-02-10T12:34:56Z, …56.123Z, …56+00:00, "2024-02-10 12:34:56"
        val m = Regex("""(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?(Z|[+-]\d{2}:?\d{2})?""").find(s) ?: return null
        val g = m.groupValues
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt())
        }
        var t = cal.timeInMillis
        val tz = g[7]
        if (tz.isNotEmpty() && tz != "Z") {
            val sign = if (tz[0] == '-') -1 else 1
            val d = tz.substring(1).replace(":", "")
            t -= sign * (d.substring(0, 2).toInt() * 3600_000L + d.substring(2).toInt() * 60_000L)
        }
        return t
    }

}
