package gr.alerts.kindynos

import android.text.Html
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Πηγές δεδομένων */
object Sources {
    /** Προειδοποιήσεις ΕΜΥ για την Ελλάδα, όπως δημοσιεύονται στο Meteoalarm (EUMETNET). */
    const val METEOALARM_GR = "https://feeds.meteoalarm.org/feeds/meteoalarm-legacy-atom-greece"

    /** Γενική Γραμματεία Πολιτικής Προστασίας – RSS */
    const val CP_PRESS = "https://civilprotection.gov.gr/deltia-tupou.rss"
    const val CP_PLANS = "https://civilprotection.gov.gr/shedia-politikis-prostasias.rss"
    const val CP_FIRE_MAPS = "https://civilprotection.gov.gr/cp_hartes.rss"

    const val EMY_SITE = "https://www.emy.gr/"
}

/** Μία ειδοποίηση, από οποιαδήποτε πηγή */
data class Alert(
    val id: String,
    val source: String,      // "ΕΜΥ" ή "Πολιτική Προστασία"
    val level: Int,          // 0 = ενημέρωση, 1 = κίτρινη, 2 = πορτοκαλί, 3 = κόκκινη
    val title: String,
    val text: String,
    val link: String,
    val time: String
)

/** Κανονικοποίηση ελληνικού κειμένου: πεζά, χωρίς τόνους, σ αντί για ς */
fun norm(s: String): String =
    Normalizer.normalize(s.lowercase(Locale("el")), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace('ς', 'σ')

fun stripHtml(s: String): String =
    Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString()
        .replace(Regex("\\s+"), " ").trim()

private val ATHENS: ZoneId = ZoneId.of("Europe/Athens")
private val SHORT = DateTimeFormatter.ofPattern("dd/MM HH:mm")

fun fmtIso(iso: String?): String = try {
    if (iso.isNullOrBlank()) "" else OffsetDateTime.parse(iso).atZoneSameInstant(ATHENS).format(SHORT)
} catch (e: Exception) { iso ?: "" }

fun fmtRfc822(s: String?): String = try {
    if (s.isNullOrBlank()) "" else ZonedDateTime.parse(s.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
        .withZoneSameInstant(ATHENS).format(SHORT)
} catch (e: Exception) { s ?: "" }

object Http {
    fun get(url: String): InputStream {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "KindynosAlert/1.0 (Android)")
        c.setRequestProperty("Accept", "application/atom+xml, application/rss+xml, application/xml, text/xml, */*")
        if (c.responseCode !in 200..299) {
            val code = c.responseCode
            c.disconnect()
            throw RuntimeException("HTTP $code για $url")
        }
        return c.inputStream
    }
}

/** Διαβάζει όλο το κείμενο μέσα σε ένα στοιχείο, αγνοώντας εμφωλευμένα tags (π.χ. <a> μέσα σε <title>). */
private fun XmlPullParser.readInner(): String {
    val sb = StringBuilder()
    var depth = 1
    while (depth > 0) {
        when (next()) {
            XmlPullParser.START_TAG -> depth++
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.TEXT, XmlPullParser.CDSECT -> sb.append(text)
            XmlPullParser.END_DOCUMENT -> return sb.toString()
        }
    }
    return sb.toString()
}

private fun newParser(input: InputStream): XmlPullParser =
    Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false) // κρατά τα "cap:event" ως ονόματα
        setInput(input, null)
    }

data class MeteoEntry(
    val id: String,
    val event: String,
    val area: String,
    val severity: String,
    val msgType: String,
    val onset: String,
    val expires: String,
    val link: String,
    val title: String
)

object MeteoalarmParser {
    fun parse(input: InputStream): List<MeteoEntry> = input.use {
        val p = newParser(it)
        val out = mutableListOf<MeteoEntry>()
        var inEntry = false
        var f = mutableMapOf<String, String>()
        var link = ""
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG) {
                val n = p.name
                if (n == "entry") { inEntry = true; f = mutableMapOf(); link = ""; continue }
                if (!inEntry) continue
                if (n == "link") {
                    val href = p.getAttributeValue(null, "href") ?: ""
                    val type = p.getAttributeValue(null, "type") ?: ""
                    if (link.isEmpty() && href.isNotEmpty() && !type.contains("cap")) link = href
                    continue
                }
                if (n in setOf("cap:event", "cap:areaDesc", "cap:severity", "cap:message_type",
                        "cap:msgType", "cap:onset", "cap:expires", "cap:identifier", "id", "title")) {
                    f[n] = p.readInner().trim()
                }
            } else if (p.eventType == XmlPullParser.END_TAG && p.name == "entry") {
                inEntry = false
                out += MeteoEntry(
                    id = f["cap:identifier"]?.takeIf { s -> s.isNotEmpty() } ?: f["id"].orEmpty(),
                    event = f["cap:event"].orEmpty(),
                    area = f["cap:areaDesc"].orEmpty(),
                    severity = f["cap:severity"].orEmpty(),
                    msgType = f["cap:message_type"] ?: f["cap:msgType"].orEmpty(),
                    onset = f["cap:onset"].orEmpty(),
                    expires = f["cap:expires"].orEmpty(),
                    link = link,
                    title = f["title"].orEmpty()
                )
            }
        }
        out
    }

    fun level(severity: String): Int = when (severity.lowercase()) {
        "moderate" -> 1
        "severe" -> 2
        "extreme" -> 3
        else -> 0
    }
}

data class RssItem(val guid: String, val title: String, val link: String, val description: String, val pubDate: String)

object RssParser {
    fun parse(input: InputStream): List<RssItem> = input.use {
        val p = newParser(it)
        val out = mutableListOf<RssItem>()
        var inItem = false
        var f = mutableMapOf<String, String>()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG) {
                if (p.name == "item") { inItem = true; f = mutableMapOf(); continue }
                if (inItem && p.name in setOf("title", "link", "description", "pubDate", "guid")) {
                    f[p.name] = p.readInner().trim()
                }
            } else if (p.eventType == XmlPullParser.END_TAG && p.name == "item") {
                inItem = false
                val link = f["link"].orEmpty()
                out += RssItem(
                    guid = f["guid"]?.takeIf { s -> s.isNotEmpty() } ?: link,
                    title = stripHtml(f["title"].orEmpty()),
                    link = link,
                    description = stripHtml(f["description"].orEmpty()),
                    pubDate = f["pubDate"].orEmpty()
                )
            }
        }
        out
    }
}
