package dev.mattdev0.crispshot.media

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.zip.Adler32
import java.util.zip.CRC32
import java.util.zip.Deflater

/** Thread-safe [PixelSource] over a fully loaded pixel array. */
class IntArrayPixelSource(
    override val width: Int,
    override val height: Int,
    override val hasAlpha: Boolean,
    private val pixels: IntArray,
) : PixelSource {
    init {
        require(pixels.size >= width * height) { "pixel array too small for ${width}x$height" }
    }

    override fun readRow(y: Int, buffer: IntArray) {
        System.arraycopy(pixels, y * width, buffer, 0, width)
    }
}

/** Raw PNG chunk payload to copy verbatim into the upscaled output. */
data class PngChunk(
    val type: String,
    val data: ByteArray,
)

/**
 * Row-by-row ARGB pixel reader (`0xAARRGGBB` ints) so [ScaledPngWriter] is JVM-testable.
 * [readRow] may be called concurrently from several threads.
 */
interface PixelSource {
    val width: Int
    val height: Int
    val hasAlpha: Boolean
    fun readRow(y: Int, buffer: IntArray)
}

/**
 * Streaming k× nearest-neighbor PNG encoder.
 *
 * Avoids allocating a k² scaled bitmap: writes each source row with PNG Filter 1 (`Sub`, where
 * `k - 1` repeated pixels become zero deltas) followed by `k - 1` rows with PNG Filter 2 (`Up`,
 * all zero bytes), while preserving ancillary color/EXIF chunks from the source PNG.
 */
object ScaledPngWriter {
    const val DEFAULT_DEFLATE_LEVEL = 1
    const val DEFAULT_SEGMENTS = 4

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    private val PRESERVED_CHUNK_TYPES = setOf(
        "sRGB",
        "iCCP",
        "cICP",
        "gAMA",
        "cHRM",
        "sBIT",
        "eXIf",
    )

    /** Extracts preserved ancillary chunks from [file] in original order without decoding pixels. */
    fun extractAncillaryChunks(file: File): List<PngChunk> =
        runCatching {
            FileInputStream(file).use { extractAncillaryChunks(it) }
        }.getOrDefault(emptyList())

    /** Extracts preserved ancillary chunks from a PNG [input] stream in original order. */
    fun extractAncillaryChunks(input: InputStream): List<PngChunk> {
        val dis = DataInputStream(BufferedInputStream(input, 16_384))
        val sig = ByteArray(8)
        try {
            dis.readFully(sig)
        } catch (_: EOFException) {
            return emptyList()
        }
        if (!sig.contentEquals(PNG_SIGNATURE)) return emptyList()

        val chunks = mutableListOf<PngChunk>()
        val typeBytes = ByteArray(4)
        while (true) {
            val length = try {
                dis.readInt()
            } catch (_: EOFException) {
                break
            }
            if (length < 0) break

            try {
                dis.readFully(typeBytes)
            } catch (_: EOFException) {
                break
            }
            val type = String(typeBytes, Charsets.US_ASCII)
            if (type == "IEND") break

            if (type in PRESERVED_CHUNK_TYPES && length <= MAX_ANCILLARY_CHUNK_BYTES) {
                val data = ByteArray(length)
                try {
                    dis.readFully(data)
                    val expectedCrc = dis.readInt()
                    val crc = CRC32().apply {
                        update(typeBytes)
                        update(data)
                    }.value.toInt()
                    if (crc == expectedCrc) {
                        chunks.add(PngChunk(type, data))
                    }
                } catch (_: EOFException) {
                    break
                }
            } else {
                if (!skipExactly(dis, length.toLong() + 4L)) break
            }
        }
        return chunks
    }

