package com.israk.dhakaflix.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class DirEntry(
    val name: String,
    val url: String,
    val isDir: Boolean,
    val modified: Long,
    val sizeBytes: Long,
)

/** Reads h5ai (and plain Apache-style) directory listings served over HTTP. */
class H5aiClient(private val http: OkHttpClient) {

    private val dateFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Dhaka")
        }
    }

    suspend fun list(url: String): List<DirEntry> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", "DhakaFlixTV/1.0").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            parse(resp.body?.string().orEmpty(), url)
        }
    }

    fun parse(html: String, baseUrl: String): List<DirEntry> {
        val doc = Jsoup.parse(html, baseUrl)
        val rows = doc.select("#fallback table tr")
        if (rows.isNotEmpty()) {
            return rows.mapNotNull { tr ->
                val a = tr.selectFirst("td.fb-n a") ?: return@mapNotNull null
                val href = a.attr("href")
                val name = a.text().trim()
                if (href == ".." || name.equals("Parent Directory", true)) return@mapNotNull null
                val abs = a.absUrl("href").ifEmpty { return@mapNotNull null }
                val alt = tr.selectFirst("td.fb-i img")?.attr("alt").orEmpty()
                val isDir = alt.startsWith("folder") || abs.endsWith("/")
                DirEntry(
                    name = name,
                    url = abs,
                    isDir = isDir,
                    modified = parseDate(tr.selectFirst("td.fb-d")?.text().orEmpty()),
                    sizeBytes = parseSize(tr.selectFirst("td.fb-s")?.text().orEmpty()),
                )
            }
        }
        // Fallback: generic index page
        return doc.select("a[href]").mapNotNull { a ->
            val href = a.attr("href")
            if (href.startsWith("?") || href.startsWith("#") || href == "../" || href == "..") return@mapNotNull null
            val abs = a.absUrl("href")
            if (!abs.startsWith(baseUrl) || abs == baseUrl) return@mapNotNull null
            val name = a.text().trim().trimEnd('/')
            DirEntry(name, abs, abs.endsWith("/"), 0L, 0L)
        }.distinctBy { it.url }
    }

    private fun parseDate(s: String): Long = try {
        if (s.isBlank()) 0L else dateFmt.get()!!.parse(s.trim())?.time ?: 0L
    } catch (_: Exception) { 0L }

    private fun parseSize(s: String): Long {
        val m = Regex("""([\d.]+)\s*([KMGT]?B)""", RegexOption.IGNORE_CASE).find(s) ?: return 0L
        val v = m.groupValues[1].toDoubleOrNull() ?: return 0L
        val mul = when (m.groupValues[2].uppercase()) {
            "KB" -> 1024.0; "MB" -> 1024.0 * 1024; "GB" -> 1024.0 * 1024 * 1024; "TB" -> 1024.0 * 1024 * 1024 * 1024
            else -> 1.0
        }
        return (v * mul).toLong()
    }
}
