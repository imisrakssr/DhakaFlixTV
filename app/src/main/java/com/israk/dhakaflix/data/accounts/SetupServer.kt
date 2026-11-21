package com.israk.dhakaflix.data.accounts

import com.israk.dhakaflix.DhakaFlixApp
import com.israk.dhakaflix.data.DiagLog
import com.israk.dhakaflix.data.Diagnostics
import com.israk.dhakaflix.data.db.ProgressEntity
import com.israk.dhakaflix.data.db.UserStateEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * A tiny web page served by the TV on the local Wi-Fi. The TV shows a QR code with its address;
 * the phone opens it and types API keys / restores backups there instead of on the TV remote.
 * Only runs while the setup screen is open and every path needs a random per-session token.
 */
object SetupServer {
    sealed interface Event {
        data class Saved(val what: List<String>) : Event
        data object Restored : Event
    }

    private var server: ServerSocket? = null
    private var job: Job? = null
    private var users = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val _address = MutableStateFlow<String?>(null)
    /** http://ip:port/token while running, else null. */
    val address: StateFlow<String?> = _address
    private val _event = MutableStateFlow<Event?>(null)
    val event: StateFlow<Event?> = _event
    fun consumeEvent() { _event.value = null }

    private var token = ""

    @Synchronized fun acquire() {
        users++
        if (server != null) return
        val ip = Diagnostics.localIp() ?: return
        token = (1..6).map { "abcdefghjkmnpqrstuvwxyz23456789"[SecureRandom().nextInt(31)] }.joinToString("")
        val ss = (8765..8775).firstNotNullOfOrNull { port ->
            runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) } }.getOrNull()
        } ?: return
        server = ss
        _address.value = "http://$ip:${ss.localPort}/$token"
        job = scope.launch {
            while (isActive) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                launch { runCatching { s.use { handle(it) } } }
            }
        }
    }

    @Synchronized fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0) return
        job?.cancel(); job = null
        runCatching { server?.close() }
        server = null
        _address.value = null
    }

    // ------------------------------------------------------------------ http

    private class Req(val method: String, val path: String, val body: ByteArray)

    private fun read(s: Socket): Req? {
        s.soTimeout = 15_000
        val inp = BufferedInputStream(s.getInputStream())
        fun line(): String? {
            val b = ByteArrayOutputStream()
            while (true) {
                val c = inp.read()
                if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8")
                if (c == '\n'.code) return b.toString("UTF-8").trimEnd('\r')
                b.write(c)
                if (b.size() > 8192) return null
            }
        }
        val first = line() ?: return null
        val parts = first.split(' ')
        if (parts.size < 2) return null
        var len = 0
        while (true) {
            val h = line() ?: break
            if (h.isEmpty()) break
            if (h.startsWith("content-length:", ignoreCase = true)) len = h.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        if (len > 4_000_000) return null
        val body = ByteArray(len)
        var off = 0
        while (off < len) { val n = inp.read(body, off, len - off); if (n < 0) break; off += n }
        return Req(parts[0], parts[1].substringBefore('?'), body)
    }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray, extra: String = "") {
        val status = when (code) { 200 -> "OK"; 404 -> "Not Found"; 400 -> "Bad Request"; else -> "OK" }
        out.write(("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n$extra\r\n").toByteArray())
        out.write(body)
        out.flush()
    }

    private fun handle(s: Socket) {
        val r = read(s) ?: return
        val out = s.getOutputStream()
        val base = "/$token"
        if (r.path != base && !r.path.startsWith("$base/")) { send(out, 404, "text/plain", "Not found".toByteArray()); return }
        val app = DhakaFlixApp.instance
        when (r.path.removePrefix(base)) {
            "", "/" -> send(out, 200, "text/html; charset=utf-8", page(null).toByteArray())
            "/save" -> {
                val form = parseForm(String(r.body, Charsets.UTF_8))
                val saved = mutableListOf<String>()
                form["tmdb"]?.takeIf { it.isNotBlank() }?.let { app.settings.setTmdbKey(it); saved += "TMDB" }
                form["mdblist"]?.takeIf { it.isNotBlank() }?.let { app.settings.setMdblistKey(it); saved += "MDBList" }
                form["simkl"]?.takeIf { it.isNotBlank() }?.let { app.settings.setSimklClientId(it); saved += "Simkl" }
                if (saved.isNotEmpty()) _event.value = Event.Saved(saved)
                send(out, 200, "text/html; charset=utf-8",
                    page(if (saved.isEmpty()) "Nothing to save — fill at least one box." else "Saved on your TV: ${saved.joinToString()} ✓").toByteArray())
            }
            "/backup" -> send(
                out, 200, "application/json; charset=utf-8", backup().toByteArray(),
                "Content-Disposition: attachment; filename=\"dhakaflix-backup.json\"\r\n",
            )
            "/restore" -> {
                val ok = runCatching { restore(String(r.body, Charsets.UTF_8)) }.onFailure { DiagLog.add("Backup", it.message ?: "restore failed") }.isSuccess
                if (ok) _event.value = Event.Restored
                send(out, if (ok) 200 else 400, "text/plain; charset=utf-8",
                    (if (ok) "Restored ✓ — the TV app restarts now." else "That file doesn't look like a DhakaFlix backup.").toByteArray())
            }
            "/diag" -> {
                val txt = runBlocking { Diagnostics.run(app) }.asText()
                send(out, 200, "text/plain; charset=utf-8", txt.toByteArray())
            }
            else -> send(out, 404, "text/plain", "Not found".toByteArray())
        }
    }

    private fun parseForm(s: String): Map<String, String> = s.split('&').mapNotNull {
        val k = it.substringBefore('=', "")
        if (k.isEmpty()) null else URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8").trim()
    }.toMap()

    // ------------------------------------------------------------------ backup

    private fun backup(): String {
        val app = DhakaFlixApp.instance
        val users = runBlocking { app.db.user().all() }
        val progress = runBlocking { app.db.progress().all() }
        return buildJsonObject {
            put("app", "dhakaflix"); put("version", 1); put("createdAt", System.currentTimeMillis())
            putJsonArray("settings") {
                app.settings.exportAll().forEach { (k, v) ->
                    val t = when (v) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is String -> "s"; else -> null } ?: return@forEach
                    add(buildJsonObject { put("k", k); put("t", t); put("v", v.toString()) })
                }
            }
            putJsonArray("user") {
                users.forEach { u ->
                    add(buildJsonObject {
                        put("id", u.itemId); put("wl", u.inWatchlist); put("wlAt", u.watchlistAt); put("w", u.watched); put("wAt", u.watchedAt)
                    })
                }
            }
            putJsonArray("progress") {
                progress.forEach { p ->
                    add(buildJsonObject {
                        put("url", p.fileUrl); put("id", p.itemId); put("label", p.label); put("s", p.season); put("e", p.episode)
                        put("pos", p.positionMs); put("dur", p.durationMs); put("at", p.updatedAt); put("done", p.completed)
                    })
                }
            }
        }.toString()
    }

    private fun restore(text: String) {
        val app = DhakaFlixApp.instance
        val root = json.parseToJsonElement(text) as JsonObject
        require((root["app"] as? JsonPrimitive)?.contentOrNull == "dhakaflix")
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
        fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
        fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull == true
        val settings = (root["settings"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val k = o.s("k") ?: return@mapNotNull null
            val v = o.s("v") ?: return@mapNotNull null
            k to when (o.s("t")) {
                "b" -> v.toBoolean(); "i" -> v.toIntOrNull(); "l" -> v.toLongOrNull(); "f" -> v.toFloatOrNull(); else -> v
            }
        }.toMap()
        runBlocking {
            (root["user"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { o ->
                val id = o.s("id") ?: return@forEach
                app.db.user().put(UserStateEntity(id, o.b("wl"), o.l("wlAt") ?: 0, o.b("w"), o.l("wAt") ?: 0))
            }
            (root["progress"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { o ->
                val url = o.s("url") ?: return@forEach
                val at = o.l("at") ?: 0
                val local = app.db.progress().get(url)
                if (local != null && local.updatedAt >= at) return@forEach
                app.db.progress().put(
                    ProgressEntity(
                        fileUrl = url, itemId = o.s("id") ?: return@forEach, label = o.s("label"),
                        season = o.l("s")?.toInt(), episode = o.l("e")?.toInt(),
                        positionMs = o.l("pos") ?: 0, durationMs = o.l("dur") ?: 0, updatedAt = at, completed = o.b("done"),
                    )
                )
            }
        }
        app.settings.importAll(settings)
    }

    // ------------------------------------------------------------------ page

    private fun page(msg: String?): String {
        val st = DhakaFlixApp.instance.settings
        fun state(v: String) = if (v.isBlank()) "not set" else "saved ••••" + v.takeLast(4)
        val note = msg?.let { "<div class=note>${esc(it)}</div>" } ?: ""
        return """<!doctype html><html><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>DhakaFlix TV setup</title><style>
*{box-sizing:border-box}body{margin:0;font:15px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:#07090f;color:#e8ecf4}
.wrap{max-width:560px;margin:0 auto;padding:22px 16px 60px}h1{font-size:22px;margin:4px 0 2px}h1 b{background:linear-gradient(90deg,#1e6eff,#22deee);-webkit-background-clip:text;color:transparent}
.sub{color:#8a93a6;margin:0 0 18px}.card{background:#121621;border:1px solid #1f2533;border-radius:16px;padding:16px;margin:14px 0}
h2{font-size:16px;margin:0 0 10px}label{display:block;font-weight:600;margin:12px 0 4px}small{color:#8a93a6;display:block;margin-top:3px}
input[type=text]{width:100%;padding:12px;border-radius:10px;border:1px solid #2a3142;background:#0b0e16;color:#fff;font-size:15px}
button,.btn{display:inline-block;margin-top:14px;padding:12px 18px;border:0;border-radius:999px;background:#1e6eff;color:#fff;font-weight:700;font-size:15px;text-decoration:none}
.ghost{background:#1f2533}.note{background:#11331f;border:1px solid #1f6b3a;color:#b6f5c9;padding:12px;border-radius:12px;margin:10px 0}
a{color:#7fb2ff}ol{padding-left:20px;margin:6px 0;color:#b8c0d0}code{background:#0b0e16;padding:1px 5px;border-radius:5px}
</style></head><body><div class=wrap>
<h1><b>DhakaFlix</b> TV setup</h1><p class=sub>Whatever you save here goes straight to your TV.</p>$note
<form class=card method=post action="/$token/save"><h2>Accounts</h2>
<label>TMDB API key</label><input type=text name=tmdb autocomplete=off placeholder="${state(st.tmdbKey.value)}">
<small>Posters &amp; info. Free at <a href="https://www.themoviedb.org/settings/api">themoviedb.org/settings/api</a></small>
<label>MDBList API key</label><input type=text name=mdblist autocomplete=off placeholder="${state(st.mdblistKey.value)}">
<small>IMDb / Rotten Tomatoes / Metacritic ratings. Free at <a href="https://mdblist.com/preferences/">mdblist.com/preferences</a></small>
<label>Simkl Client ID</label><input type=text name=simkl autocomplete=off placeholder="${state(st.simklClientId.value)}">
<small>Sync across devices. One-time: <ol><li>Open <a href="https://simkl.com/settings/developer/new/">simkl.com/settings/developer/new</a></li>
<li>Name: <code>DhakaFlix TV</code>, Redirect URI: <code>urn:ietf:wg:oauth:2.0:oob</code></li><li>Copy the <b>Client ID</b> here, then press <b>Sign in</b> on the TV.</li></ol></small>
<button type=submit>Save to TV</button></form>
<div class=card><h2>Backup &amp; restore</h2><p class=sub style="margin:0">Settings, watchlist, history and progress.</p>
<a class=btn href="/$token/backup">Download backup</a>
<label>Restore on this TV</label><input type=file id=f accept=".json,application/json">
<button class="ghost" onclick="up()">Restore</button><div id=r></div></div>
<div class=card><h2>Diagnostics</h2><a class="btn ghost" href="/$token/diag">View report</a></div>
<p class=sub>Made ❤️ by Israk Sisir</p></div>
<script>function up(){var f=document.getElementById('f').files[0];if(!f)return;f.text().then(function(t){return fetch('/$token/restore',{method:'POST',body:t})}).then(function(r){return r.text()}).then(function(t){document.getElementById('r').innerHTML='<div class=note>'+t+'</div>'})}</script>
</body></html>"""
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
