package com.israk.dhakaflix.data

/**
 * Parses DhakaFlix folder / file names such as
 *   "Ace Ventura-When Nature Calls (1995) 720p [Dual Audio]"
 *   "A Discovery of Witches (TV Series 2018–2022) 1080p"
 *   "A.Discovery.of.Witches.S01E03.1080p.10bit.BrRip.6CH.x265.HEVC-PSA.mkv"
 */
object NameParser {

    data class Parsed(
        val title: String,
        val year: Int?,
        val quality: String?,
        val tags: List<String>,
    )

    data class EpisodeInfo(val season: Int?, val episode: Int?)

    private val movieRe = Regex("""^(.*?)\s*\((\d{4})\)(.*)$""")
    // "(TV Series 2018–2022)", "(TV Mini Series 2024)", "(TV Reality-Show 2011– )", "(2019)" …
    private val seriesRe = Regex("""^(.*?)\s*\(\s*[^)\d]*?(\d{4})[^)]*\)(.*)$""")
    private val qualityRe = Regex("""(?i)\b(2160p|4k|1080p|720p|576p|480p|360p)\b""")
    private val bracketRe = Regex("""\[([^\]]+)]""")
    private val knownTags = listOf("AMZN", "NF", "DSNP", "HMAX", "WEBRip", "WEB-DL", "BluRay", "BrRip", "HDRip", "HEVC", "x265", "10bit", "HDR")

    private val sxe = Regex("""(?i)S(\d{1,2})[\s._-]*E(\d{1,3})""")
    private val nxn = Regex("""(?i)(?<![0-9])(\d{1,2})x(\d{2,3})(?![0-9])""")
    private val epOnly = Regex("""(?i)(?:\bEp(?:isode)?|\bE)[\s._-]*(\d{1,3})(?![0-9])""")
    private val seasonRe = Regex("""(?i)\bSeason[\s._-]*(\d{1,2})|\bS(\d{1,2})\b""")

    val videoExt = setOf("mkv", "mp4", "avi", "m4v", "mov", "ts", "m2ts", "webm", "wmv", "flv", "mpg", "mpeg", "vob", "3gp", "divx")
    val subExt = setOf("srt", "vtt", "ass", "ssa")
    val imageExt = setOf("jpg", "jpeg", "png", "webp")

    fun ext(name: String): String = name.substringAfterLast('.', "").lowercase()

    /** Returns null when the folder is a container (year folder, A–L bucket, collection…). */
    fun parseFolder(raw: String, type: MediaType): Parsed? {
        val name = raw.trim()
        val m = (if (type == MediaType.SERIES) seriesRe else movieRe).find(name) ?: return null
        val title = cleanTitle(m.groupValues[1])
        if (title.isBlank()) return null
        val year = m.groupValues[2].toIntOrNull()?.takeIf { it in 1880..2100 }
        val rest = m.groupValues[3]
        return Parsed(title, year, quality(rest), tags(rest))
    }

    fun quality(s: String): String? = qualityRe.find(s)?.value?.lowercase()?.let { if (it == "4k") "2160p" else it }

    fun tags(s: String): List<String> {
        val out = LinkedHashSet<String>()
        bracketRe.findAll(s).forEach { out += it.groupValues[1].trim() }
        knownTags.forEach { if (Regex("(?i)\\b${Regex.escape(it)}\\b").containsMatchIn(s)) out += it }
        return out.toList()
    }

    fun cleanTitle(t: String): String =
        t.replace('_', ' ').replace(Regex("""\s+"""), " ").trim().trimEnd('-', '.', ' ')

    /** Query string for TMDB: DhakaFlix uses "-" where titles have ":". */
    fun searchQuery(title: String): String =
        title.replace(Regex("""\s*-\s*"""), " ").replace(Regex("""\s+"""), " ").trim()

    fun episode(fileName: String): EpisodeInfo {
        val base = fileName.substringBeforeLast('.')
        sxe.find(base)?.let { return EpisodeInfo(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        nxn.find(base)?.let { return EpisodeInfo(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        epOnly.find(base)?.let { return EpisodeInfo(null, it.groupValues[1].toInt()) }
        return EpisodeInfo(null, null)
    }

    fun seasonNumber(folderName: String): Int? {
        val m = seasonRe.find(folderName) ?: return null
        return (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull()
    }

    /** Normalised title used to compare names. */
    fun norm(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]"), "")
}
