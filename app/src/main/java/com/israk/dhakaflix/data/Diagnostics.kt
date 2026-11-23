package com.israk.dhakaflix.data

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.israk.dhakaflix.BuildConfig
import com.israk.dhakaflix.DhakaFlixApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Small persistent log of things that went wrong (player errors, failed servers, sync problems). */
object DiagLog {
    data class Entry(val at: Long, val tag: String, val msg: String)

    private const val MAX = 40
    private val list = ArrayDeque<Entry>()
    private var loaded = false

    private fun prefs() = DhakaFlixApp.instance.settings.store("diag_log")

    @Synchronized fun add(tag: String, msg: String) {
        load()
        list.addFirst(Entry(System.currentTimeMillis(), tag, msg.take(300)))
        while (list.size > MAX) list.removeLast()
        runCatching {
            prefs().edit().putString("log", list.joinToString("\n") { "${it.at}\t${it.tag}\t${it.msg.replace('\n', ' ')}" }).apply()
        }
    }

    @Synchronized fun entries(): List<Entry> { load(); return list.toList() }

    @Synchronized fun clear() { list.clear(); runCatching { prefs().edit().clear().apply() } }

    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            prefs().getString("log", "")!!.split('\n').filter { it.isNotBlank() }.forEach { line ->
                val p = line.split('\t', limit = 3)
                if (p.size == 3) list.addLast(Entry(p[0].toLongOrNull() ?: 0, p[1], p[2]))
            }
        }
    }
}

/** Everything the Diagnostics page shows, collected once per "Run again". */
data class DiagReport(
    val device: List<Pair<String, String>>,
    val network: List<Pair<String, String>>,
    val servers: List<Triple<String, Boolean, String>>,
    val codecs: List<Pair<String, String>>,
    val library: List<Pair<String, String>>,
) {
    fun asText(): String = buildString {
        appendLine("DhakaFlix TV diagnostics — " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
        fun sec(t: String, l: List<Pair<String, String>>) { appendLine("\n== $t"); l.forEach { appendLine("${it.first}: ${it.second}") } }
        sec("Device", device); sec("Network", network)
        appendLine("\n== Servers"); servers.forEach { appendLine((if (it.second) "OK   " else "FAIL ") + it.first + "  " + it.third) }
        sec("Codecs", codecs); sec("Library", library)
        appendLine("\n== Recent errors")
        DiagLog.entries().forEach { appendLine(SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(it.at)) + "  [" + it.tag + "] " + it.msg) }
    }
}

object Diagnostics {
    suspend fun run(ctx: Context): DiagReport = withContext(Dispatchers.IO) {
        val app = DhakaFlixApp.instance
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val dm = ctx.resources.displayMetrics
        val device = listOf(
            "App" to "DhakaFlix TV ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "Model" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "CPU ABI" to Build.SUPPORTED_ABIS.joinToString(),
            "RAM" to "${mem.availMem / 1_048_576} MB free of ${mem.totalMem / 1_048_576} MB" + if (mem.lowMemory) " — LOW" else "",
            "App memory limit" to "${am.memoryClass} MB (large ${am.largeMemoryClass} MB)",
            "Screen" to "${dm.widthPixels}×${dm.heightPixels} @ ${dm.densityDpi} dpi",
        )
        val network = listOf("IP address" to (localIp() ?: "not connected"))

        val hosts = app.settings.sources.value.filter { it.enabled }
            .map { android.net.Uri.parse(it.url).let { u -> "${u.scheme}://${u.host}/" } }.distinct()
        val quick = app.http.newBuilder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()
        val servers = coroutineScope {
            hosts.map { h ->
                async {
                    val t0 = System.nanoTime()
                    runCatching {
                        quick.newCall(Request.Builder().url(h).head().build()).execute().use { r ->
                            val ms = (System.nanoTime() - t0) / 1_000_000
                            Triple(h, r.code < 500, "HTTP ${r.code} · $ms ms")
                        }
                    }.getOrElse { Triple(h, false, it.javaClass.simpleName + (it.message?.let { m -> ": $m" } ?: "")) }
                }
            }.awaitAll()
        }

        val codecs = codecSupport()

        val repo = app.library
        val db = app.db
        val cacheMb = dirSize(ctx.cacheDir.resolve("images")) / 1_048_576
        val library = listOf(
            "Titles" to "${db.items().countForAllSources()}",
            "Waiting for metadata" to "${db.items().pendingNow()}",
            "Watchlist" to "${db.user().watchlistCount()}",
            "Database" to "${ctx.getDatabasePath("dhakaflix.db").length() / 1024} KB",
            "Image cache" to "$cacheMb MB",
            "TMDB" to repo.tmdbStatus.value.name,
            "MDBList" to repo.mdblist.status.value.name,
            "Simkl" to if (repo.simkl.signedIn) "signed in (${app.settings.simklUser.value})" else if (repo.simkl.hasClient) "not signed in" else "no client id",
            "Last library update" to if (app.settings.lastSync.value == 0L) "never" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(app.settings.lastSync.value)),
        )
        DiagReport(device, network, servers, codecs, library)
    }

    private fun codecSupport(): List<Pair<String, String>> {
        val infos = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder } }.getOrDefault(emptyList())
        fun video(name: String, mime: String): Pair<String, String> {
            val hw = infos.filter { i -> i.supportedTypes.any { it.equals(mime, true) } }
            if (hw.isEmpty()) return name to "✗ not supported"
            val best = hw.maxOf { i ->
                runCatching {
                    i.getCapabilitiesForType(mime).videoCapabilities?.supportedHeights?.upper ?: 0
                }.getOrDefault(0)
            }
            val hardware = hw.any { isHardware(it) }
            return name to "✓ up to ${best}p" + if (hardware) " (hardware)" else " (software)"
        }
        fun audio(name: String, mime: String) = name to if (infos.any { i -> i.supportedTypes.any { it.equals(mime, true) } }) "✓ decoder" else "via FFmpeg"
        return listOf(
            video("H.264", MediaFormat.MIMETYPE_VIDEO_AVC),
            video("H.265 / HEVC", MediaFormat.MIMETYPE_VIDEO_HEVC),
            video("VP9", MediaFormat.MIMETYPE_VIDEO_VP9),
            video("AV1", "video/av01"),
            audio("AC3", "audio/ac3"), audio("E-AC3", "audio/eac3"), audio("DTS", "audio/vnd.dts"),
        )
    }

    private fun isHardware(i: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) i.isHardwareAccelerated
        else !i.name.startsWith("OMX.google.") && !i.name.startsWith("c2.android.")

    private fun dirSize(f: java.io.File): Long = if (!f.exists()) 0 else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress }
            .firstOrNull { it != null && !it.startsWith("169.254") }
    }.getOrNull()
}
