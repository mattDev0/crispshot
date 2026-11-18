package dev.mattdev0.crispshot.watch

import dev.mattdev0.crispshot.data.LogEntry
import dev.mattdev0.crispshot.media.MediaRow
import dev.mattdev0.crispshot.media.WritePath
import dev.mattdev0.crispshot.media.WriteResult
import dev.mattdev0.crispshot.policy.HdPolicy
import dev.mattdev0.crispshot.policy.ScalePlan
import dev.mattdev0.crispshot.policy.ScreenshotSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotProcessorTest {
    private val nowSec = 1_760_000_000L

    private fun sampleRow(
        id: Long = 10L,
        isPending: Int = 0,
        width: Int = 1080,
        height: Int = 2400,
    ) = MediaRow(
        id = id,
        displayName = "Screenshot_$id.png",
        relativePath = "Pictures/Screenshots/",
        width = width,
        height = height,
        mimeType = "image/png",
        dateAddedSec = nowSec - 2L,
        dateModifiedSec = nowSec - 2L,
        isPending = isPending,
    )

    private class FakeStore(
        private val rowsById: MutableMap<Long, MutableList<MediaRow>> = mutableMapOf(),
        var recentRows: List<MediaRow> = emptyList(),
    ) : ScreenshotStore {
        var queryRecentCalls = 0

        fun enqueueRow(id: Long, vararg sequence: MediaRow) {
            rowsById[id] = sequence.toMutableList()
        }

        override fun queryById(id: Long): MediaRow? {
            val list = rowsById[id] ?: return null
            if (list.isEmpty()) return null
            return if (list.size > 1) list.removeAt(0) else list.first()
        }

        override fun queryRecent(sinceEpochSec: Long, sources: List<ScreenshotSource>): List<MediaRow> {
            queryRecentCalls++
            return recentRows
        }
    }

    private class FakeWriter : ProcessorWriter {
        val writtenIds = mutableListOf<Long>()

        override suspend fun process(row: MediaRow, plan: ScalePlan): WriteResult {
            writtenIds.add(row.id)
            return WriteResult.Success(
                mediaId = row.id,
                displayName = row.displayName,
                oldWidth = row.width,
                oldHeight = row.height,
                newWidth = plan.targetWidth,
                newHeight = plan.targetHeight,
                elapsedMs = 450L,
                pathUsed = WritePath.MEDIASTORE,
            )
        }
    }

    private class FakeState(
        var enabled: Boolean = true,
        val policy: HdPolicy = HdPolicy(),
        val processed: MutableSet<Long> = mutableSetOf(),
    ) : ProcessorState {
        val logs = mutableListOf<LogEntry>()

        override suspend fun isEnabled(): Boolean = enabled
        override suspend fun getPolicy(): HdPolicy = policy
        override suspend fun getProcessedIds(): Set<Long> = processed.toSet()
        override suspend fun markProcessed(mediaId: Long) {
            processed.add(mediaId)
        }

        override suspend fun appendLog(entry: LogEntry) {
            logs.add(entry)
        }
    }

    @Test
    fun nullIds_runsRecentScanAndProcessesEligibleRows() = runTest {
        val store = FakeStore(recentRows = listOf(sampleRow(id = 201L)))
        val writer = FakeWriter()
        val state = FakeState()
        val processor = ScreenshotProcessor(
            store = store,
            writer = writer,
            state = state,
            delayFn = {},
            clockSec = { nowSec },
            clockMs = { nowSec * 1000L },
        )

        processor.processIds(null)

        assertEquals(1, store.queryRecentCalls)
        assertEquals(listOf(201L), writer.writtenIds)
        assertTrue(201L in state.processed)
        assertEquals(1, state.logs.size)
    }

    @Test
    fun pendingRowStillPendingAfterRequery_isSkippedWithoutWriteOrMarkingProcessed() = runTest {
        val store = FakeStore().apply {
            enqueueRow(301L, sampleRow(id = 301L, isPending = 1), sampleRow(id = 301L, isPending = 1))
        }
        val writer = FakeWriter()
        val state = FakeState()
        val delays = mutableListOf<Long>()
        val processor = ScreenshotProcessor(
            store = store,
            writer = writer,
            state = state,
            delayFn = { delays.add(it) },
            clockSec = { nowSec },
        )

        processor.processIds(listOf(301L))

        assertEquals(listOf(ScreenshotProcessor.PENDING_REQUERY_DELAY_MS), delays)
        assertTrue(writer.writtenIds.isEmpty())
        assertTrue(state.processed.isEmpty())
        assertTrue(state.logs.isEmpty())
    }

    @Test
    fun pendingRowClearedOnRequery_isProcessedInSameJob() = runTest {
        val store = FakeStore().apply {
            enqueueRow(302L, sampleRow(id = 302L, isPending = 1), sampleRow(id = 302L, isPending = 0))
        }
        val writer = FakeWriter()
        val state = FakeState()
        val processor = ScreenshotProcessor(
            store = store,
            writer = writer,
            state = state,
            delayFn = {},
            clockSec = { nowSec },
            clockMs = { nowSec * 1000L },
        )

        processor.processIds(listOf(302L))

        assertEquals(listOf(302L), writer.writtenIds)
        assertTrue(302L in state.processed)
        assertEquals(1, state.logs.size)
    }

    @Test
    fun alreadyProcessedId_isSkippedWithoutWrite() = runTest {
        val store = FakeStore().apply {
            enqueueRow(401L, sampleRow(id = 401L))
        }
        val writer = FakeWriter()
        val state = FakeState(processed = mutableSetOf(401L))
        val processor = ScreenshotProcessor(
            store = store,
            writer = writer,
            state = state,
            delayFn = {},
            clockSec = { nowSec },
        )

        processor.processIds(listOf(401L))

        assertTrue(writer.writtenIds.isEmpty())
        assertTrue(state.logs.isEmpty())
    }

    @Test
    fun successfulRow_marksProcessedAndAppendsLog() = runTest {
        val store = FakeStore().apply {
            enqueueRow(501L, sampleRow(id = 501L))
        }
        val writer = FakeWriter()
        val state = FakeState()
        val processor = ScreenshotProcessor(
            store = store,
            writer = writer,
            state = state,
            delayFn = {},
            clockSec = { nowSec },
            clockMs = { 9_999L },
        )

        processor.processIds(listOf(501L, 501L))

        assertEquals(listOf(501L), writer.writtenIds)
        assertEquals(setOf(501L), state.processed)
        assertEquals(1, state.logs.size)
        assertEquals(2160, state.logs.first().newWidth)
        assertEquals(4800, state.logs.first().newHeight)
        assertEquals(9_999L, state.logs.first().timestampMs)
    }
}
