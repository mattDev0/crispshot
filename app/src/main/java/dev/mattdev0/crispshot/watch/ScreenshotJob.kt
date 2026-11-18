package dev.mattdev0.crispshot.watch

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import dev.mattdev0.crispshot.App

/** Schedules, cancels, and inspects the MediaStore content-URI trigger job. */
object ScreenshotJob {
    const val JOB_ID = 1001
    private const val UPDATE_DELAY_MS = 0L
    private const val MAX_DELAY_MS = 0L

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val trigger = JobInfo.TriggerContentUri(
            uri,
            JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
        )
        val jobInfo = JobInfo.Builder(JOB_ID, ComponentName(context, ScreenshotJobService::class.java))
            .addTriggerContentUri(trigger)
            .setTriggerContentUpdateDelay(UPDATE_DELAY_MS)
            .setTriggerContentMaxDelay(MAX_DELAY_MS)
            .build()

        val result = scheduler.schedule(jobInfo)
        if (result != JobScheduler.RESULT_SUCCESS) {
            Log.w(App.LOG_TAG, "job schedule returned $result")
        }
    }

    fun cancel(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        scheduler.cancel(JOB_ID)
    }

    fun isScheduled(context: Context): Boolean {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        return scheduler.getPendingJob(JOB_ID) != null
    }
}
