package com.israk.dhakaflix.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class AppSettings(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("dhakaflix_settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _accent = MutableStateFlow(prefs.getInt("accent", 0))
    val accent: StateFlow<Int> = _accent
    fun setAccent(v: Int) { _accent.value = v; prefs.edit().putInt("accent", v).apply() }

    /** "en", "bn" or "dhk" (Dhakaiya). */
    private val _language = MutableStateFlow(prefs.getString("language", "en") ?: "en")
    val language: StateFlow<String> = _language
    fun setLanguage(v: String) { _language.value = v; prefs.edit().putString("language", v).apply() }

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

    private fun loadSources(): List<Source> {
        val s = prefs.getString("sources", null) ?: return Defaults.sources
        return runCatching { json.decodeFromString(sourceSer, s) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: Defaults.sources
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
        return saved + Defaults.homeRows.filter { it.key !in keys }.map { it.copy(enabled = false) }
    }
}
