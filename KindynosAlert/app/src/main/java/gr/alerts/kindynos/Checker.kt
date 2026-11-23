package gr.alerts.kindynos

import android.content.Context
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Κάνει έναν πλήρη έλεγχο όλων των πηγών και στέλνει ειδοποιήσεις για ό,τι νέο. */
object Checker {

    data class Result(val newAlerts: Int, val errors: List<String>)

    fun run(ctx: Context): Result {
        val store = Store(ctx)
        val errors = mutableListOf<String>()
        val fresh = mutableListOf<Alert>()

        // 1) ΕΜΥ μέσω Meteoalarm
        try { fresh += checkMeteoalarm(store) } catch (e: Exception) { errors += "ΕΜΥ/Meteoalarm: ${e.message}" }

        // 2) Πολιτική Προστασία – ανακοινώσεις & σχέδια (φίλτρο λέξεων-κλειδιών)
        if (store.cpNews) {
            for (url in listOf(Sources.CP_PRESS, Sources.CP_PLANS)) {
                try { fresh += checkCpRss(store, url, fire = false) } catch (e: Exception) { errors += "Πολ. Προστασία: ${e.message}" }
            }
        }
        // 3) Πολιτική Προστασία – ημερήσιος χάρτης κινδύνου πυρκαγιάς
        if (store.cpFire) {
            try { fresh += checkCpRss(store, Sources.CP_FIRE_MAPS, fire = true) } catch (e: Exception) { errors += "Χάρτης πυρκαγιάς: ${e.message}" }
        }

        fresh.sortedByDescending { it.level }.forEach { Notifier.show(ctx, it) }
        if (fresh.size > 1) Notifier.summary(ctx, fresh.size)
        store.addHistory(fresh)
        store.markSeen(fresh.map { it.id })

        store.lastCheck = OffsetDateTime.now(ZoneId.of("Europe/Athens"))
            .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
        store.lastError = errors.joinToString("\n")
        return Result(fresh.size, errors)
    }

    private fun areaMatches(store: Store, text: String): Boolean {
        val areas = store.areaList()
        if (areas.isEmpty()) return true
        val t = norm(text)
        return areas.any { t.contains(it) }
    }

    private fun checkMeteoalarm(store: Store): List<Alert> {
        val now = OffsetDateTime.now()
        val entries = MeteoalarmParser.parse(Http.get(Sources.METEOALARM_GR))
        val out = mutableListOf<Alert>()
        for (e in entries) {
            val level = MeteoalarmParser.level(e.severity)
            if (level < store.minLevel) continue
            if (e.msgType.equals("Cancel", true)) continue
            val expired = try { e.expires.isNotBlank() && OffsetDateTime.parse(e.expires).isBefore(now) } catch (ignored: Exception) { false }
            if (expired) continue
            if (!areaMatches(store, e.area + " " + e.title)) continue

            // το ίδιο μήνυμα μπορεί να αφορά πολλές περιοχές -> id ανά περιοχή
            val id = "emy|${e.id}|${e.area}"
            if (store.isSeen(id)) continue

            val color = when (level) { 3 -> "🔴 Κόκκινη"; 2 -> "🟠 Πορτοκαλί"; else -> "🟡 Κίτρινη" }
            val when_ = listOf(fmtIso(e.onset), fmtIso(e.expires)).filter { it.isNotEmpty() }.joinToString(" → ")
            out += Alert(
                id = id,
                source = "ΕΜΥ",
                level = level,
                title = "$color προειδοποίηση ΕΜΥ – ${e.area}",
                text = listOf(translateEvent(e.event), when_).filter { it.isNotBlank() }.joinToString(" · "),
                link = e.link.ifBlank { Sources.EMY_SITE },
                time = fmtIso(e.onset)
            )
        }
        return out
    }

    private fun checkCpRss(store: Store, url: String, fire: Boolean): List<Alert> {
        val items = RssParser.parse(Http.get(url))
            .filterNot { it.link.contains("/en/") } // το site δίνει κάθε άρθρο και στα αγγλικά
        val seedKey = url

        // Πρώτη ανάγνωση: απλώς καταγράφουμε τα υπάρχοντα, χωρίς ειδοποίηση
        if (!store.isSeeded(seedKey)) {
            store.markSeen(items.map { "cp|${it.guid}" })
            store.setSeeded(seedKey)
            return emptyList()
        }

        val keywords = store.keywordList()
        val out = mutableListOf<Alert>()
        for (item in items) {
            val id = "cp|${item.guid}"
            if (store.isSeen(id)) continue
            val hay = norm(item.title + " " + item.description)

            val relevant: Boolean
            val level: Int
            if (fire) {
                val high = Store.FIRE_HIGH.any { k -> hay.contains(k) }
                relevant = store.fireAll || high
                level = if (high) 2 else 0
            } else {
                relevant = keywords.isEmpty() || keywords.any { k -> hay.contains(k) }
                level = if (hay.contains("εκτακτ") || hay.contains("112") || hay.contains("εκκενωσ")) 2 else 1
            }
            // Οι ανακοινώσεις της Πολ. Προστασίας δεν φιλτράρονται ανά περιοχή (συχνά αφορούν πολλές περιφέρειες)
            if (!relevant) { store.markSeen(listOf(id)); continue }

            out += Alert(
                id = id,
                source = "Πολιτική Προστασία",
                level = level,
                title = (if (fire) "🔥 " else "📢 ") + item.title,
                text = item.description.take(400),
                link = item.link,
                time = fmtRfc822(item.pubDate)
            )
        }
        return out
    }

    /** Μικρή μετάφραση των αγγλικών ονομάτων φαινομένων του Meteoalarm */
    private fun translateEvent(ev: String): String {
        val e = ev.lowercase()
        val gr = when {
            "thunder" in e -> "Καταιγίδες"
            "rain" in e && "flood" in e -> "Βροχή / πλημμύρα"
            "rain" in e -> "Έντονες βροχοπτώσεις"
            "flood" in e -> "Πλημμύρα"
            "snow" in e || "ice" in e -> "Χιόνι / παγετός"
            "wind" in e -> "Θυελλώδεις άνεμοι"
            "high temp" in e || "heat" in e -> "Υψηλές θερμοκρασίες / καύσωνας"
            "low temp" in e || "cold" in e -> "Χαμηλές θερμοκρασίες"
            "fog" in e -> "Ομίχλη"
            "fire" in e -> "Κίνδυνος πυρκαγιάς"
            "coastal" in e -> "Ακραία κατάσταση θάλασσας"
            else -> ""
        }
        return if (gr.isEmpty()) ev else "$gr ($ev)"
    }
}
