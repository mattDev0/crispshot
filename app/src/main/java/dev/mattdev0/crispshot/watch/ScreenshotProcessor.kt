package dev.mattdev0.crispshot.watch

import dev.mattdev0.crispshot.data.LogEntry
import dev.mattdev0.crispshot.media.MediaRow
import dev.mattdev0.crispshot.media.WriteResult
import dev.mattdev0.crispshot.policy.Decision
import dev.mattdev0.crispshot.policy.HdPolicy
import dev.mattdev0.crispshot.policy.ScalePlan
import dev.mattdev0.crispshot.policy.ProcessedSize
import dev.mattdev0.crispshot.policy.ScreenshotFilter
import dev.mattdev0.crispshot.policy.ScreenshotSource
import dev.mattdev0.crispshot.policy.SkipReason

/** Minimal MediaStore query interface for [ScreenshotProcessor]. */
interface ScreenshotStore {
    fun queryById(id: Long): MediaRow?
    fun queryRecent(sinceEpochSec: Long, sources: List<ScreenshotSource>): List<MediaRow>
}

/** Minimal write interface for [ScreenshotProcessor]. */
interface ProcessorWriter {
    suspend fun process(row: MediaRow, plan: ScalePlan): WriteResult
}

/** Settings and policy state interface for [ScreenshotProcessor]. */
interface ProcessorState {
    suspend fun isEnabled(): Boolean
    suspend fun getPolicy(): HdPolicy
    suspend fun getProcessed(): Map<Long, ProcessedSize>
    suspend fun markProcessed(mediaId: Long, size: ProcessedSize)
    suspend fun appendLog(entry: LogEntry)
}

/**
 * Stateless per-batch screenshot processor shared by [ScreenshotJobService] and JVM unit tests.
 */
class ScreenshotProcessor(
    private val store: ScreenshotStore,
    private val writer: ProcessorWriter,
    private val state: ProcessorState,
    private val delayFn: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val clockSec: () -> Long = { System.currentTimeMillis() / 1000L },
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val logInfo: (String) -> Unit = {},
    private val logWarn: (String) -> Unit = {},
) {
    /** Processes specific media [ids], or scans recent screenshots when [ids] is null. */
    suspend fun processIds(ids: List<Long>?) {
        if (!state.isEnabled()) {
            logInfo("processor skipped: Auto-HD disabled")
            return
        }

        val policy = state.getPolicy()
        val processed = state.getProcessed().toMutableMap()

        if (ids == null) {
            val sinceEpochSec = clockSec() - policy.recentWindowSec
            val rows = store.queryRecent(sinceEpochSec, policy.sources)
            for (row in rows) {
                handleCandidate(row, policy, processed)
            }
            return
        }

        for (id in ids.distinct()) {
            val row = store.queryById(id) ?: continue
            handleCandidate(row, policy, processed)
        }
    }

    private suspend fun handleCandidate(
        initialRow: MediaRow,
        policy: HdPolicy,
        processed: MutableMap<Long, ProcessedSize>,
    ) {
        var row = initialRow
        var decision = ScreenshotFilter.evaluate(row, clockSec(), policy, processed)

        // Short single re-query when a matching screenshot is still pending or populating dims.
        if (shouldRequeryOnce(row, decision)) {
            delayFn(PENDING_REQUERY_DELAY_MS)
            row = store.queryById(row.id) ?: return
            decision = ScreenshotFilter.evaluate(row, clockSec(), policy, processed)
        }

        when (decision) {
            is Decision.Skip -> {
                logInfo("skipped id=${row.id} name=${row.displayName} reason=${decision.reason}")
            }
            is Decision.Process -> {
                when (val result = writer.process(row, decision.plan)) {
                    is WriteResult.Success -> {
                        val size = ProcessedSize(result.newWidth, result.newHeight)
                        processed[row.id] = size
                        state.markProcessed(row.id, size)
                        state.appendLog(
                            LogEntry(
                                mediaId = result.mediaId,
                                displayName = result.displayName,
                                oldWidth = result.oldWidth,
                                oldHeight = result.oldHeight,
                                newWidth = result.newWidth,
                                newHeight = result.newHeight,
                                elapsedMs = result.elapsedMs,
                                writePath = result.pathUsed,
                                timestampMs = clockMs(),
                            ),
                        )
                        logInfo(
                            "processed id=${result.mediaId} name=${result.displayName} " +
                                "${result.oldWidth}x${result.oldHeight}->${result.newWidth}x${result.newHeight} " +
                                "in ${result.elapsedMs}ms " +
                                "(decode=${result.decodeMs}ms encode=${result.encodeMs}ms write=${result.writeMs}ms " +
                                "verify=${result.verifyMs}ms rescan=${result.rescanMs}ms total=${result.elapsedMs}ms) " +
                                "path=${result.pathUsed} owner=${row.ownerPackageName ?: "unknown"}",
                        )
                    }
                    is WriteResult.Failure -> {
                        logWarn(
                            "failed id=${result.mediaId} name=${result.displayName} " +
                                "restored=${result.restoredFromBackup} reason=${result.reason}",
                        )
                    }
                }
            }
        }
    }

    private fun shouldRequeryOnce(row: MediaRow, decision: Decision): Boolean {
        if (decision !is Decision.Skip) return false
        if (decision.reasonCode == SkipReason.PENDING) return true
        return decision.reasonCode == SkipReason.POLICY_SKIPPED && row.width <= 0 && row.height <= 0
    }

    companion object {
        const val PENDING_REQUERY_DELAY_MS = 400L
    }
}
