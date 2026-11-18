package dev.mattdev0.crispshot.media

/** Pure Kotlin representation of a MediaStore image cursor row. */
data class MediaRow(
    val id: Long,
    val displayName: String,
    val relativePath: String,
    val dataPath: String? = null,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val dateAddedSec: Long,
    val dateModifiedSec: Long,
    val sizeBytes: Long = 0L,
    val ownerPackageName: String? = null,
    val isPending: Int = 0,
)
