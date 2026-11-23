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
        val user = runCatching {
            (post("/users/settings", JsonObject(emptyMap())) as? JsonObject)?.obj("user")
        }.getOrNull()
        val name = user?.str("name") ?: "Simkl"
        settings.setSimklAccount(token, name)
        settings.setSimklAvatar(user?.str("avatar").orEmpty())
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
        cacheStore.edit().clear().apply()
        snap.edit().remove("activities").apply()
        settings.setSimklAccount("", "")
        settings.setSimklAvatar("")
        settings.setSimklRefresh("", 0)
        settings.setSimklLastSync(0)
    }

    // ------------------------------------------------------------------ full sync

    /** What Simkl had at the last pull, so a removal on another device can be told apart from a new local add. */
    private val snap = app.settings.store("simkl_snapshot")
    private var lastPull = 0L

    /** Watchlist removals made on this device: id -> time. Simkl can keep returning a removed item for a
     *  while (or the remove call can fail), so a pull must not put it back until Simkl really dropped it. */
    private fun tombs(key: String = "list_removed"): MutableMap<String, Long> =
        (snap.getStringSet(key, emptySet()) ?: emptySet()).mapNotNull { e ->
            val i = e.lastIndexOf('|'); if (i <= 0) null else e.substring(0, i) to (e.substring(i + 1).toLongOrNull() ?: 0L)
        }.toMap(HashMap())
    private fun saveTombs(m: Map<String, Long>, key: String = "list_removed") {
        snap.edit().putStringSet(key, m.map { (k, v) -> "$k|$v" }.toSet()).apply()
    }

    /** Pull Simkl → this device, then push anything this device has that Simkl doesn't. */
    fun syncNow(firstTime: Boolean = false) {
        if (!signedIn) return
        app.appScope.launch(Dispatchers.IO) {
            if (!mutex.tryLock()) return@launch
            _syncing.value = true
            _error.value = null
            try {
                if (!avatarTried && settings.simklAvatar.value.isBlank()) {
                    avatarTried = true
                    runCatching {
                        (post("/users/settings", JsonObject(emptyMap())) as? JsonObject)?.obj("user")?.str("avatar")
                    }.getOrNull()?.takeIf { it.isNotBlank() }?.let { settings.setSimklAvatar(it) }
                }
                pull(full = firstTime)
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
    @Volatile private var avatarTried = false

    /** Called when the app comes to the front and every few minutes while it's open. Simkl asks mobile /
     *  TV apps to check at most every 15-30 min (polling harder gets the app's client ID blocked). */
    fun syncIfStale(maxAgeMs: Long = 15 * 60_000L) {
        if (signedIn && System.currentTimeMillis() - lastPull > maxAgeMs) syncNow()
    }

    private data class Remote(val watched: MutableMap<String, Long> = HashMap(), val list: MutableMap<String, Long> = HashMap(),
                              val episodes: MutableMap<String, MutableSet<Pair<Int, Int>>> = HashMap())

    /**
     * Simkl's own sync model (api.simkl.org/guides/sync):
     *  - read /sync/activities first; if nothing moved since last time, stop there (one tiny call);
     *  - otherwise fetch only what changed (date_from delta), and the id list when something was removed;
     *  - the whole library only on the very first sync.
     * The old code pulled the whole library from both devices every few minutes, which Simkl throttles —
     * and a failed pull was read as "everything was removed". Now any failed call aborts the sync.
     */
    private suspend fun pull(full: Boolean) {
        val act = getJson("/sync/activities") as? JsonObject ?: throw java.io.IOException("Simkl: no activities")
        val prev = snap.getString("activities", null)?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val ready = cacheStore.getBoolean(READY, false)
        val changed: Boolean
        var playbackMoved = true
        if (full || prev == null || !ready) {
            val cache = HashMap<String, JsonObject>()
            for (type in listOf("movies", "shows", "anime")) {
                val q = if (type == "movies") "" else "?extended=full&episode_watched_at=yes"
                val root = getJson("/sync/all-items/$type$q") as? JsonObject ?: continue // null = nothing of this type
                addEntries(cache, root)
            }
            saveCache(cache)
            changed = true
        } else if (act.str("all") != null && act.str("all") == prev.str("all")) {
            changed = false
            playbackMoved = false
        } else {
            val cache = loadCache()
            fun moved(key: String) = BLOCKS.any { b -> act.obj(b)?.str(key) != prev.obj(b)?.str(key) }
            if (listOf("watching", "plantowatch", "hold", "completed", "dropped").any { moved(it) }) {
                val since = prev.str("all")
                val q = buildString {
                    append("/sync/all-items?extended=full&episode_watched_at=yes")
                    if (since != null) append("&date_from=").append(java.net.URLEncoder.encode(since, "UTF-8"))
                }
                (getJson(q) as? JsonObject)?.let { addEntries(cache, it) }
            }
            if (moved("removed_from_list")) {
                val root = getJson("/sync/all-items?extended=simkl_ids_only") as? JsonObject
                val keep = HashSet<String>()
                if (root != null) for (type in listOf("movies", "shows", "anime")) {
                    (root[type] as? JsonArray)?.forEach { el -> simklId(el)?.let { keep += "$type:$it" } }
                }
                // an empty answer while we hold items is more likely a hiccup than "everything removed"
                if (keep.isNotEmpty() || cache.isEmpty()) cache.keys.retainAll { it in keep || !it.substringAfter(':').all(Char::isDigit) }
            }
            playbackMoved = moved("playback")
            saveCache(cache)
            changed = true
        }
        if (changed || !snap.contains("watched")) applyRemote(remoteFromCache())
        if (playbackMoved) pullPlayback()
        // saved last, only after every call above succeeded
        snap.edit().putString("activities", act.toString()).apply()
    }

    // ---- local copy of the Simkl library ("type:simklId" -> small JSON), so deltas can be merged
    private val cacheStore = app.settings.store("simkl_remote")

    private fun loadCache(): HashMap<String, JsonObject> {
        val out = HashMap<String, JsonObject>()
        cacheStore.all.forEach { (k, v) ->
            if (k != READY && v is String) runCatching { json.parseToJsonElement(v) as? JsonObject }.getOrNull()?.let { out[k] = it }
        }
        return out
    }

    private fun saveCache(cache: Map<String, JsonObject>) {
        val e = cacheStore.edit().clear()
        cache.forEach { (k, v) -> e.putString(k, v.toString()) }
        e.putBoolean(READY, true).commit()
    }

    /** Merge one /sync/all-items answer (full or delta) into the cache. */
    private fun addEntries(cache: MutableMap<String, JsonObject>, root: JsonObject) {
        for (type in listOf("movies", "shows", "anime")) {
            val arr = root[type] as? JsonArray ?: continue
            for (el in arr) {
                val o = el as? JsonObject ?: continue
                val media = o.obj(if (type == "movies") "movie" else "show") ?: o.obj("show") ?: o.obj("anime") ?: continue
                val ids = media.obj("ids")
                val sid = ids?.str("simkl") ?: ids?.tmdb()?.let { "tmdb$it" } ?: continue
                val eps = buildJsonArray {
                    (o["seasons"] as? JsonArray)?.forEach { se ->
                        val so = se as? JsonObject ?: return@forEach
                        val sn = so.int("number") ?: return@forEach
                        (so["episodes"] as? JsonArray)?.forEach { ee ->
                            (ee as? JsonObject)?.int("number")?.let { en -> add(kotlinx.serialization.json.JsonPrimitive("$sn:$en")) }
                        }
                    }
                }
                cache["$type:$sid"] = buildJsonObject {
                    put("tv", type != "movies")
                    ids?.tmdb()?.let { put("tmdb", it) }
                    media.str("title")?.let { put("title", it) }
                    media.int("year")?.let { put("year", it) }
                    put("status", o.str("status") ?: "")
                    put("watchedAt", parseTime(o.str("last_watched_at")) ?: 0L)
                    put("listAt", parseTime(o.str("added_to_watchlist_at")) ?: 0L)
                    put("eps", eps)
                }
            }
        }
    }

    private fun simklId(el: JsonElement): String? {
        val o = el as? JsonObject ?: return null
        o.obj("ids")?.str("simkl")?.let { return it }
        for (k in listOf("movie", "show", "anime")) o.obj(k)?.obj("ids")?.str("simkl")?.let { return it }
        return null
    }

    /** The cache, matched to library items, in the shape applyRemote() works with. */
    private suspend fun remoteFromCache(): Remote {
        val r = Remote()
        for ((_, e) in loadCache()) {
            val media = buildJsonObject {
                e.int("tmdb")?.let { putJsonObject("ids") { put("tmdb", it) } }
                e.str("title")?.let { put("title", it) }
                e.int("year")?.let { put("year", it) }
            }
            val tv = e["tv"]?.jsonPrimitive?.contentOrNull == "true"
            val item = match(media, tv) ?: continue
            when (e.str("status")) {
                "completed" -> r.watched[item.id] = e["watchedAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                "plantowatch" -> r.list[item.id] = e["listAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            }
            (e["eps"] as? JsonArray)?.forEach { p ->
                val s = p.jsonPrimitive.contentOrNull ?: return@forEach
                val i = s.indexOf(':'); if (i <= 0) return@forEach
                val sn = s.substring(0, i).toIntOrNull() ?: return@forEach
                val en = s.substring(i + 1).toIntOrNull() ?: return@forEach
                r.episodes.getOrPut(item.id) { HashSet() } += sn to en
            }
        }
        return r
    }

    /** Episodes Simkl says are watched, per series (shown as a check on the episode even when the file
     *  isn't listed yet). Kept as "s:e" strings under the item id. */
    private val epStore = app.settings.store("simkl_watched_eps")
    private val _epVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    val watchedEpisodesVersion: kotlinx.coroutines.flow.StateFlow<Int> = _epVersion
    fun watchedEpisodes(itemId: String): Set<Pair<Int, Int>> =
        (epStore.getStringSet(itemId, emptySet()) ?: emptySet()).mapNotNull { s ->
            val i = s.indexOf(':'); if (i <= 0) null else s.substring(0, i).toIntOrNull()?.let { a -> s.substring(i + 1).toIntOrNull()?.let { a to it } }
        }.toSet()
    private fun saveWatchedEpisodes(all: Map<String, Set<Pair<Int, Int>>>) {
        val e = epStore.edit().clear()
        all.forEach { (id, eps) -> e.putStringSet(id, eps.map { "${it.first}:${it.second}" }.toSet()) }
        e.apply()
        _epVersion.value++
    }

    private suspend fun applyRemote(r: Remote) {
        saveWatchedEpisodes(r.episodes)
        val prevWatched = snap.getStringSet("watched", emptySet()) ?: emptySet()
        val prevList = snap.getStringSet("list", emptySet()) ?: emptySet()
        val firstPull = !snap.contains("watched")
        val local = db.user().all().associateBy { it.itemId }

        // additions from other devices
        val wTomb = tombs("watched_removed")
        wTomb.entries.removeAll { (id, rm) -> id !in r.watched || System.currentTimeMillis() - rm > 14L * 24 * 3600_000 }
        saveTombs(wTomb, "watched_removed")
        r.watched.forEach { (id, at) ->
            if (wTomb[id]?.let { at <= it } == true) return@forEach // un-watched here; Simkl hasn't caught up yet
            val u = local[id] ?: UserStateEntity(id)
            if (!u.watched) { db.user().put(u.copy(watched = true, watchedAt = if (at > 0) at else System.currentTimeMillis())); db.progress().completeItem(id) }
        }
        val tomb = tombs()
        val now = System.currentTimeMillis()
        tomb.entries.removeAll { (id, at) -> id !in r.list || now - at > 14L * 24 * 3600_000 }
        saveTombs(tomb)
        r.list.forEach { (id, at) ->
            val removedAt = tomb[id]
            if (removedAt != null && at <= removedAt) return@forEach // removed here; Simkl hasn't caught up yet
            if (removedAt != null) { tomb.remove(id); saveTombs(tomb) } // re-added elsewhere after our removal
            val u = db.user().get(id) ?: UserStateEntity(id)
            if (!u.inWatchlist) db.user().put(u.copy(inWatchlist = true, watchlistAt = if (at > 0) at else now))
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
        snap.edit().putStringSet("watched", r.watched.keys - wTomb.keys).putStringSet("list", r.list.keys - tomb.keys).apply()

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
        val cwTomb = tombs("cw_removed")
        if (cwTomb.entries.removeAll { System.currentTimeMillis() - it.value > 60L * 24 * 3600_000 }) saveTombs(cwTomb, "cw_removed")
        for (el in arr.take(50)) {
            val o = el as? JsonObject ?: continue
            val pct = o["progress"]?.jsonPrimitive?.doubleOrNull ?: continue
            if (pct < 1 || pct > 95) continue
            val pausedAt = parseTime(o.str("paused_at")) ?: System.currentTimeMillis() - 60_000
            val ep = o.obj("episode")
            val isEpisode = o.str("type") == "episode" || ep != null
            val media = (if (isEpisode) o.obj("show") ?: o.obj("anime") else o.obj("movie")) ?: continue
            val item = match(media, isEpisode) ?: continue
            // removed from Continue Watching here after this pause: keep it gone, and clear it on Simkl
            val removedAt = cwTomb[item.id]
            if (removedAt != null && removedAt >= pausedAt) {
                o.int("id")?.let { pid -> runCatching { delete("/sync/playback/$pid") } }
                continue
            }
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
        // removals Simkl still shows: send them again
        val again = (tombs().keys + tombs("watched_removed").keys).filter { id -> states.none { it.itemId == id && (it.inWatchlist || it.watched) } }
        val againItems = db.items().getMany(again).filter { it.tmdbId != null }
        if (againItems.isNotEmpty()) runCatching { post("/sync/history/remove", historyBody(againItems.map { it to null })) }
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

    fun onWatched(itemId: String, watched: Boolean) {
        if (signedIn) {
            val tomb = tombs("watched_removed")
            if (watched) tomb.remove(itemId) else tomb[itemId] = System.currentTimeMillis()
            saveTombs(tomb, "watched_removed")
        }
        pushWatched(itemId, watched)
    }

    private fun pushWatched(itemId: String, watched: Boolean) = push(itemId) { item ->
        if (watched) post("/sync/history", historyBody(listOf(item to null)))
        else post("/sync/history/remove", historyBody(listOf(item to null)))
        snapUpdate("watched", itemId, watched)
    }

    fun onWatchlist(itemId: String, inList: Boolean) {
        if (signedIn) {
            val tomb = tombs()
            if (inList) tomb.remove(itemId) else tomb[itemId] = System.currentTimeMillis()
            saveTombs(tomb)
        }
        pushWatchlist(itemId, inList)
    }

    private fun pushWatchlist(itemId: String, inList: Boolean) = push(itemId) { item ->
        if (inList) post("/sync/add-to-list", listBody(listOf(item), "plantowatch"))
        else {
            val u = db.user().get(itemId)
            if (u?.watched != true) post("/sync/history/remove", historyBody(listOf(item to null)))
        }
        snapUpdate("list", itemId, inList)
    }

    /** Removed from Continue Watching on this device: Simkl's old paused position must not bring it back. */
    fun onContinueRemoved(itemId: String) {
        val tomb = tombs("cw_removed")
        tomb[itemId] = System.currentTimeMillis()
        saveTombs(tomb, "cw_removed")
    }

    /** Finished episode: history entry (Simkl then hides the old paused position on every device). */
    fun onEpisodeDone(itemId: String, season: Int, episode: Int) = push(itemId) { item ->
        post("/sync/history", historyBody(listOf(item to (season to episode))))
    }

    /** Finished movie: the history entry from onWatched hides its paused position everywhere. */
    fun onMovieDone(itemId: String) = Unit

    /** Playback position for other devices' Continue Watching; throttled unless [force]. */
    fun onProgress(play: PlayItem, positionMs: Long, durationMs: Long, force: Boolean) {
        if (!signedIn || durationMs <= 0 || positionMs < 20_000) return
        val now = System.currentTimeMillis()
        val last = lastScrobble[play.url] ?: 0L
        // Simkl moves progress on by itself; only re-post now and then (stop / pause always posts)
        if (!force && now - last < 10 * 60_000) return
        lastScrobble[play.url] = now
        val pct = (positionMs * 100.0 / durationMs).coerceIn(1.0, 95.0)
        push(play.itemId) { item ->
            // Simkl locks scrobbles for 20 s per user: wait out the lock instead of getting a RATE_LIMIT 400
            val wait = 21_000L - (System.currentTimeMillis() - lastScrobbleSent)
            if (wait > 0) delay(wait)
            lastScrobbleSent = System.currentTimeMillis()
            val body = scrobbleBody(item, play.season, play.episode, pct)
            runCatching { post("/scrobble/pause", body) }.onFailure {
                delay(21_000)
                lastScrobbleSent = System.currentTimeMillis()
                post("/scrobble/pause", body)
            }
        }
    }

    @Volatile private var lastScrobbleSent = 0L

    private fun scrobbleBody(item: ItemEntity, season: Int?, episode: Int?, pct: Double): JsonObject = buildJsonObject {
        put("progress", Math.round(pct * 100) / 100.0)
        if (item.isSeries && season != null && episode != null) {
            putJsonObject("show") { put("title", item.displayTitle); item.displayYear?.let { put("year", it) }; putJsonObject("ids") { put("tmdb", item.tmdbId?.toString()) } }
            putJsonObject("episode") { put("season", season); put("number", episode) }
        } else {
            putJsonObject("movie") { put("title", item.displayTitle); item.displayYear?.let { put("year", it) }; putJsonObject("ids") { put("tmdb", item.tmdbId?.toString()) } }
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
            movies.forEach { (m, _) -> add(buildJsonObject { put("watched_at", now); putJsonObject("ids") { put("tmdb", m.tmdbId?.toString()) } }) }
        }
        if (shows.isNotEmpty()) putJsonArray("shows") {
            shows.forEach { (tmdb, entries) ->
                add(buildJsonObject {
                    putJsonObject("ids") { put("tmdb", tmdb?.toString()) }
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
            movies.forEach { add(buildJsonObject { put("to", to); putJsonObject("ids") { put("tmdb", it.tmdbId?.toString()) } }) }
        }
        if (shows.isNotEmpty()) putJsonArray("shows") {
            shows.forEach { add(buildJsonObject { put("to", to); putJsonObject("ids") { put("tmdb", it.tmdbId?.toString()) } }) }
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

    private suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        ensureFresh()
        app.http.newCall(builder(path, true).delete().build()).execute().use { }
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

    private companion object {
        const val READY = "__ready"
        val BLOCKS = listOf("movies", "tv_shows", "anime")
    }
}
