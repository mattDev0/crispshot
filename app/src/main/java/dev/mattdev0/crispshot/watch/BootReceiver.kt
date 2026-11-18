package dev.mattdev0.crispshot.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.mattdev0.crispshot.App
import dev.mattdev0.crispshot.media.BackupPruneWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Reschedules [ScreenshotJob] and daily backup pruning on boot or package replacement. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val pendingResult = goAsync()
        val app = context.applicationContext as App
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                BackupPruneWorker.schedule(context.applicationContext)
                if (app.settings.isEnabled()) {
                    ScreenshotJob.schedule(context.applicationContext)
                    Log.i(App.LOG_TAG, "boot receiver scheduled job ($action)")
                } else {
                    ScreenshotJob.cancel(context.applicationContext)
                    Log.i(App.LOG_TAG, "boot receiver skipped ($action): Auto-HD disabled")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
