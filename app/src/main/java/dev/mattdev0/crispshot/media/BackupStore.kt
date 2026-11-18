package dev.mattdev0.crispshot.media

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import org.json.JSONObject

/** Persisted metadata for an original screenshot backup. */
data class BackupMetadata(
    val mediaId: Long,
    val displayName: String,
    val relativePath: String,
    val dataPath: String?,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val dateModifiedSec: Long,
    val backedUpAtMs: Long,
    val backupFileName: String,
)

/**
 * Manages original-byte backups in app-private `filesDir/originals/`.
 *
 * Uses `org.json.JSONObject` so backup/restore/pruning can be unit-tested on the JVM.
 */
class BackupStore(private val originalsDir: File) {
    /** Saves [bytes] and metadata for [row] atomically before an in-place rewrite. */
    fun save(
        row: MediaRow,
        bytes: InputStream,
        nowMs: Long = System.currentTimeMillis(),
    ): BackupMetadata {
        if (!originalsDir.exists() && !originalsDir.mkdirs()) {
            throw IllegalStateException("Cannot create backup dir: $originalsDir")
        }

        val ext = if (row.mimeType.equals("image/jpeg", ignoreCase = true)) "jpg" else "png"
        val backupFileName = "${row.id}.$ext"
        val dataFile = File(originalsDir, backupFileName)
        val tmpDataFile = File(originalsDir, "${row.id}.$ext.tmp")
        val metaFile = metadataFile(row.id)
        val tmpMetaFile = File(originalsDir, "${row.id}.json.tmp")

        try {
            var copiedBytes = 0L
            bytes.use { input ->
                FileOutputStream(tmpDataFile).use { output ->
                    copiedBytes = input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (copiedBytes <= 0L) {
                throw IllegalStateException("Backup copy was empty for mediaId=${row.id}")
            }
            if (dataFile.exists()) {
                dataFile.delete()
            }
            if (!tmpDataFile.renameTo(dataFile)) {
                throw IllegalStateException("Failed to finalize backup file: $dataFile")
            }

            val metadata = BackupMetadata(
                mediaId = row.id,
                displayName = row.displayName,
                relativePath = row.relativePath,
                dataPath = row.dataPath,
                mimeType = row.mimeType,
                width = row.width,
                height = row.height,
                sizeBytes = if (row.sizeBytes > 0L) row.sizeBytes else copiedBytes,
                dateModifiedSec = row.dateModifiedSec,
                backedUpAtMs = nowMs,
                backupFileName = backupFileName,
            )

            tmpMetaFile.writeText(metadataToJson(metadata).toString())
            if (metaFile.exists()) {
                metaFile.delete()
            }
            if (!tmpMetaFile.renameTo(metaFile)) {
                throw IllegalStateException("Failed to write backup metadata: $metaFile")
            }

            return metadata
        } catch (t: Throwable) {
            tmpDataFile.delete()
            tmpMetaFile.delete()
            throw t
        }
    }

    fun hasBackup(mediaId: Long): Boolean {
        val meta = readMetadata(mediaId) ?: return false
        return File(originalsDir, meta.backupFileName).isFile
    }

    fun readMetadata(mediaId: Long): BackupMetadata? {
        val metaFile = metadataFile(mediaId)
        if (!metaFile.isFile) return null
        return runCatching {
            jsonToMetadata(JSONObject(metaFile.readText()))
        }.getOrNull()
    }

    fun backupFile(mediaId: Long): File? {
        val meta = readMetadata(mediaId) ?: return null
        val file = File(originalsDir, meta.backupFileName)
        return if (file.isFile) file else null
    }

    fun openBackupStream(mediaId: Long): InputStream? {
        val file = backupFile(mediaId) ?: return null
        return FileInputStream(file)
    }

    fun delete(mediaId: Long) {
        val meta = readMetadata(mediaId)
        if (meta != null) {
            File(originalsDir, meta.backupFileName).delete()
        } else {
            File(originalsDir, "$mediaId.png").delete()
            File(originalsDir, "$mediaId.jpg").delete()
        }
        metadataFile(mediaId).delete()
    }

    /** Deletes backup pairs older than [maxAgeMs] relative to [nowMs]. Returns number of removed backups. */
    fun pruneOlderThan(maxAgeMs: Long, nowMs: Long = System.currentTimeMillis()): Int {
        if (!originalsDir.isDirectory) return 0
        val files = originalsDir.listFiles() ?: return 0
        var pruned = 0
        for (file in files) {
            if (!file.name.endsWith(".json")) continue
            val id = file.name.removeSuffix(".json").toLongOrNull() ?: continue
            val meta = readMetadata(id)
            val ageMs = if (meta != null) nowMs - meta.backedUpAtMs else nowMs - file.lastModified()
            if (ageMs > maxAgeMs) {
                delete(id)
                pruned++
            }
        }
        return pruned
    }

    private fun metadataFile(mediaId: Long): File = File(originalsDir, "$mediaId.json")

    private fun metadataToJson(meta: BackupMetadata): JSONObject = JSONObject().apply {
        put("mediaId", meta.mediaId)
        put("displayName", meta.displayName)
        put("relativePath", meta.relativePath)
        if (meta.dataPath != null) put("dataPath", meta.dataPath) else put("dataPath", JSONObject.NULL)
        put("mimeType", meta.mimeType)
        put("width", meta.width)
        put("height", meta.height)
        put("sizeBytes", meta.sizeBytes)
        put("dateModifiedSec", meta.dateModifiedSec)
        put("backedUpAtMs", meta.backedUpAtMs)
        put("backupFileName", meta.backupFileName)
    }

    private fun jsonToMetadata(json: JSONObject): BackupMetadata = BackupMetadata(
        mediaId = json.getLong("mediaId"),
        displayName = json.getString("displayName"),
        relativePath = json.getString("relativePath"),
        dataPath = if (json.isNull("dataPath")) null else json.getString("dataPath"),
        mimeType = json.getString("mimeType"),
        width = json.getInt("width"),
        height = json.getInt("height"),
        sizeBytes = json.getLong("sizeBytes"),
        dateModifiedSec = json.getLong("dateModifiedSec"),
        backedUpAtMs = json.getLong("backedUpAtMs"),
        backupFileName = json.getString("backupFileName"),
    )
}