    /** Encodes [source] scaled by integer [factor] directly to [out] as a lossless PNG. */
    fun writeScaled(
        out: OutputStream,
        source: PixelSource,
        factor: Int,
        ancillaryChunks: List<PngChunk> = emptyList(),
        deflateLevel: Int = DEFAULT_DEFLATE_LEVEL,
        segments: Int = DEFAULT_SEGMENTS,
    ) {
        require(factor >= 1) { "factor must be >= 1, got $factor" }
        val srcW = source.width
        val srcH = source.height
        require(srcW > 0 && srcH > 0) { "invalid source size ${srcW}x$srcH" }

        val rowBuf = IntArray(srcW)
        val opaque = isAllOpaque(source, rowBuf)
        val colorType = if (opaque) 2 else 6
        val bpp = if (opaque) 3 else 4
        val outW = srcW * factor
        val outH = srcH * factor

        val dos = DataOutputStream(BufferedOutputStream(out, 65_536))
        dos.write(PNG_SIGNATURE)
        writeChunk(dos, "IHDR", buildIhdr(outW, outH, colorType))

        for (chunk in ancillaryChunks) {
            val payload = if (chunk.type == "eXIf" && factor > 1) {
                patchExifDimensions(chunk.data, outW, outH)
            } else {
                chunk.data
            }
            writeChunk(dos, chunk.type, payload)
        }

        val idatBytes = encodeIdat(source, factor, outW, opaque, bpp, deflateLevel, segments)
        writeChunk(dos, "IDAT", idatBytes)
        writeChunk(dos, "IEND", ByteArray(0))
        dos.flush()
    }

    private fun isAllOpaque(source: PixelSource, rowBuf: IntArray): Boolean {
        if (!source.hasAlpha) return true
        for (y in 0 until source.height) {
            source.readRow(y, rowBuf)
            for (x in 0 until source.width) {
                if ((rowBuf[x] ushr 24) != 0xFF) {
                    return false
                }
            }
        }
        return true
    }

    private fun buildIhdr(width: Int, height: Int, colorType: Int): ByteArray =
        ByteArrayOutputStream(13).apply {
            DataOutputStream(this).use { h ->
                h.writeInt(width)
                h.writeInt(height)
                h.writeByte(8) // bit depth
                h.writeByte(colorType)
                h.writeByte(0) // compression
                h.writeByte(0) // filter method
                h.writeByte(0) // interlace
            }
        }.toByteArray()

    /** Compressed output of one contiguous range of source rows. */
    private class Segment(
        val bytes: ByteArray,
        val adler: Long,
        val rawLength: Long,
    )

    /**
     * Builds the zlib stream for IDAT by compressing [segments] contiguous row ranges in parallel.
     *
     * Each range is raw-deflated on its own thread and ends on a byte boundary (`SYNC_FLUSH`), except
     * the last, which finishes the stream. The pieces are concatenated behind a zlib header and the
     * Adler-32 trailer is merged from the per-range checksums, giving one standard zlib stream.
     */
    private fun encodeIdat(
        source: PixelSource,
        factor: Int,
        outW: Int,
        opaque: Boolean,
        bpp: Int,
        deflateLevel: Int,
        segments: Int,
    ): ByteArray {
        val srcH = source.height
        val count = segments.coerceIn(1, srcH)
        val bounds = IntArray(count + 1) { ((it.toLong() * srcH) / count).toInt() }

        val results: List<Segment> = if (count == 1) {
            listOf(compressRows(source, 0, srcH, true, factor, outW, opaque, bpp, deflateLevel))
        } else {
            val pool = Executors.newFixedThreadPool(count) { r ->
                Thread(r, "png-deflate").apply { isDaemon = true }
            }
            try {
                val futures = (0 until count).map { i ->
                    pool.submit(
                        Callable {
                            compressRows(
                                source, bounds[i], bounds[i + 1], i == count - 1,
                                factor, outW, opaque, bpp, deflateLevel,
                            )
                        },
                    )
                }
                futures.map { future ->
                    try {
                        future.get()
                    } catch (e: ExecutionException) {
                        throw e.cause ?: e
                    }
                }
            } finally {
                pool.shutdownNow()
            }
        }

        val out = ByteArrayOutputStream(results.sumOf { it.bytes.size } + 6)
        out.write(0x78) // zlib header: deflate, 32K window
        out.write(0x01) // FCHECK for 0x78xx, fastest-compression hint
        var adler = 1L
        for (segment in results) {
            out.write(segment.bytes)
            adler = adler32Combine(adler, segment.adler, segment.rawLength)
        }
        out.write(((adler ushr 24) and 0xFF).toInt())
        out.write(((adler ushr 16) and 0xFF).toInt())
        out.write(((adler ushr 8) and 0xFF).toInt())
        out.write((adler and 0xFF).toInt())
        return out.toByteArray()
    }

