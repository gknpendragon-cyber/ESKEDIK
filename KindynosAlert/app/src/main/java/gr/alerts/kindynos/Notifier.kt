package gr.alerts.kindynos

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifier {
    const val CH_URGENT = "urgent"   // πορτοκαλί / κόκκινη / έκτακτα
    const val CH_INFO = "info"       // κίτρινη / γενικές ανακοινώσεις
    private const val GROUP = "kindynos_group"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_URGENT, "Επικίνδυνα φαινόμενα", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Πορτοκαλί/κόκκινες προειδοποιήσεις ΕΜΥ και έκτακτες ανακοινώσεις Πολιτικής Προστασίας"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 800)
            enableLights(true); lightColor = Color.RED
        })
        nm.createNotificationChannel(NotificationChannel(CH_INFO, "Ενημερώσεις", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Κίτρινες προειδοποιήσεις και λοιπές σχετικές ανακοινώσεις"
        })
    }

    private fun canPost(ctx: Context) =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun show(ctx: Context, a: Alert) {
        if (!canPost(ctx)) return
        createChannels(ctx)
        val open = Intent(Intent.ACTION_VIEW, Uri.parse(a.link))
        val pi = PendingIntent.getActivity(ctx, a.id.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val urgent = a.level >= 2
        val color = when (a.level) { 3 -> 0xFFD32F2F.toInt(); 2 -> 0xFFF57C00.toInt(); 1 -> 0xFFFBC02D.toInt(); else -> 0xFF1976D2.toInt() }

        val n = NotificationCompat.Builder(ctx, if (urgent) CH_URGENT else CH_INFO)
            .setSmallIcon(R.drawable.ic_alert)
            .setColor(color)
            .setContentTitle(a.title)
            .setContentText(a.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${a.text}\n\nΠηγή: ${a.source}"))
            .setSubText(a.source)
            .setPriority(if (urgent) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setGroup(GROUP)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(a.id.hashCode(), n) } catch (ignored: SecurityException) {}
    }

    fun summary(ctx: Context, count: Int) {
        if (!canPost(ctx)) return
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CH_URGENT)
            .setSmallIcon(R.drawable.ic_alert)
            .setContentTitle("$count νέες ειδοποιήσεις κινδύνου")
            .setGroup(GROUP).setGroupSummary(true)
            .setContentIntent(pi).setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(1, n) } catch (ignored: SecurityException) {}
    }
}
