package dev.mattdev0.crispshot.media

import dev.mattdev0.crispshot.policy.ScalePlan
import dev.mattdev0.crispshot.watch.ProcessorWriter
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream

/** How the target file was opened for writing. */
enum class WritePath {
    MEDIASTORE,
    FILE,
}

/** Opened output stream and the write path that succeeded. */
data class WriteOpenResult(
    val stream: OutputStream,
    val pathUsed: WritePath,
)

/** Testable MediaStore I/O seam for [InPlaceWriter]. */
interface MediaIo {
    fun openRead(row: MediaRow): InputStream
    fun openWriteTruncate(row: MediaRow): WriteOpenResult
    fun setLastModified(row: MediaRow, dateModifiedSec: Long)
    suspend fun rescan(row: MediaRow)
    suspend fun queryDims(id: Long): Pair<Int, Int>?
}

/** Result of an in-place upscale write. */
sealed interface WriteResult {
    data class Success(
        val mediaId: Long,
        val displayName: String,
        val oldWidth: Int,
        val oldHeight: Int,
        val newWidth: Int,
        val newHeight: Int,
        val elapsedMs: Long,
        val pathUsed: WritePath,
        val decodeMs: Long = 0L,
        val encodeMs: Long = 0L,
        val writeMs: Long = 0L,
        val verifyMs: Long = 0L,
        val rescanMs: Long = 0L,
        val warning: String? = null,
    ) : WriteResult

    data class Failure(
        val mediaId: Long,
        val displayName: String,
        val reason: String,
        val restoredFromBackup: Boolean,
    ) : WriteResult
}

/** Result of restoring an original screenshot from [BackupStore]. */
sealed interface RestoreResult {
    data class Success(val metadata: BackupMetadata, val pathUsed: WritePath) : RestoreResult
    data class Failure(val reason: String) : RestoreResult
}

/**
 * Safely replaces a screenshot in-place with its upscaled version:
 * backup -> upscale to temp -> verify bounds -> write -> restore mtime -> rescan -> verify target.
 * Rolls back from backup if any write or verification step fails.
 */
