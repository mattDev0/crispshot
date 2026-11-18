package dev.mattdev0.crispshot.watch

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.util.Log
import dev.mattdev0.crispshot.App
import dev.mattdev0.crispshot.media.BackupPruneWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * System-triggered [JobService] woken only when MediaStore Images change.
 * Reschedules [ScreenshotJob] after processing finishes (in place of `jobFinished`).
 */
class ScreenshotJobService : JobService() {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val jobDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val serviceScope = CoroutineScope(SupervisorJob() + jobDispatcher)

    @Volatile
    private var activeJob: Job? = null

    @Volatile
    private var stopped = false

    override fun onStartJob(params: JobParameters): Boolean {
        stopped = false
        val triggerUris = params.triggeredContentUris
        Log.i(App.LOG_TAG, "job start triggers=${triggerUris?.size ?: "null"}")

        val parsedIds = triggerUris?.mapNotNull(::parseNumericId)?.distinct().orEmpty()
        val idsArg = if (parsedIds.isEmpty()) null else parsedIds

        val app = application as App
        activeJob = serviceScope.launch {
            try {
                app.processor.processIds(idsArg)
                pruneIfDue(app)
            } finally {
                if (!stopped) {
                    if (app.settings.isEnabled()) {
                        // Reschedule only after processing finishes so JobScheduler doesn't stop us early.
                        ScreenshotJob.schedule(applicationContext)
                    } else {
                        jobFinished(params, false)
                    }
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        val reason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) params.stopReason.toString() else "unknown"
        Log.w(App.LOG_TAG, "job stopped reason=$reason")
        activeJob?.cancel()
        return true
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun pruneIfDue(app: App) {
        val nowMs = System.currentTimeMillis()
        val lastPruneMs = app.settings.getLastPruneMs()
        if (nowMs - lastPruneMs > PRUNE_INTERVAL_MS) {
            val pruned = app.backupStore.pruneOlderThan(BackupPruneWorker.BACKUP_RETENTION_MS, nowMs)
            app.settings.setLastPruneMs(nowMs)
            if (pruned > 0) {
                Log.i(App.LOG_TAG, "job prune removed $pruned expired backups")
            }
        }
    }

    private fun parseNumericId(uri: Uri): Long? = try {
        val id = ContentUris.parseId(uri)
        if (id > 0L) id else null
    } catch (_: NumberFormatException) {
        null
    } catch (_: UnsupportedOperationException) {
        null
    }

    companion object {
        private const val PRUNE_INTERVAL_MS = 24L * 60L * 60L * 1000L
    }
}
