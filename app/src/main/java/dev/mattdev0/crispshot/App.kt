package dev.mattdev0.crispshot

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import dev.mattdev0.crispshot.data.LogEntry
import dev.mattdev0.crispshot.data.Settings
import dev.mattdev0.crispshot.media.BackupStore
import dev.mattdev0.crispshot.media.InPlaceWriter
import dev.mattdev0.crispshot.media.MediaStoreRepo
import dev.mattdev0.crispshot.media.Upscaler
import dev.mattdev0.crispshot.policy.HdPolicy
import dev.mattdev0.crispshot.watch.ProcessorState
import dev.mattdev0.crispshot.watch.ScreenshotProcessor
import java.io.File

/**
 * Application object holding the lazy manual DI graph.
 * `onCreate` stays minimal so cold-started content-trigger jobs don't pay extra startup cost.
 */
class App : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            // Keep WorkManager's JobScheduler ids clear of ScreenshotJob.JOB_ID.
            .setJobSchedulerJobIdRange(WORK_JOB_ID_MIN, WORK_JOB_ID_MAX)
            .build()

    val policy: HdPolicy = HdPolicy()
    val settings: Settings by lazy { Settings(this) }
    val backupStore: BackupStore by lazy { BackupStore(File(filesDir, "originals")) }
    val mediaStoreRepo: MediaStoreRepo by lazy { MediaStoreRepo(this) }
    val upscaler: Upscaler by lazy { Upscaler() }
    val inPlaceWriter: InPlaceWriter by lazy {
        InPlaceWriter(
            mediaIo = mediaStoreRepo,
            backupStore = backupStore,
            scaler = upscaler,
            cacheDir = cacheDir,
            logWarn = { msg -> Log.w(LOG_TAG, msg) },
        )
    }

    val processor: ScreenshotProcessor by lazy {
        ScreenshotProcessor(
            store = mediaStoreRepo,
            writer = inPlaceWriter,
            state = object : ProcessorState {
                override suspend fun isEnabled(): Boolean = settings.isEnabled()

                override suspend fun getPolicy(): HdPolicy =
                    policy.copy(sources = HdPolicy.sourcesFor(settings.isQuickShareEnabled()))

                override suspend fun getProcessedIds(): Set<Long> = settings.getProcessedIds()

                override suspend fun markProcessed(mediaId: Long) {
                    settings.markProcessed(mediaId)
                }

                override suspend fun appendLog(entry: LogEntry) {
                    settings.appendLog(entry)
                }
            },
            logInfo = { msg -> Log.i(LOG_TAG, msg) },
            logWarn = { msg -> Log.w(LOG_TAG, msg) },
        )
    }

    companion object {
        const val LOG_TAG = "Crispshot"
        private const val WORK_JOB_ID_MIN = 10_000
        private const val WORK_JOB_ID_MAX = 19_999
    }
}
