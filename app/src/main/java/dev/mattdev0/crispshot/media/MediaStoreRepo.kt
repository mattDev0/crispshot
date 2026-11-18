package dev.mattdev0.crispshot.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import dev.mattdev0.crispshot.policy.ScreenshotSource
import dev.mattdev0.crispshot.watch.ScreenshotStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Queries MediaStore for screenshots, triggers MediaScanner, and exposes [MediaIo] for writing. */
class MediaStoreRepo(private val context: Context) : MediaIo, ScreenshotStore {
    private val resolver: ContentResolver = context.contentResolver

    override fun queryById(id: Long): MediaRow? {
        val uri = ContentUris.withAppendedId(IMAGES_URI, id)
        return resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) readRow(cursor) else null
        }
    }

    override fun queryRecent(sinceEpochSec: Long, sources: List<ScreenshotSource>): List<MediaRow> {
        if (sources.isEmpty()) return emptyList()
        val pathClauses = sources.joinToString(" OR ") {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        }
        val selection = "($pathClauses) AND (${MediaStore.Images.Media.DATE_ADDED} >= ? OR ${MediaStore.Images.Media.DATE_MODIFIED} >= ?)"
        val args = buildList {
            for (source in sources) {
                add(if (source.matchAnywhere) "%${source.pathPrefix}%" else "${source.pathPrefix}%")
            }
            add(sinceEpochSec.toString()) // DATE_ADDED
            add(sinceEpochSec.toString()) // DATE_MODIFIED
        }.toTypedArray()
        val sort = "${MediaStore.Images.Media._ID} DESC"
        val rows = mutableListOf<MediaRow>()
        resolver.query(IMAGES_URI, PROJECTION, selection, args, sort)?.use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(readRow(cursor))
            }
        }
        return rows
    }

    override fun openRead(row: MediaRow): InputStream {
        val uri = ContentUris.withAppendedId(IMAGES_URI, row.id)
        val resolverStream = runCatching { resolver.openInputStream(uri) }.getOrNull()
        if (resolverStream != null) return resolverStream

        val path = row.dataPath
        if (path != null) {
            return FileInputStream(File(path))
        }
        throw IllegalStateException("Unable to open read stream for mediaId=${row.id}")
    }

    override fun openWriteTruncate(row: MediaRow): WriteOpenResult {
        val uri = ContentUris.withAppendedId(IMAGES_URI, row.id)
        try {
            val stream = resolver.openOutputStream(uri, "wt")
            if (stream != null) {
                return WriteOpenResult(stream, WritePath.MEDIASTORE)
            }
        } catch (_: SecurityException) {
            // Fall back to direct FileOutputStream via MANAGE_EXTERNAL_STORAGE.
        }

        val path = row.dataPath
            ?: throw IllegalStateException("MediaStore openOutputStream failed and DATA path is null for id=${row.id}")
        return WriteOpenResult(FileOutputStream(File(path)), WritePath.FILE)
    }

    override fun setLastModified(row: MediaRow, dateModifiedSec: Long) {
        val path = row.dataPath ?: return
        if (dateModifiedSec > 0L) {
            File(path).setLastModified(dateModifiedSec * 1000L)
        }
    }

    override suspend fun rescan(row: MediaRow) {
        val path = row.dataPath ?: return
        withTimeoutOrNull(RESCAN_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { cont ->
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(path),
                    arrayOf(row.mimeType),
                ) { _, _ ->
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    override suspend fun queryDims(id: Long): Pair<Int, Int>? {
        val uri = ContentUris.withAppendedId(IMAGES_URI, id)
        val cols = arrayOf(MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT)
        return resolver.query(uri, cols, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val w = cursor.getInt(0)
                val h = cursor.getInt(1)
                w to h
            } else {
                null
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun readRow(cursor: Cursor): MediaRow {
        val dataIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
        val ownerIdx = cursor.getColumnIndex(MediaStore.Images.Media.OWNER_PACKAGE_NAME)
        return MediaRow(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)),
            displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)) ?: "",
            relativePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)) ?: "",
            dataPath = if (dataIdx >= 0) cursor.getString(dataIdx) else null,
            width = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)),
            height = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)),
            mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)) ?: "",
            dateAddedSec = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)),
            dateModifiedSec = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)),
            sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)),
            ownerPackageName = if (ownerIdx >= 0) cursor.getString(ownerIdx) else null,
            isPending = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.IS_PENDING)),
        )
    }

    companion object {
        val IMAGES_URI: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        private const val RESCAN_TIMEOUT_MS = 5_000L

        @Suppress("DEPRECATION")
        private val PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.OWNER_PACKAGE_NAME,
            MediaStore.Images.Media.IS_PENDING,
        )
    }
}