class InPlaceWriter(
    private val mediaIo: MediaIo,
    private val backupStore: BackupStore,
    private val scaler: ImageScaler,
    private val cacheDir: File,
    private val logWarn: (String) -> Unit = {},
) : ProcessorWriter {
    override suspend fun process(row: MediaRow, plan: ScalePlan): WriteResult {
        val startMs = System.currentTimeMillis()
        var targetTouched = false
        val tempFile = File(cacheDir, "upscale_${row.id}_${System.nanoTime()}.tmp")

        try {
            // 1. Back up the original bytes first.
            mediaIo.openRead(row).use { originalStream ->
                backupStore.save(row, originalStream)
            }

            // 2. Upscale straight from the verified backup file to a temp file, then verify its decoded bounds.
            val backupFile = backupStore.backupFile(row.id)
                ?: return WriteResult.Failure(row.id, row.displayName, "backup missing after save", false)
            val scaleMetrics = scaler.upscaleToFile(backupFile, tempFile, plan, row.mimeType)

            val tTempVerifyStart = System.currentTimeMillis()
            val tempValid = scaler.verifyBounds(tempFile, plan.targetWidth, plan.targetHeight)
            val tempVerifyMs = System.currentTimeMillis() - tTempVerifyStart
            if (!tempValid) {
                return WriteResult.Failure(
                    mediaId = row.id,
                    displayName = row.displayName,
                    reason = "upscaled temp failed bounds check (expected ${plan.targetWidth}x${plan.targetHeight})",
                    restoredFromBackup = false,
                )
            }

            // 3. Overwrite the target (MediaStore "wt", fallback to FileOutputStream).
            val tWriteStart = System.currentTimeMillis()
            targetTouched = true
            val openResult = mediaIo.openWriteTruncate(row)
            openResult.stream.use { out ->
                FileInputStream(tempFile).use { input ->
                    input.copyTo(out)
                }
            }
            val writeMs = System.currentTimeMillis() - tWriteStart

            // Verify written target stream decodes to the expected dims before accepting.
            val tTargetVerifyStart = System.currentTimeMillis()
            val targetDecodesOk = mediaIo.openRead(row).use { writtenStream ->
                scaler.verifyStreamBounds(writtenStream, plan.targetWidth, plan.targetHeight)
            }
            val verifyMs = tempVerifyMs + (System.currentTimeMillis() - tTargetVerifyStart)
            if (!targetDecodesOk) {
                val restored = rollback(row)
                return WriteResult.Failure(
                    mediaId = row.id,
                    displayName = row.displayName,
                    reason = "written target failed bounds verification",
                    restoredFromBackup = restored,
                )
            }

            // 4. Rescan so MediaStore sees the new mtime/size and updates WIDTH/HEIGHT, then restore mtime.
            val tRescanStart = System.currentTimeMillis()
            mediaIo.rescan(row)
            var scannedDims = mediaIo.queryDims(row.id)
            if (scannedDims != (plan.targetWidth to plan.targetHeight)) {
                // If write finished within the same wall-clock second as DATE_MODIFIED, bump mtime by 1s so MediaScanner re-parses.
                mediaIo.setLastModified(row, row.dateModifiedSec + 1L)
                mediaIo.rescan(row)
                scannedDims = mediaIo.queryDims(row.id)
            }
            mediaIo.setLastModified(row, row.dateModifiedSec)
            val rescanMs = System.currentTimeMillis() - tRescanStart
            val warning = if (scannedDims != (plan.targetWidth to plan.targetHeight)) {
                val msg = "rescan dims mismatch for id=${row.id}: expected ${plan.targetWidth}x${plan.targetHeight}, got $scannedDims"
                logWarn(msg)
                msg
            } else {
                null
            }

            val elapsedMs = System.currentTimeMillis() - startMs
            return WriteResult.Success(
                mediaId = row.id,
                displayName = row.displayName,
                oldWidth = row.width,
                oldHeight = row.height,
                newWidth = plan.targetWidth,
                newHeight = plan.targetHeight,
                elapsedMs = elapsedMs,
                pathUsed = openResult.pathUsed,
                decodeMs = scaleMetrics.decodeMs,
                encodeMs = scaleMetrics.encodeMs,
                writeMs = writeMs,
                verifyMs = verifyMs,
                rescanMs = rescanMs,
                warning = warning,
            )
        } catch (t: Throwable) {
            val restored = if (targetTouched) rollback(row) else false
            return WriteResult.Failure(
                mediaId = row.id,
                displayName = row.displayName,
                reason = t.message ?: t::class.java.simpleName,
                restoredFromBackup = restored,
            )
        } finally {
            tempFile.delete()
        }
    }

    /** Restores the original screenshot bytes for [mediaId] from [BackupStore]. */
    suspend fun restore(mediaId: Long, currentRow: MediaRow? = null): RestoreResult {
        val meta = backupStore.readMetadata(mediaId)
            ?: return RestoreResult.Failure("No backup metadata for id=$mediaId")
        val backupStream = backupStore.openBackupStream(mediaId)
            ?: return RestoreResult.Failure("Backup bytes missing for id=$mediaId")

        val targetRow = currentRow ?: MediaRow(
            id = meta.mediaId,
            displayName = meta.displayName,
            relativePath = meta.relativePath,
            dataPath = meta.dataPath,
            width = meta.width,
            height = meta.height,
            mimeType = meta.mimeType,
            dateAddedSec = meta.dateModifiedSec,
            dateModifiedSec = meta.dateModifiedSec,
            sizeBytes = meta.sizeBytes,
        )

        return try {
            val openResult = mediaIo.openWriteTruncate(targetRow)
            openResult.stream.use { out ->
                backupStream.use { input -> input.copyTo(out) }
            }
            mediaIo.setLastModified(targetRow, meta.dateModifiedSec)
            mediaIo.rescan(targetRow)
            RestoreResult.Success(meta, openResult.pathUsed)
        } catch (t: Throwable) {
            RestoreResult.Failure(t.message ?: t::class.java.simpleName)
        }
    }

    private suspend fun rollback(row: MediaRow): Boolean {
        val backupStream = backupStore.openBackupStream(row.id) ?: return false
        return try {
            val openResult = mediaIo.openWriteTruncate(row)
            openResult.stream.use { out ->
                backupStream.use { input -> input.copyTo(out) }
            }
            mediaIo.setLastModified(row, row.dateModifiedSec)
            mediaIo.rescan(row)
            true
        } catch (t: Throwable) {
            logWarn("rollback failed for id=${row.id}: ${t.message}")
            false
        }
    }
}
