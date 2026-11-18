package dev.mattdev0.crispshot.media

import dev.mattdev0.crispshot.policy.ScalePlan
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InPlaceWriterTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val originalBytes = "ORIG_1080x2400".encodeToByteArray()
    private val upscaledBytes = "UPSCALED_2160x4800".encodeToByteArray()
    private val plan = ScalePlan(targetWidth = 2160, targetHeight = 4800, filter = false, factor = 2)

    private fun sampleRow() = MediaRow(
        id = 555L,
        displayName = "Screenshot_test.png",
        relativePath = "Pictures/Screenshots/",
        dataPath = "/sdcard/Pictures/Screenshots/Screenshot_test.png",
        width = 1080,
        height = 2400,
        mimeType = "image/png",
        dateAddedSec = 1_760_000_000L,
        dateModifiedSec = 1_760_000_010L,
        sizeBytes = originalBytes.size.toLong(),
    )

    private class FakeMediaIo(initialBytes: ByteArray) : MediaIo {
        var bytes: ByteArray = initialBytes.copyOf()
        var lastModifiedSec: Long = 0L
        var rescanCount: Int = 0
        var dimsAfterRescan: Pair<Int, Int>? = 2160 to 4800
        var throwOnNthWrite: Int? = null
        private var writeCount = 0

        override fun openRead(row: MediaRow): InputStream = ByteArrayInputStream(bytes.copyOf())

        override fun openWriteTruncate(row: MediaRow): WriteOpenResult {
            writeCount++
            val failOn = throwOnNthWrite
            val stream = object : OutputStream() {
                private val buffer = ByteArrayOutputStream()
                override fun write(b: Int) {
                    if (failOn != null && writeCount == failOn) {
                        throw IOException("simulated disk full")
                    }
                    buffer.write(b)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (failOn != null && writeCount == failOn) {
                        throw IOException("simulated disk full")
                    }
                    buffer.write(b, off, len)
                }

                override fun close() {
                    if (failOn == null || writeCount != failOn) {
                        bytes = buffer.toByteArray()
                    }
                }
            }
            return WriteOpenResult(stream, WritePath.MEDIASTORE)
        }

        override fun setLastModified(row: MediaRow, dateModifiedSec: Long) {
            lastModifiedSec = dateModifiedSec
        }

        override suspend fun rescan(row: MediaRow) {
            rescanCount++
        }

        override suspend fun queryDims(id: Long): Pair<Int, Int>? = dimsAfterRescan
    }

    private class FakeScaler(
        private val upscaledPayload: ByteArray,
        private val tempBoundsOk: Boolean = true,
        private val targetBoundsOk: Boolean = true,
    ) : ImageScaler {
        override fun upscaleToFile(inputFile: File, outputFile: File, plan: ScalePlan, mimeType: String): ScaleMetrics {
            outputFile.writeBytes(upscaledPayload)
            return ScaleMetrics(decodeMs = 1L, encodeMs = 2L)
        }

        override fun verifyBounds(file: File, expectedWidth: Int, expectedHeight: Int): Boolean =
            tempBoundsOk && file.isFile && file.length() > 0

        override fun verifyStreamBounds(input: InputStream, expectedWidth: Int, expectedHeight: Int): Boolean {
            input.use { it.readBytes() }
            return targetBoundsOk
        }
    }

    @Test
    fun process_success_writesUpscaledBytes_preservesMtime_rescans_andBacksUpOriginal() = runTest {
        val backupStore = BackupStore(tempFolder.newFolder("originals"))
        val cacheDir = tempFolder.newFolder("cache")
        val mediaIo = FakeMediaIo(originalBytes)
        val writer = InPlaceWriter(mediaIo, backupStore, FakeScaler(upscaledBytes), cacheDir)

        val result = writer.process(sampleRow(), plan)

        assertTrue(result is WriteResult.Success)
        val ok = result as WriteResult.Success
        assertEquals(2160, ok.newWidth)
        assertEquals(4800, ok.newHeight)
        assertEquals(WritePath.MEDIASTORE, ok.pathUsed)
        assertNull(ok.warning)
        assertArrayEquals(upscaledBytes, mediaIo.bytes)
        assertEquals(1_760_000_010L, mediaIo.lastModifiedSec)
        assertEquals(1, mediaIo.rescanCount)
        assertTrue(backupStore.hasBackup(555L))
        assertArrayEquals(originalBytes, requireNotNull(backupStore.openBackupStream(555L)).use { it.readBytes() })
    }

    @Test
    fun process_writeThrows_restoresOriginalBytesAndRescans() = runTest {
        val backupStore = BackupStore(tempFolder.newFolder("originals"))
        val cacheDir = tempFolder.newFolder("cache")
        val mediaIo = FakeMediaIo(originalBytes).apply {
            throwOnNthWrite = 1
        }
        val writer = InPlaceWriter(mediaIo, backupStore, FakeScaler(upscaledBytes), cacheDir)

        val result = writer.process(sampleRow(), plan)

        assertTrue(result is WriteResult.Failure)
        val failure = result as WriteResult.Failure
        assertTrue(failure.restoredFromBackup)
        assertArrayEquals(originalBytes, mediaIo.bytes)
        assertEquals(1, mediaIo.rescanCount)
        assertEquals(1_760_000_010L, mediaIo.lastModifiedSec)
    }

    @Test
    fun process_targetVerifyFails_restoresOriginalBytesAndRescans() = runTest {
        val backupStore = BackupStore(tempFolder.newFolder("originals"))
        val cacheDir = tempFolder.newFolder("cache")
        val mediaIo = FakeMediaIo(originalBytes)
        val scaler = FakeScaler(upscaledBytes, tempBoundsOk = true, targetBoundsOk = false)
        val writer = InPlaceWriter(mediaIo, backupStore, scaler, cacheDir)

        val result = writer.process(sampleRow(), plan)

        assertTrue(result is WriteResult.Failure)
        val failure = result as WriteResult.Failure
        assertTrue(failure.restoredFromBackup)
        assertArrayEquals(originalBytes, mediaIo.bytes)
        assertEquals(1, mediaIo.rescanCount)
    }

    @Test
    fun process_tempVerifyFails_doesNotOverwriteTarget() = runTest {
        val backupStore = BackupStore(tempFolder.newFolder("originals"))
        val cacheDir = tempFolder.newFolder("cache")
        val mediaIo = FakeMediaIo(originalBytes)
        val scaler = FakeScaler(upscaledBytes, tempBoundsOk = false)
        val writer = InPlaceWriter(mediaIo, backupStore, scaler, cacheDir)

        val result = writer.process(sampleRow(), plan)

        assertTrue(result is WriteResult.Failure)
        assertArrayEquals(originalBytes, mediaIo.bytes)
        assertEquals(0, mediaIo.rescanCount)
    }

    @Test
    fun process_rescanReturnsOldDims_logsWarningButKeepsUpscaledFile() = runTest {
        val warnings = mutableListOf<String>()
        val backupStore = BackupStore(tempFolder.newFolder("originals"))
        val cacheDir = tempFolder.newFolder("cache")
        val mediaIo = FakeMediaIo(originalBytes).apply {
            dimsAfterRescan = 1080 to 2400
        }
        val writer = InPlaceWriter(
            mediaIo,
            backupStore,
            FakeScaler(upscaledBytes),
            cacheDir,
            logWarn = { warnings.add(it) },
        )

        val result = writer.process(sampleRow(), plan)

        assertTrue(result is WriteResult.Success)
        val ok = result as WriteResult.Success
        assertNotNull(ok.warning)
        assertEquals(1, warnings.size)
        assertArrayEquals(upscaledBytes, mediaIo.bytes)
    }

    @Test
    fun restore_restoresOriginalBytesAndRescans() = runTest {
        val backupStore = BackupStore(tempFolder.newFolder("originals"))
        val cacheDir = tempFolder.newFolder("cache")
        val mediaIo = FakeMediaIo(originalBytes)
        val writer = InPlaceWriter(mediaIo, backupStore, FakeScaler(upscaledBytes), cacheDir)

        val writeResult = writer.process(sampleRow(), plan)
        assertTrue(writeResult is WriteResult.Success)
        assertArrayEquals(upscaledBytes, mediaIo.bytes)

        val restoreResult = writer.restore(555L)
        assertTrue(restoreResult is RestoreResult.Success)
        assertArrayEquals(originalBytes, mediaIo.bytes)
        assertEquals(2, mediaIo.rescanCount)
    }

    @Test
    fun process_deletedBeforeRead_failsGracefullyWithoutTouchingTarget() = runTest {
        val backupStore = BackupStore(tempFolder.newFolder("originals"))
        val cacheDir = tempFolder.newFolder("cache")
        val missingIo = object : MediaIo {
            override fun openRead(row: MediaRow): InputStream =
                throw FileNotFoundException("screenshot deleted")

            override fun openWriteTruncate(row: MediaRow): WriteOpenResult =
                error("should not write when read fails")

            override fun setLastModified(row: MediaRow, dateModifiedSec: Long) = Unit
            override suspend fun rescan(row: MediaRow) = Unit
            override suspend fun queryDims(id: Long): Pair<Int, Int>? = null
        }
        val writer = InPlaceWriter(missingIo, backupStore, FakeScaler(upscaledBytes), cacheDir)

        val result = writer.process(sampleRow(), plan)

        assertTrue(result is WriteResult.Failure)
        val failure = result as WriteResult.Failure
        assertFalse(failure.restoredFromBackup)
        assertFalse(backupStore.hasBackup(555L))
    }
}
