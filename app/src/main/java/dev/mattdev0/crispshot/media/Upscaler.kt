package dev.mattdev0.crispshot.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import androidx.core.graphics.scale
import dev.mattdev0.crispshot.policy.ScalePlan
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/** Per-step durations returned by [ImageScaler.upscaleToFile]. */
data class ScaleMetrics(
    val decodeMs: Long,
    val encodeMs: Long,
)

/** Abstraction over bitmap scaling and bounds verification so [InPlaceWriter] is JVM-testable. */
interface ImageScaler {
    fun upscaleToFile(inputFile: File, outputFile: File, plan: ScalePlan, mimeType: String): ScaleMetrics
    fun verifyBounds(file: File, expectedWidth: Int, expectedHeight: Int): Boolean
    fun verifyStreamBounds(input: InputStream, expectedWidth: Int, expectedHeight: Int): Boolean
}

/**
 * Decodes, scales, encodes, and bounds-verifies screenshots with no MediaStore dependencies.
 *
 * Uses [ScaledPngWriter] for integer PNG upscales (streaming rows without allocating a scaled
 * bitmap while preserving PNG color/EXIF chunks) and `Bitmap.createScaledBitmap` for filtered
 * fallbacks and JPEGs.
 */
class Upscaler(
    private val deflateLevel: Int = ScaledPngWriter.DEFAULT_DEFLATE_LEVEL,
) : ImageScaler {
    override fun upscaleToFile(
        inputFile: File,
        outputFile: File,
        plan: ScalePlan,
        mimeType: String,
    ): ScaleMetrics {
        var srcBitmap: Bitmap? = null
        var scaledBitmap: Bitmap? = null
        try {
            val tDecodeStart = System.currentTimeMillis()
            val source = ImageDecoder.createSource(inputFile)
            srcBitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
            val tDecodeEnd = System.currentTimeMillis()

            val isJpeg = mimeType.equals("image/jpeg", ignoreCase = true)
            val factor = plan.factor
            outputFile.parentFile?.mkdirs()

            if (!isJpeg && !plan.filter && factor != null && srcBitmap.config == Bitmap.Config.ARGB_8888) {
                val ancillary = ScaledPngWriter.extractAncillaryChunks(inputFile)
                val pixelSource = loadPixels(srcBitmap)
                // The pixels now live in an array that the encoder threads share; free the bitmap early.
                srcBitmap.recycle()
                FileOutputStream(outputFile).use { out ->
                    ScaledPngWriter.writeScaled(
                        out = out,
                        source = pixelSource,
                        factor = factor,
                        ancillaryChunks = ancillary,
                        deflateLevel = deflateLevel,
                    )
                    out.fd.sync()
                }
            } else {
                scaledBitmap = srcBitmap.scale(plan.targetWidth, plan.targetHeight, plan.filter)
                val format = if (isJpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
                val quality = if (isJpeg) JPEG_QUALITY else PNG_QUALITY
                FileOutputStream(outputFile).use { out ->
                    val ok = scaledBitmap.compress(format, quality, out)
                    if (!ok) {
                        throw IllegalStateException("Bitmap.compress returned false for ${outputFile.name}")
                    }
                    out.fd.sync()
                }
            }
            val tEncodeEnd = System.currentTimeMillis()
            return ScaleMetrics(
                decodeMs = tDecodeEnd - tDecodeStart,
                encodeMs = tEncodeEnd - tDecodeEnd,
            )
        } finally {
            if (scaledBitmap != null && scaledBitmap !== srcBitmap && !scaledBitmap.isRecycled) {
                scaledBitmap.recycle()
            }
            if (srcBitmap != null && !srcBitmap.isRecycled) {
                srcBitmap.recycle()
            }
        }
    }

    override fun verifyBounds(file: File, expectedWidth: Int, expectedHeight: Int): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return opts.outWidth == expectedWidth && opts.outHeight == expectedHeight
    }

    override fun verifyStreamBounds(
        input: InputStream,
        expectedWidth: Int,
        expectedHeight: Int,
    ): Boolean {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        input.use { BitmapFactory.decodeStream(it, null, opts) }
        return opts.outWidth == expectedWidth && opts.outHeight == expectedHeight
    }

    private fun loadPixels(bitmap: Bitmap): IntArrayPixelSource {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return IntArrayPixelSource(w, h, bitmap.hasAlpha(), pixels)
    }

    private companion object {
        const val PNG_QUALITY = 100
        const val JPEG_QUALITY = 95
    }
}
