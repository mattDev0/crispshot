package dev.mattdev0.crispshot.media

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun sampleRow(id: Long = 100L) = MediaRow(
        id = id,
        displayName = "Screenshot_100.png",
        relativePath = "Pictures/Screenshots/",
        dataPath = "/sdcard/Pictures/Screenshots/Screenshot_100.png",
        width = 1080,
        height = 2400,
        mimeType = "image/png",
        dateAddedSec = 1_700_000_000L,
        dateModifiedSec = 1_700_000_005L,
        sizeBytes = 5L,
    )

    @Test
    fun saveReadAndDelete_roundTripsBytesAndMetadata() {
        val store = BackupStore(tempFolder.newFolder("originals"))
        val originalBytes = byteArrayOf(1, 2, 3, 4, 5)
        val saved = store.save(sampleRow(77L), ByteArrayInputStream(originalBytes), nowMs = 1_000L)

        assertTrue(store.hasBackup(77L))
        assertEquals("77.png", saved.backupFileName)

        val meta = store.readMetadata(77L)
        assertNotNull(meta)
        assertEquals(1080, meta?.width)
        assertEquals(2400, meta?.height)
        assertEquals(1_700_000_005L, meta?.dateModifiedSec)

        val readBack = requireNotNull(store.openBackupStream(77L)).use { it.readBytes() }
        assertArrayEquals(originalBytes, readBack)

        store.delete(77L)
        assertFalse(store.hasBackup(77L))
        assertNull(store.readMetadata(77L))
    }

    @Test
    fun pruneOlderThan_removesOnlyExpiredBackups() {
        val store = BackupStore(tempFolder.newFolder("originals"))
        val sevenDaysMs = 7L * 24 * 60 * 60 * 1000
        val now = 10_000_000_000L

        store.save(sampleRow(1L), ByteArrayInputStream(byteArrayOf(10)), nowMs = now - sevenDaysMs - 1_000L)
        store.save(sampleRow(2L), ByteArrayInputStream(byteArrayOf(20)), nowMs = now - 60_000L)

        val pruned = store.pruneOlderThan(sevenDaysMs, nowMs = now)
        assertEquals(1, pruned)
        assertFalse(store.hasBackup(1L))
        assertTrue(store.hasBackup(2L))
    }
}
