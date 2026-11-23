package gr.alerts.kindynos

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Ρυθμίσεις χρήστη και κατάσταση (ποιες ειδοποιήσεις έχουν ήδη σταλεί, ιστορικό). */
class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("kindynos", Context.MODE_PRIVATE)

    companion object {
        const val DEFAULT_KEYWORDS =
            "έκτακτ, επικίνδυν, επιδείνωσ, κακοκαιρ, καιρικ, πλημμ, καταιγ, χιον, παγετ, " +
            "καύσων, θυελλ, ανεμ, πυρκαγ, κίνδυν, 112, εκκένωσ, σεισμ"

        /** Φράσεις στους χάρτες κινδύνου πυρκαγιάς που σημαίνουν υψηλή κατηγορία */
        val FIRE_HIGH = listOf(
            "πολυ υψηλ", "κατασταση συναγερμου", "κατηγορια 4", "κατηγορια 5",
            "κατηγορια κινδυνου 4", "κατηγορια κινδυνου 5"
        )
        private const val MAX_SEEN = 800
        private const val MAX_HISTORY = 60
    }

    // ---------- Ρυθμίσεις ----------
    /** Περιοχές, χωρισμένες με κόμμα. Κενό = όλη η Ελλάδα. */
    var areas: String
        get() = sp.getString("areas", "") ?: ""
        set(v) = sp.edit().putString("areas", v).apply()

    /** Ελάχιστο επίπεδο ΕΜΥ: 1 κίτρινη, 2 πορτοκαλί, 3 κόκκινη */
    var minLevel: Int
        get() = sp.getInt("minLevel", 2)
        set(v) = sp.edit().putInt("minLevel", v).apply()

    var cpNews: Boolean
        get() = sp.getBoolean("cpNews", true)
        set(v) = sp.edit().putBoolean("cpNews", v).apply()

    var cpFire: Boolean
        get() = sp.getBoolean("cpFire", true)
        set(v) = sp.edit().putBoolean("cpFire", v).apply()

    /** true = ειδοποίηση για κάθε νέο χάρτη πυρκαγιάς, false = μόνο υψηλή κατηγορία */
    var fireAll: Boolean
        get() = sp.getBoolean("fireAll", false)
        set(v) = sp.edit().putBoolean("fireAll", v).apply()

    var keywords: String
        get() = sp.getString("keywords", DEFAULT_KEYWORDS) ?: DEFAULT_KEYWORDS
        set(v) = sp.edit().putString("keywords", v).apply()

    var intervalMin: Int
        get() = sp.getInt("intervalMin", 15)
        set(v) = sp.edit().putInt("intervalMin", v.coerceAtLeast(15)).apply()

    fun areaList(): List<String> = areas.split(',', ';').map { norm(it.trim()) }.filter { it.isNotEmpty() }
    fun keywordList(): List<String> = keywords.split(',', ';').map { norm(it.trim()) }.filter { it.isNotEmpty() }

    // ---------- Κατάσταση ----------
    var lastCheck: String
        get() = sp.getString("lastCheck", "—") ?: "—"
        set(v) = sp.edit().putString("lastCheck", v).apply()

    var lastError: String
        get() = sp.getString("lastError", "") ?: ""
        set(v) = sp.edit().putString("lastError", v).apply()

    /** Αν μια πηγή RSS έχει διαβαστεί έστω μία φορά (για να μη «βομβαρδιστεί» ο χρήστης την πρώτη φορά). */
    fun isSeeded(source: String) = sp.getBoolean("seeded_$source", false)
    fun setSeeded(source: String) = sp.edit().putBoolean("seeded_$source", true).apply()

    private fun seenArray(): JSONArray = JSONArray(sp.getString("seen", "[]"))

    fun isSeen(id: String): Boolean {
        val a = seenArray()
        for (i in 0 until a.length()) if (a.getString(i) == id) return true
        return false
    }

    fun markSeen(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val list = mutableListOf<String>()
        val a = seenArray()
        for (i in 0 until a.length()) list += a.getString(i)
        ids.forEach { if (it !in list) list += it }
        val trimmed = list.takeLast(MAX_SEEN)
        sp.edit().putString("seen", JSONArray(trimmed).toString()).apply()
    }

    fun addHistory(alerts: List<Alert>) {
        if (alerts.isEmpty()) return
        val all = alerts + history()
        val arr = JSONArray()
        all.distinctBy { it.id }.take(MAX_HISTORY).forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("source", it.source); put("level", it.level)
                put("title", it.title); put("text", it.text); put("link", it.link); put("time", it.time)
            })
        }
        sp.edit().putString("history", arr.toString()).apply()
    }

    fun history(): List<Alert> {
        val a = JSONArray(sp.getString("history", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Alert(o.optString("id"), o.optString("source"), o.optInt("level"), o.optString("title"),
                o.optString("text"), o.optString("link"), o.optString("time"))
        }
    }

    fun clearHistory() = sp.edit().putString("history", "[]").apply()
}
