package com.israk.dhakaflix.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.israk.dhakaflix.data.PlayItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.Charset

data class Cue(val start: Long, val end: Long, val text: String)

/** Tiny SRT / WebVTT parser so external subtitles can be shifted (synced) freely. */
object SubtitleParser {
    private val time = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})[,.](\d{1,3})""")
    private val arrow = Regex("""(.+?)\s*-->\s*(\S+)""")
    private val tags = Regex("""<[^>]+>|\{\\[^}]*}""")

    fun isOwnRendered(url: String): Boolean {
        val u = url.lowercase()
        return u.endsWith(".srt") || u.endsWith(".vtt")
    }

    suspend fun load(http: OkHttpClient, url: String): List<Cue> = withContext(Dispatchers.IO) {
        val bytes = http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.bytes() ?: ByteArray(0) }
        parse(decode(bytes))
    }

    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, Charsets.UTF_16BE)
        val utf8 = String(bytes, Charsets.UTF_8)
        val bad = utf8.count { it == '�' }
        return if (bad > 5) String(bytes, Charset.forName("windows-1252")) else utf8.removePrefix("﻿")
    }

    private fun ms(m: MatchResult): Long {
        val h = m.groupValues[1].toLongOrNull() ?: 0
        val mi = m.groupValues[2].toLong()
        val s = m.groupValues[3].toLong()
        val frac = m.groupValues[4].padEnd(3, '0').take(3).toLong()
        return ((h * 60 + mi) * 60 + s) * 1000 + frac
    }

    fun parse(text: String): List<Cue> {
        val out = ArrayList<Cue>()
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val a = if (line.contains("-->")) arrow.find(line) else null
            if (a != null) {
                val s = time.find(a.groupValues[1]); val e = time.find(a.groupValues[2])
                i++
                val sb = StringBuilder()
                while (i < lines.size && lines[i].isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(lines[i].replace(tags, "").trim())
                    i++
                }
                if (s != null && e != null && sb.isNotBlank()) out += Cue(ms(s), ms(e), sb.toString())
            } else i++
        }
        out.sortBy { it.start }
        return out
    }

    /** Cues visible at [pos] when subtitles are delayed by [delayMs] (positive = show later). */
    fun at(cues: List<Cue>, pos: Long, delayMs: Long): String? {
        if (cues.isEmpty()) return null
        val t = pos - delayMs
        var lo = 0; var hi = cues.lastIndex; var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].start <= t) { idx = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (idx < 0) return null
        val sb = StringBuilder()
        var j = idx
        while (j >= 0 && idx - j < 6) {
            val c = cues[j]
            if (c.start <= t && t < c.end) { if (sb.isNotEmpty()) sb.insert(0, '\n'); sb.insert(0, c.text) }
            j--
        }
        return sb.toString().ifEmpty { null }
    }
}

object ExternalPlayer {
    private const val VLC = "org.videolan.vlc"

    fun openInVlc(ctx: Context, item: PlayItem, positionMs: Long) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(item.url), "video/*")
            putExtra("title", listOfNotNull(item.title, item.subtitle).joinToString(" · "))
            if (positionMs > 0) { putExtra("position", positionMs); putExtra("from_start", false) }
            item.subs.firstOrNull()?.let { putExtra("subtitles_location", it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(Intent(intent).setPackage(VLC))
        } catch (_: ActivityNotFoundException) {
            try {
                ctx.startActivity(Intent.createChooser(intent, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                val s = com.israk.dhakaflix.ui.theme.stringsFor(com.israk.dhakaflix.DhakaFlixApp.instance.settings.language.value)
                Toast.makeText(ctx, s.vlcNotInstalled, Toast.LENGTH_LONG).show()
            }
        }
    }
}