    /** Filters and raw-deflates source rows `[startY, endY)` scaled by [factor]. */
    private fun compressRows(
        source: PixelSource,
        startY: Int,
        endY: Int,
        isLast: Boolean,
        factor: Int,
        outW: Int,
        opaque: Boolean,
        bpp: Int,
        deflateLevel: Int,
    ): Segment {
        val srcW = source.width
        val rowBuf = IntArray(srcW)
        val rowByteLen = 1 + outW * bpp
        val subRow = ByteArray(rowByteLen).apply { this[0] = 1 } // filter type 1: Sub
        val upRow = ByteArray(rowByteLen).apply { this[0] = 2 } // filter type 2: Up, all-zero deltas
        val batchRows = 32
        val batch = ByteArray(batchRows * rowByteLen)
        var batchCount = 0

        val deflater = Deflater(deflateLevel, true)
        val adler = Adler32()
        val compressed = ByteArrayOutputStream(maxOf(1024, ((endY - startY) * rowByteLen) / 16))
        val outBuf = ByteArray(65_536)
        var rawLength = 0L

        fun drain(flush: Int) {
            if (flush == Deflater.NO_FLUSH) {
                while (!deflater.needsInput()) {
                    val n = deflater.deflate(outBuf, 0, outBuf.size, Deflater.NO_FLUSH)
                    if (n > 0) compressed.write(outBuf, 0, n)
                }
            } else {
                // A flush must be repeated while it fills the whole output buffer.
                do {
                    val n = deflater.deflate(outBuf, 0, outBuf.size, flush)
                    if (n > 0) compressed.write(outBuf, 0, n)
                } while (n == outBuf.size)
            }
        }

        fun feedBatch() {
            if (batchCount == 0) return
            val len = batchCount * rowByteLen
            adler.update(batch, 0, len)
            rawLength += len
            deflater.setInput(batch, 0, len)
            drain(Deflater.NO_FLUSH)
            batchCount = 0
        }

        fun addRow(row: ByteArray) {
            System.arraycopy(row, 0, batch, batchCount * rowByteLen, rowByteLen)
            batchCount++
            if (batchCount == batchRows) feedBatch()
        }

        try {
            for (y in startY until endY) {
                source.readRow(y, rowBuf)
                var prevR = 0
                var prevG = 0
                var prevB = 0
                var prevA = 0
                for (x in 0 until srcW) {
                    val px = rowBuf[x]
                    val r = (px ushr 16) and 0xFF
                    val g = (px ushr 8) and 0xFF
                    val b = px and 0xFF
                    // The k-1 repeated copies of this pixel have zero Sub deltas; the array is zeroed
                    // at those positions and never written, so only the first copy is set.
                    val base = 1 + x * factor * bpp
                    subRow[base] = (r - prevR).toByte()
                    subRow[base + 1] = (g - prevG).toByte()
                    subRow[base + 2] = (b - prevB).toByte()
                    prevR = r
                    prevG = g
                    prevB = b
                    if (!opaque) {
                        val a = (px ushr 24) and 0xFF
                        subRow[base + 3] = (a - prevA).toByte()
                        prevA = a
                    }
                }
                addRow(subRow)
                for (rep in 1 until factor) {
                    addRow(upRow)
                }
            }
            feedBatch()
            if (isLast) {
                deflater.finish()
                while (!deflater.finished()) {
                    val n = deflater.deflate(outBuf)
                    if (n > 0) compressed.write(outBuf, 0, n)
                }
            } else {
                drain(Deflater.SYNC_FLUSH)
            }
        } finally {
            deflater.end()
        }
        return Segment(compressed.toByteArray(), adler.value, rawLength)
    }

