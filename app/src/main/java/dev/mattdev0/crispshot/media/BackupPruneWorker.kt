package dev.mattdev0.crispshot.media

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.mattdev0.crispshot.App
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Periodic daily worker pruning screenshot backups older than 7 days. */
class BackupPruneWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as App
        val pruned = app.backupStore.pruneOlderThan(BACKUP_RETENTION_MS)
        if (pruned > 0) {
            Log.i(App.LOG_TAG, "daily prune removed $pruned expired backups")
        }
        Result.success()
    }

    companion object {
        const val BACKUP_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L
        private const val WORK_NAME = "backup_prune_daily"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackupPruneWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
