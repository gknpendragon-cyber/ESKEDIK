package gr.alerts.kindynos

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var store: Store
    private val levels = listOf("🟡 Κίτρινη και πάνω", "🟠 Πορτοκαλί και πάνω", "🔴 Μόνο κόκκινη")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store(this)
        Notifier.createChannels(this)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val spinner = findViewById<Spinner>(R.id.level)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, levels)

        loadSettings()
        AlertWorker.schedule(this, store.intervalMin)

        findViewById<Button>(R.id.btnSave).setOnClickListener { saveSettings() }
        findViewById<Button>(R.id.btnCheck).setOnClickListener { checkNow() }
        findViewById<Button>(R.id.btnTest).setOnClickListener {
            Notifier.show(this, Alert("test|${System.currentTimeMillis()}", "Δοκιμή", 2,
                "🟠 Δοκιμαστική ειδοποίηση", "Αν τη βλέπεις, οι ειδοποιήσεις λειτουργούν.",
                Sources.EMY_SITE, ""))
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            catch (ignored: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
        findViewById<Button>(R.id.btnClear).setOnClickListener { store.clearHistory(); refresh() }

        findViewById<TextView>(R.id.links).text =
            "Πηγές:\n• ΕΜΥ (μέσω Meteoalarm): ${Sources.METEOALARM_GR}\n" +
            "• Πολιτική Προστασία: ${Sources.CP_PRESS}\n  ${Sources.CP_PLANS}\n  ${Sources.CP_FIRE_MAPS}\n" +
            "• ΕΜΥ: ${Sources.EMY_SITE}"
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun loadSettings() {
        findViewById<EditText>(R.id.areas).setText(store.areas)
        findViewById<Spinner>(R.id.level).setSelection((store.minLevel - 1).coerceIn(0, 2))
        findViewById<CheckBox>(R.id.cpNews).isChecked = store.cpNews
        findViewById<EditText>(R.id.keywords).setText(store.keywords)
        findViewById<CheckBox>(R.id.cpFire).isChecked = store.cpFire
        findViewById<CheckBox>(R.id.fireAll).isChecked = store.fireAll
        findViewById<EditText>(R.id.interval).setText(store.intervalMin.toString())
    }

    private fun saveSettings() {
        store.areas = findViewById<EditText>(R.id.areas).text.toString().trim()
        store.minLevel = findViewById<Spinner>(R.id.level).selectedItemPosition + 1
        store.cpNews = findViewById<CheckBox>(R.id.cpNews).isChecked
        store.keywords = findViewById<EditText>(R.id.keywords).text.toString().trim()
        store.cpFire = findViewById<CheckBox>(R.id.cpFire).isChecked
        store.fireAll = findViewById<CheckBox>(R.id.fireAll).isChecked
        store.intervalMin = findViewById<EditText>(R.id.interval).text.toString().toIntOrNull() ?: 15
        findViewById<EditText>(R.id.interval).setText(store.intervalMin.toString())
        AlertWorker.schedule(this, store.intervalMin, replace = true)
        Toast.makeText(this, "Αποθηκεύτηκε – έλεγχος κάθε ${store.intervalMin}′", Toast.LENGTH_SHORT).show()
    }

    private fun checkNow() {
        val btn = findViewById<Button>(R.id.btnCheck)
        btn.isEnabled = false
        btn.text = "Έλεγχος…"
        Thread {
            val r = try { Checker.run(applicationContext) } catch (e: Exception) { Checker.Result(0, listOf(e.toString())) }
            runOnUiThread {
                btn.isEnabled = true
                btn.text = "Έλεγχος τώρα"
                Toast.makeText(this,
                    if (r.newAlerts == 0) "Καμία νέα ειδοποίηση" else "${r.newAlerts} νέες ειδοποιήσεις",
                    Toast.LENGTH_SHORT).show()
                refresh()
            }
        }.start()
    }

    private fun refresh() {
        val err = store.lastError
        findViewById<TextView>(R.id.status).text =
            "Τελευταίος έλεγχος: ${store.lastCheck}\nΑυτόματος έλεγχος κάθε ${store.intervalMin}′" +
                (if (err.isNotBlank()) "\n⚠️ $err" else "")

        val list = findViewById<LinearLayout>(R.id.list)
        list.removeAllViews()
        val h = store.history()
        if (h.isEmpty()) {
            list.addView(TextView(this).apply { text = "Δεν υπάρχουν ακόμη ειδοποιήσεις."; setPadding(0, 16, 0, 16) })
            return
        }
        val pad = (12 * resources.displayMetrics.density).toInt()
        for (a in h) {
            val bg = when (a.level) { 3 -> "#FFCDD2"; 2 -> "#FFE0B2"; 1 -> "#FFF9C4"; else -> "#E3F2FD" }
            list.addView(TextView(this).apply {
                text = "${a.title}\n${a.text}\n${a.source}${if (a.time.isNotBlank()) " · " + a.time else ""}"
                setBackgroundColor(Color.parseColor(bg))
                setPadding(pad, pad, pad, pad)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = pad / 2 }
                setOnClickListener { _: View ->
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.link))) } catch (ignored: Exception) {}
                }
            })
        }
    }
}