    /** Combines Adler-32 checksums of two adjacent byte ranges (port of zlib's `adler32_combine`). */
    internal fun adler32Combine(adler1: Long, adler2: Long, len2: Long): Long {
        require(len2 >= 0L) { "len2 must be >= 0" }
        val base = 65_521L
        val rem = len2 % base
        var sum1 = adler1 and 0xFFFF
        var sum2 = (rem * sum1) % base
        sum1 += (adler2 and 0xFFFF) + base - 1
        sum2 += ((adler1 ushr 16) and 0xFFFF) + ((adler2 ushr 16) and 0xFFFF) + base - rem
        if (sum1 >= base) sum1 -= base
        if (sum1 >= base) sum1 -= base
        if (sum2 >= (base shl 1)) sum2 -= (base shl 1)
        if (sum2 >= base) sum2 -= base
        return sum1 or (sum2 shl 16)
    }

    private fun writeChunk(dos: DataOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        dos.writeInt(data.size)
        dos.write(typeBytes)
        if (data.isNotEmpty()) {
            dos.write(data)
        }
        val crc = CRC32().apply {
            update(typeBytes)
            if (data.isNotEmpty()) update(data)
        }
        dos.writeInt(crc.value.toInt())
    }

    private fun skipExactly(input: InputStream, bytesToSkip: Long): Boolean {
        var remaining = bytesToSkip
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                if (input.read() < 0) return false
                remaining--
            }
        }
        return true
    }

    /**
     * Updates TIFF `ImageWidth`/`ImageLength` (and `PixelXDimension`/`PixelYDimension` in ExifIFD)
     * inside an `eXIf` chunk so MediaProvider reads the upscaled dimensions rather than the original ones.
     */
    internal fun patchExifDimensions(exifData: ByteArray, newWidth: Int, newHeight: Int): ByteArray {
        val out = exifData.copyOf()
        // PNG eXIf stores raw TIFF bytes ("II*\0" or "MM\0*"); tolerate optional "Exif\0\0" prefix.
        val tiffBase = when {
            out.size >= 8 && isTiffHeader(out, 0) -> 0
            out.size >= 14 &&
                out[0] == 'E'.code.toByte() &&
                out[1] == 'x'.code.toByte() &&
                out[2] == 'i'.code.toByte() &&
                out[3] == 'f'.code.toByte() &&
                isTiffHeader(out, 6) -> 6
            else -> return out
        }
        val littleEndian = out[tiffBase] == 'I'.code.toByte()
        val ifd0Rel = readU32(out, tiffBase + 4, littleEndian)
        patchIfd(out, tiffBase, tiffBase + ifd0Rel, littleEndian, newWidth, newHeight, recurseExifIfd = true)
        return out
    }

    private fun isTiffHeader(buf: ByteArray, offset: Int): Boolean {
        if (offset + 8 > buf.size) return false
        val le = buf[offset] == 'I'.code.toByte() && buf[offset + 1] == 'I'.code.toByte() &&
            buf[offset + 2] == 42.toByte() && buf[offset + 3] == 0.toByte()
        val be = buf[offset] == 'M'.code.toByte() && buf[offset + 1] == 'M'.code.toByte() &&
            buf[offset + 2] == 0.toByte() && buf[offset + 3] == 42.toByte()
        return le || be
    }

    private fun patchIfd(
        buf: ByteArray,
        tiffBase: Int,
        ifdOffset: Int,
        littleEndian: Boolean,
        newWidth: Int,
        newHeight: Int,
        recurseExifIfd: Boolean,
    ) {
        if (ifdOffset < tiffBase || ifdOffset + 2 > buf.size) return
        val count = readU16(buf, ifdOffset, littleEndian)
        var pos = ifdOffset + 2
        for (i in 0 until count) {
            if (pos + 12 > buf.size) break
            val tag = readU16(buf, pos, littleEndian)
            val type = readU16(buf, pos + 2, littleEndian)
            val valCount = readU32(buf, pos + 4, littleEndian)
            when (tag) {
                TAG_IMAGE_WIDTH, TAG_PIXEL_X_DIM -> writeIfdScalar(buf, pos + 8, type, valCount, newWidth, littleEndian)
                TAG_IMAGE_LENGTH, TAG_PIXEL_Y_DIM -> writeIfdScalar(buf, pos + 8, type, valCount, newHeight, littleEndian)
                TAG_EXIF_IFD_POINTER -> if (recurseExifIfd) {
                    val subRel = readU32(buf, pos + 8, littleEndian)
                    patchIfd(buf, tiffBase, tiffBase + subRel, littleEndian, newWidth, newHeight, recurseExifIfd = false)
                }
            }
            pos += 12
        }
    }

    private fun writeIfdScalar(
        buf: ByteArray,
        valueOffset: Int,
        type: Int,
        count: Int,
        value: Int,
        littleEndian: Boolean,
    ) {
        if (count != 1 || valueOffset + 4 > buf.size) return
        when (type) {
            TIFF_SHORT -> if (value in 0..0xFFFF) writeU16(buf, valueOffset, value, littleEndian)
            TIFF_LONG -> writeU32(buf, valueOffset, value, littleEndian)
        }
    }

    private fun readU16(buf: ByteArray, off: Int, le: Boolean): Int = if (le) {
        (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)
    } else {
        ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)
    }

    private fun readU32(buf: ByteArray, off: Int, le: Boolean): Int = if (le) {
        (buf[off].toInt() and 0xFF) or
            ((buf[off + 1].toInt() and 0xFF) shl 8) or
            ((buf[off + 2].toInt() and 0xFF) shl 16) or
            ((buf[off + 3].toInt() and 0xFF) shl 24)
    } else {
        ((buf[off].toInt() and 0xFF) shl 24) or
            ((buf[off + 1].toInt() and 0xFF) shl 16) or
            ((buf[off + 2].toInt() and 0xFF) shl 8) or
            (buf[off + 3].toInt() and 0xFF)
    }

    private fun writeU16(buf: ByteArray, off: Int, v: Int, le: Boolean) {
        if (le) {
            buf[off] = (v and 0xFF).toByte()
            buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
        } else {
            buf[off] = ((v ushr 8) and 0xFF).toByte()
            buf[off + 1] = (v and 0xFF).toByte()
        }
    }

    private fun writeU32(buf: ByteArray, off: Int, v: Int, le: Boolean) {
        if (le) {
            buf[off] = (v and 0xFF).toByte()
            buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
            buf[off + 2] = ((v ushr 16) and 0xFF).toByte()
            buf[off + 3] = ((v ushr 24) and 0xFF).toByte()
        } else {
            buf[off] = ((v ushr 24) and 0xFF).toByte()
            buf[off + 1] = ((v ushr 16) and 0xFF).toByte()
            buf[off + 2] = ((v ushr 8) and 0xFF).toByte()
            buf[off + 3] = (v and 0xFF).toByte()
        }
    }

    private const val TAG_IMAGE_WIDTH = 0x0100
    private const val TAG_IMAGE_LENGTH = 0x0101
    private const val TAG_EXIF_IFD_POINTER = 0x8769
    private const val TAG_PIXEL_X_DIM = 0xA002
    private const val TAG_PIXEL_Y_DIM = 0xA003
    private const val TIFF_SHORT = 3
    private const val TIFF_LONG = 4
    private const val MAX_ANCILLARY_CHUNK_BYTES = 4 * 1024 * 1024
}
