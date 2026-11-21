package com.israk.dhakaflix.player

import com.israk.dhakaflix.DhakaFlixApp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Remembers the audio track, subtitle and aspect picked for each file (and, as a fallback, for the
 * whole title, so the next episode of a series starts with the same language choices).
 */
object TrackMemory {
    @Serializable
    data class Choice(
        val audioId: String? = null,
        val audioLabel: String? = null,
        /** null = never chosen, "off", "ext:<url>", or an engine track id */
        val sub: String? = null,
        val subLabel: String? = null,
        val aspect: Int? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val prefs by lazy { DhakaFlixApp.instance.settings.store("track_memory") }

    fun forFile(url: String): Choice? = read("f:$url")
    fun forTitle(itemId: String): Choice? = read("t:$itemId")

    private fun read(k: String): Choice? = prefs.getString(k, null)?.let { runCatching { json.decodeFromString(Choice.serializer(), it) }.getOrNull() }

    fun update(url: String, itemId: String, change: (Choice) -> Choice) {
        val file = change(forFile(url) ?: Choice())
        // the title-level copy never keeps an external file (it belongs to one episode only)
        val title = change(forTitle(itemId) ?: Choice()).let { if (it.sub?.startsWith("ext:") == true) it.copy(sub = "ext:*") else it }
        prefs.edit()
            .putString("f:$url", json.encodeToString(Choice.serializer(), file))
            .putString("t:$itemId", json.encodeToString(Choice.serializer(), title))
            .apply()
    }
}
