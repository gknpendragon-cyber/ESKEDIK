package gr.alerts.kindynos

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Εργασία παρασκηνίου: τρέχει περιοδικά (ελάχιστο 15′ – όριο του Android) και συνεχίζει και μετά από επανεκκίνηση. */
class AlertWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val r = Checker.run(applicationContext)
        // Αν απέτυχαν ΟΛΕΣ οι πηγές (π.χ. χωρίς δίκτυο), ξαναδοκίμασε σύντομα
        return if (r.errors.size >= 3) Result.retry() else Result.success()
    }

    companion object {
        private const val NAME = "kindynos_periodic"

        fun schedule(ctx: Context, intervalMin: Int, replace: Boolean = false) {
            val req = PeriodicWorkRequestBuilder<AlertWorker>(intervalMin.toLong().coerceAtLeast(15), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 2, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                NAME,
                if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }
    }
}
