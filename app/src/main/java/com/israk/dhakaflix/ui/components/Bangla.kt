package com.israk.dhakaflix.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.israk.dhakaflix.DhakaFlixApp
import com.israk.dhakaflix.data.db.ItemEntity
import java.util.Locale

/**
 * Dhakaiya mode helpers: Bengali digits, dates, runtimes, genres, and the title/plot of a movie or
 * series in Bengali (from TMDB translations). Anything without a translation stays in English.
 */
object Bangla {
    private const val DIGITS = "০১২৩৪৫৬৭৮৯"

    fun digits(s: String): String = buildString(s.length) {
        s.forEach { c -> append(if (c in '0'..'9') DIGITS[c - '0'] else c) }
    }

    val locale: Locale = Locale.forLanguageTag("bn-BD")

    fun runtime(min: Int): String {
        val h = min / 60
        val m = min % 60
        return digits(if (h > 0) "$h ঘণ্টা $m মিনিট" else "$m মিনিট")
    }

    private val genres = mapOf(
        "Action" to "অ্যাকশন", "Adventure" to "অ্যাডভেঞ্চার", "Animation" to "অ্যানিমেশন", "Comedy" to "কমেডি",
        "Crime" to "ক্রাইম", "Documentary" to "ডকুমেন্টারি", "Drama" to "ড্রামা", "Family" to "পরিবার",
        "Fantasy" to "ফ্যান্টাসি", "History" to "ইতিহাস", "Horror" to "হরর", "Music" to "মিউজিক",
        "Mystery" to "রহস্য", "Romance" to "রোমান্স", "Science Fiction" to "সায়েন্স ফিকশন", "Sci-Fi" to "সায়েন্স ফিকশন",
        "Thriller" to "থ্রিলার", "War" to "যুদ্ধ", "Western" to "ওয়েস্টার্ন", "Kids" to "শিশু", "Reality" to "রিয়েলিটি",
        "Talk" to "টক শো", "News" to "খবর", "Soap" to "সোপ", "TV Movie" to "টিভি মুভি",
        "Action & Adventure" to "অ্যাকশন ও অ্যাডভেঞ্চার", "Sci-Fi & Fantasy" to "সায়েন্স ফিকশন ও ফ্যান্টাসি",
        "War & Politics" to "যুদ্ধ ও রাজনীতি",
    )

    fun genre(name: String): String = genres[name] ?: name

    fun partOfDay(hour: Int): String = when (hour) {
        in 4..11 -> "সকাল"; in 12..15 -> "দুপুর"; in 16..17 -> "বিকাল"; in 18..19 -> "সন্ধ্যা"; else -> "রাত"
    }

    /** "বিকাল ৪:১৫" in Dhakaiya, "4:15 PM" otherwise. */
    fun clock(time: java.util.Date, bn: Boolean): String {
        val hm = java.text.SimpleDateFormat("h:mm", Locale.US).format(time)
        if (!bn) return hm + " " + java.text.SimpleDateFormat("a", Locale.US).format(time)
        val h = java.util.Calendar.getInstance().apply { this.time = time }.get(java.util.Calendar.HOUR_OF_DAY)
        return partOfDay(h) + " " + digits(hm)
    }

    /** "২২ এ নভেম্বর, রবিবার" in Dhakaiya, "Sunday, 22 November" otherwise. */
    fun date(time: java.util.Date, bn: Boolean): String =
        if (bn) digits(java.text.SimpleDateFormat("d", Locale.US).format(time)) + " এ " +
            java.text.SimpleDateFormat("MMMM", locale).format(time) + ", " + java.text.SimpleDateFormat("EEEE", locale).format(time)
        else java.text.SimpleDateFormat("EEEE, d MMMM", Locale.US).format(time)
}

/** True while the app language is Dhakaiya. */
@Composable
fun isDhakaiya(): Boolean {
    val lang by DhakaFlixApp.instance.settings.language.collectAsState()
    return lang == "dhk"
}

/** Plot in the app language: Bengali when TMDB has a translation, otherwise English. */
@Composable
fun localOverview(item: ItemEntity): String? {
    val bn = isDhakaiya()
    val t by produceState(item.overview, item.id, item.overview, bn) {
        value = if (bn) DhakaFlixApp.instance.library.discovery.bangla(item).overview ?: item.overview else item.overview
    }
    return t
}
