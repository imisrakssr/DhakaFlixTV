package com.israk.dhakaflix.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class AppSettings(ctx: Context) {
    companion object {
        const val DEFAULT_AUDIO_DELAY = -30L
        val SUB_SCALES = floatArrayOf(0.8f, 1f, 1.25f, 1.5f)
        /** ARGB colours + names for the subtitle colour picker. */
        val SUB_COLORS = listOf(0xFFFFFFFF.toInt() to "White", 0xFFFFE14D.toInt() to "Yellow", 0xFF7DF9FF.toInt() to "Cyan", 0xFF9BFF8A.toInt() to "Green")
    }

    private val prefs = ctx.getSharedPreferences("dhakaflix_settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** "en" or "dhk" (Dhakaiya). Older "bn" installs fall back to English. */
    private val _language = MutableStateFlow((prefs.getString("language", "en") ?: "en").let { if (it == "dhk") it else "en" })
    val language: StateFlow<String> = _language
    fun setLanguage(v: String) { _language.value = v; prefs.edit().putString("language", v).apply() }

    /** "top" or "left" navigation on TV. */
    private val _navPosition = MutableStateFlow(prefs.getString("nav_position", "top") ?: "top")
    val navPosition: StateFlow<String> = _navPosition
    fun setNavPosition(v: String) { _navPosition.value = v; prefs.edit().putString("nav_position", v).apply() }

    /** 0 = small, 1 = medium, 2 = large */
    private val _uiSize = MutableStateFlow(prefs.getInt("ui_size", 1))
    val uiSize: StateFlow<Int> = _uiSize
    fun setUiSize(v: Int) { _uiSize.value = v; prefs.edit().putInt("ui_size", v).apply() }

    /** Name in the Home greeting ("What's the mood today, Israk?"). Blank = no name. */
    private val _userName = MutableStateFlow(prefs.getString("user_name", "Israk") ?: "Israk")
    val userName: StateFlow<String> = _userName
    fun setUserName(v: String) { _userName.value = v.trim(); prefs.edit().putString("user_name", v.trim()).apply() }

    /** Audio delay in ms (positive = sound later). Device-wide, so it's remembered. */
    private val _audioDelay = MutableStateFlow(prefs.getLong("audio_delay", DEFAULT_AUDIO_DELAY))
    val audioDelay: StateFlow<Long> = _audioDelay
    fun setAudioDelay(v: Long) { _audioDelay.value = v; prefs.edit().putLong("audio_delay", v).apply() }

    private val _subDelay = MutableStateFlow(prefs.getLong("sub_delay", 0L))
    val subDelay: StateFlow<Long> = _subDelay
    fun setSubDelay(v: Long) { _subDelay.value = v; prefs.edit().putLong("sub_delay", v).apply() }

    /** Titles opened from the Search page, newest first. */
    private val _recentSearch = MutableStateFlow(prefs.getString("recent_search", "")!!.split('\n').filter { it.isNotBlank() })
    val recentSearch: StateFlow<List<String>> = _recentSearch
    fun addRecentSearch(id: String) {
        val v = (listOf(id) + _recentSearch.value.filter { it != id }).take(30)
        _recentSearch.value = v
        prefs.edit().putString("recent_search", v.joinToString("\n")).apply()
    }
    fun clearRecentSearch() { _recentSearch.value = emptyList(); prefs.edit().remove("recent_search").apply() }

    /** Subtitle style: size 0..3 (small..extra large), colour index into SUB_COLORS. */
    private val _subSize = MutableStateFlow(prefs.getInt("sub_size", 1))
    val subSize: StateFlow<Int> = _subSize
    fun setSubSize(v: Int) { _subSize.value = v; prefs.edit().putInt("sub_size", v).apply() }
    private val _subColor = MutableStateFlow(prefs.getInt("sub_color", 0))
    val subColor: StateFlow<Int> = _subColor
    fun setSubColor(v: Int) { _subColor.value = v; prefs.edit().putInt("sub_color", v).apply() }

    // ---- accounts
    private val _mdblistKey = MutableStateFlow(prefs.getString("mdblist_key", "") ?: "")
    val mdblistKey: StateFlow<String> = _mdblistKey
    fun setMdblistKey(v: String) { _mdblistKey.value = v.trim(); prefs.edit().putString("mdblist_key", v.trim()).apply() }

    /** Simkl app client id (user's own, or the one built in by CI). */
    private val _simklClientId = MutableStateFlow(prefs.getString("simkl_client", "") ?: "")
    val simklClientId: StateFlow<String> = _simklClientId
    fun setSimklClientId(v: String) { _simklClientId.value = v.trim(); prefs.edit().putString("simkl_client", v.trim()).apply() }
    private val _simklToken = MutableStateFlow(prefs.getString("simkl_token", "") ?: "")
    val simklToken: StateFlow<String> = _simklToken
    private val _simklUser = MutableStateFlow(prefs.getString("simkl_user", "") ?: "")
    val simklUser: StateFlow<String> = _simklUser
    fun setSimklAccount(token: String, user: String) {
        _simklToken.value = token; _simklUser.value = user
        prefs.edit().putString("simkl_token", token).putString("simkl_user", user).apply()
    }
    /** Simkl profile picture (shown top-right when signed in). */
    private val _simklAvatar = MutableStateFlow(prefs.getString("simkl_avatar", "") ?: "")
    val simklAvatar: StateFlow<String> = _simklAvatar
    fun setSimklAvatar(url: String) { _simklAvatar.value = url; prefs.edit().putString("simkl_avatar", url).apply() }
    /** AUTH V2: refresh token + access-token expiry (ms). */
    val simklRefresh: String get() = prefs.getString("simkl_refresh", "") ?: ""
    val simklExpiresAt: Long get() = prefs.getLong("simkl_expires", 0L)
    fun setSimklRefresh(refresh: String, expiresAt: Long) {
        prefs.edit().putString("simkl_refresh", refresh).putLong("simkl_expires", expiresAt).apply()
    }
    private val _simklLastSync = MutableStateFlow(prefs.getLong("simkl_last_sync", 0L))
    val simklLastSync: StateFlow<Long> = _simklLastSync
    fun setSimklLastSync(v: Long) { _simklLastSync.value = v; prefs.edit().putLong("simkl_last_sync", v).apply() }

    // ---- layout / look
    /** Posters visible per row; 0 = automatic. */
    private val _postersPerRow = MutableStateFlow(prefs.getInt("posters_per_row", 0))
    val postersPerRow: StateFlow<Int> = _postersPerRow
    fun setPostersPerRow(v: Int) { _postersPerRow.value = v; prefs.edit().putInt("posters_per_row", v).apply() }

    private val _appIcon = MutableStateFlow(prefs.getString("app_icon", "classic") ?: "classic")
    val appIcon: StateFlow<String> = _appIcon
    fun setAppIcon(v: String) { _appIcon.value = v; prefs.edit().putString("app_icon", v).apply() }

    // ---- player timing
    /** Seconds before the player controls hide on their own. */
    private val _controlsTimeout = MutableStateFlow(prefs.getInt("controls_timeout", 5))
    val controlsTimeout: StateFlow<Int> = _controlsTimeout
    fun setControlsTimeout(v: Int) { _controlsTimeout.value = v; prefs.edit().putInt("controls_timeout", v).apply() }

    /** Seconds paused before the info overlay appears; 0 = never. */
    private val _pauseOverlay = MutableStateFlow(prefs.getInt("pause_overlay", 5))
    val pauseOverlay: StateFlow<Int> = _pauseOverlay
    fun setPauseOverlay(v: Int) { _pauseOverlay.value = v; prefs.edit().putInt("pause_overlay", v).apply() }

    // ---- extras
    private val _autoSkipIntro = MutableStateFlow(prefs.getBoolean("auto_skip_intro", false))
    val autoSkipIntro: StateFlow<Boolean> = _autoSkipIntro
    fun setAutoSkipIntro(v: Boolean) { _autoSkipIntro.value = v; prefs.edit().putBoolean("auto_skip_intro", v).apply() }

    private val _bgTrailers = MutableStateFlow(prefs.getBoolean("bg_trailers", true))
    val bgTrailers: StateFlow<Boolean> = _bgTrailers
    fun setBgTrailers(v: Boolean) { _bgTrailers.value = v; prefs.edit().putBoolean("bg_trailers", v).apply() }

    /** Minutes without input before the screensaver; 0 = off. */
    private val _screensaver = MutableStateFlow(prefs.getInt("screensaver", 5))
    val screensaver: StateFlow<Int> = _screensaver
    fun setScreensaver(v: Int) { _screensaver.value = v; prefs.edit().putInt("screensaver", v).apply() }

    private val _nightMode = MutableStateFlow(prefs.getBoolean("night_mode", false))
    val nightMode: StateFlow<Boolean> = _nightMode
    fun setNightMode(v: Boolean) { _nightMode.value = v; prefs.edit().putBoolean("night_mode", v).apply() }

    /** Raw access for small feature stores (track memory, caches). */
    fun store(name: String) = appCtx.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val appCtx = ctx.applicationContext

    /** Everything in the main settings file, for backups. */
    fun exportAll(): Map<String, Any?> = prefs.all.filterKeys { it != "simkl_token" && it != "simkl_refresh" && it != "simkl_expires" }
    fun importAll(m: Map<String, Any?>) {
        val e = prefs.edit()
        m.forEach { (k, v) ->
            when (v) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v)
                is String -> e.putString(k, v)
            }
        }
        e.apply()
    }

    private val _tmdbKey = MutableStateFlow(prefs.getString("tmdb_key", "") ?: "")
    val tmdbKey: StateFlow<String> = _tmdbKey
    fun setTmdbKey(v: String) { _tmdbKey.value = v.trim(); prefs.edit().putString("tmdb_key", v.trim()).apply() }

    /** "auto" | "exo" | "vlc" */
    private val _engine = MutableStateFlow(prefs.getString("engine", "auto") ?: "auto")
    val engine: StateFlow<String> = _engine
    fun setEngine(v: String) { _engine.value = v; prefs.edit().putString("engine", v).apply() }

    private val _autoNext = MutableStateFlow(prefs.getBoolean("auto_next", true))
    val autoNext: StateFlow<Boolean> = _autoNext
    fun setAutoNext(v: Boolean) { _autoNext.value = v; prefs.edit().putBoolean("auto_next", v).apply() }

    private val _subsDefault = MutableStateFlow(prefs.getBoolean("subs_default", true))
    val subsDefault: StateFlow<Boolean> = _subsDefault
    fun setSubsDefault(v: Boolean) { _subsDefault.value = v; prefs.edit().putBoolean("subs_default", v).apply() }

    private val _lastSync = MutableStateFlow(prefs.getLong("last_sync", 0L))
    val lastSync: StateFlow<Long> = _lastSync
    fun setLastSync(v: Long) { _lastSync.value = v; prefs.edit().putLong("last_sync", v).apply() }

    private val sourceSer = ListSerializer(Source.serializer())
    private val _sources = MutableStateFlow(loadSources())
    val sources: StateFlow<List<Source>> = _sources
    fun setSources(v: List<Source>) {
        _sources.value = v
        prefs.edit().putString("sources", json.encodeToString(sourceSer, v)).apply()
    }
    fun resetSources() = setSources(Defaults.sources)

    /** Custom names for home rows (row key -> name). Empty = built-in name. */
    private val _rowNames = MutableStateFlow(
        (prefs.getString("row_names", "") ?: "").split('\n').mapNotNull { l ->
            val i = l.indexOf('\t'); if (i <= 0) null else l.substring(0, i) to l.substring(i + 1)
        }.toMap()
    )
    val rowNames: StateFlow<Map<String, String>> = _rowNames
    fun setRowName(key: String, name: String?) {
        val m = _rowNames.value.toMutableMap()
        if (name.isNullOrBlank()) m.remove(key) else m[key] = name.trim().replace('\n', ' ').replace('\t', ' ')
        _rowNames.value = m
        prefs.edit().putString("row_names", m.entries.joinToString("\n") { "${it.key}\t${it.value}" }).apply()
    }

    private fun loadSources(): List<Source> {
        val s = prefs.getString("sources", null) ?: return Defaults.sources
        val saved = runCatching { json.decodeFromString(sourceSer, s) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return Defaults.sources
        // RongDhong arrived after the source list was saved: add its libraries once
        if (!prefs.getBoolean("rd_sources_added", false)) {
            prefs.edit().putBoolean("rd_sources_added", true).apply()
            if (saved.none { Defaults.isRongDhong(it.id) }) {
                val merged = saved + Defaults.rongDhong
                prefs.edit().putString("sources", json.encodeToString(sourceSer, merged)).apply()
                return merged
            }
        }
        return saved.filterNot { it.id == "rd-1" } // RongDhong 3D: never used
    }

    private val rowSer = ListSerializer(HomeRow.serializer())
    private val _homeRows = MutableStateFlow(loadRows())
    val homeRows: StateFlow<List<HomeRow>> = _homeRows
    fun setHomeRows(v: List<HomeRow>) {
        _homeRows.value = v
        prefs.edit().putString("home_rows", json.encodeToString(rowSer, v)).apply()
    }

    private fun loadRows(): List<HomeRow> {
        val s = prefs.getString("home_rows", null) ?: return Defaults.homeRows
        val saved = runCatching { json.decodeFromString(rowSer, s) }.getOrNull() ?: return Defaults.homeRows
        // add any newly introduced default rows at the end
        val keys = saved.map { it.key }.toSet()
        // rows added in an update: the discovery rows go in switched on, near their default spot
        val out = saved.toMutableList()
        Defaults.homeRows.forEachIndexed { i, r ->
            if (r.key in keys) return@forEachIndexed
            if (r.key in setOf(RowKeys.NEW_EPISODES, RowKeys.TOP10, RowKeys.BECAUSE)) out.add(i.coerceAtMost(out.size), r)
            else out.add(r.copy(enabled = false))
        }
        return out
    }
}
