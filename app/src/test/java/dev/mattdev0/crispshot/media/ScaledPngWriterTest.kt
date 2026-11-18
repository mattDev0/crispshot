package dev.mattdev0.crispshot.media

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import javax.imageio.ImageIO
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaledPngWriterTest {
    private class ArrayPixelSource(
        override val width: Int,
        override val height: Int,
        override val hasAlpha: Boolean,
        private val pixels: IntArray,
    ) : PixelSource {
        override fun readRow(y: Int, buffer: IntArray) {
            System.arraycopy(pixels, y * width, buffer, 0, width)
        }
    }

    private data class RawChunk(val type: String, val data: ByteArray, val crcValid: Boolean)

    private fun parseAllChunks(pngBytes: ByteArray): List<RawChunk> {
        val dis = DataInputStream(ByteArrayInputStream(pngBytes))
        val sig = ByteArray(8)
        dis.readFully(sig)
        assertArrayEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            sig,
        )
        val chunks = mutableListOf<RawChunk>()
        while (dis.available() > 0) {
            val len = dis.readInt()
            val typeBytes = ByteArray(4)
            dis.readFully(typeBytes)
            val data = ByteArray(len)
            dis.readFully(data)
            val crcRead = dis.readInt()
            val crcCalc = CRC32().apply {
                update(typeBytes)
                update(data)
            }.value.toInt()
            chunks.add(RawChunk(String(typeBytes, Charsets.US_ASCII), data, crcRead == crcCalc))
        }
        return chunks
    }

    private fun buildTestChunkBytes(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(ByteBuffer.allocate(4).putInt(data.size).array())
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }.value.toInt()
        out.write(ByteBuffer.allocate(4).putInt(crc).array())
        return out.toByteArray()
    }

    @Test
    fun writeScaled_2xOpaqueRgb_decodesWithUniform2x2BlocksAndValidCrcs() {
        val w = 5
        val h = 4
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = (0xFF shl 24) or ((x * 50) shl 16) or ((y * 60) shl 8) or ((x + y) * 20)
            }
        }
        val source = ArrayPixelSource(w, h, hasAlpha = true, pixels = pixels)

        val out = ByteArrayOutputStream()
        ScaledPngWriter.writeScaled(out, source, factor = 2)
        val pngBytes = out.toByteArray()

        val chunks = parseAllChunks(pngBytes)
        assertTrue(chunks.all { it.crcValid })
        val ihdr = chunks.first { it.type == "IHDR" }
        assertEquals(2, ihdr.data[9].toInt()) // color type 2 = RGB when all opaque

        val decoded = ImageIO.read(ByteArrayInputStream(pngBytes))
        assertEquals(w * 2, decoded.width)
        assertEquals(h * 2, decoded.height)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val expected = pixels[y * w + x]
                for (dy in 0 until 2) {
                    for (dx in 0 until 2) {
                        assertEquals(expected, decoded.getRGB(x * 2 + dx, y * 2 + dy))
                    }
                }
            }
        }
    }

    @Test
    fun writeScaled_3xTranslucentRgba_decodesWithUniform3x3BlocksAndValidCrcs() {
        val w = 3
        val h = 3
        val pixels = intArrayOf(
            0x80102030.toInt(), 0xFF405060.toInt(), 0x40708090,
            0xFFAABBCC.toInt(), 0x00000000, 0xC0112233.toInt(),
            0xFF010203.toInt(), 0xFEFFFFFF.toInt(), 0x7F556677,
        )
        val source = ArrayPixelSource(w, h, hasAlpha = true, pixels = pixels)

        val out = ByteArrayOutputStream()
        ScaledPngWriter.writeScaled(out, source, factor = 3)
        val pngBytes = out.toByteArray()

        val chunks = parseAllChunks(pngBytes)
        assertTrue(chunks.all { it.crcValid })
        val ihdr = chunks.first { it.type == "IHDR" }
        assertEquals(6, ihdr.data[9].toInt()) // color type 6 = RGBA

        val decoded = ImageIO.read(ByteArrayInputStream(pngBytes))
        assertEquals(w * 3, decoded.width)
        assertEquals(h * 3, decoded.height)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val expected = pixels[y * w + x]
                for (dy in 0 until 3) {
                    for (dx in 0 until 3) {
                        assertEquals(expected, decoded.getRGB(x * 3 + dx, y * 3 + dy))
                    }
                }
            }
        }
    }

    @Test
    fun extractAncillaryChunks_preservesSrgbAndExifInOrderBeforeIdat() {
        val source = ArrayPixelSource(
            width = 2,
            height = 2,
            hasAlpha = false,
            pixels = intArrayOf(0xFF112233.toInt(), 0xFF445566.toInt(), 0xFF778899.toInt(), 0xFFAABBCC.toInt()),
        )
        val baseOut = ByteArrayOutputStream()
        ScaledPngWriter.writeScaled(baseOut, source, factor = 1)
        val baseBytes = baseOut.toByteArray()

        // Inject synthetic sRGB and eXIf chunks right after IHDR (offset 8 + 25 = 33).
        val srgbChunk = buildTestChunkBytes("sRGB", byteArrayOf(0))
        val exifPayload = "Exif\u0000\u0000MM\u0000*".toByteArray(Charsets.US_ASCII)
        val exifChunk = buildTestChunkBytes("eXIf", exifPayload)

        val withMeta = ByteArrayOutputStream().apply {
            write(baseBytes, 0, 33)
            write(srgbChunk)
            write(exifChunk)
            write(baseBytes, 33, baseBytes.size - 33)
        }.toByteArray()

        val extracted = ScaledPngWriter.extractAncillaryChunks(ByteArrayInputStream(withMeta))
        assertEquals(listOf("sRGB", "eXIf"), extracted.map { it.type })
        assertArrayEquals(exifPayload, extracted[1].data)

        val scaledOut = ByteArrayOutputStream()
        ScaledPngWriter.writeScaled(scaledOut, source, factor = 2, ancillaryChunks = extracted)
        val outChunks = parseAllChunks(scaledOut.toByteArray())
        assertTrue(outChunks.all { it.crcValid })
        val types = outChunks.map { it.type }
        assertEquals(listOf("IHDR", "sRGB", "eXIf", "IDAT", "IEND"), types)
        assertArrayEquals(exifPayload, outChunks.first { it.type == "eXIf" }.data)
    }

    @Test
    fun patchExifDimensions_updatesImageWidthLengthAndPixelDims_whilePreservingCrc() {
        // Minimal big-endian TIFF: header(8) + IFD0(2 + 3*12 + 4 = 42) + ExifIFD(2 + 2*12 + 4 = 30) = 80 bytes
        val buf = ByteBuffer.allocate(80)
        buf.put('M'.code.toByte()).put('M'.code.toByte()).putShort(42).putInt(8) // IFD0 at 8
        buf.putShort(3) // 3 entries
        // ImageWidth (SHORT = 1080)
        buf.putShort(0x0100).putShort(3).putInt(1).putShort(1080).putShort(0)
        // ImageLength (LONG = 2400)
        buf.putShort(0x0101).putShort(4).putInt(1).putInt(2400)
        // ExifIFDPointer at 50
        buf.putShort(0x8769.toShort()).putShort(4).putInt(1).putInt(50)
        buf.putInt(0) // next IFD
        // ExifIFD at 50
        buf.putShort(2)
        buf.putShort(0xA002.toShort()).putShort(4).putInt(1).putInt(1080)
        buf.putShort(0xA003.toShort()).putShort(4).putInt(1).putInt(2400)
        buf.putInt(0)

        val patched = ScaledPngWriter.patchExifDimensions(buf.array(), newWidth = 2160, newHeight = 4800)
        val view = ByteBuffer.wrap(patched)
        assertEquals(2160, view.getShort(18).toInt() and 0xFFFF)
        assertEquals(2400.let { 4800 }, view.getInt(30))
        assertEquals(2160, view.getInt(60))
        assertEquals(4800, view.getInt(72))
    }

    private fun randomPixels(w: Int, h: Int, seed: Long, opaque: Boolean): IntArray {
        val rnd = java.util.Random(seed)
        return IntArray(w * h) {
            // Runs of equal pixels mimic UI screenshots; random noise exercises literal coding.
            val v = if (rnd.nextInt(4) == 0) rnd.nextInt() else (it / 7) * 0x010203
            if (opaque) v or (0xFF shl 24) else v
        }
    }

    private fun idatPayload(pngBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parseAllChunks(pngBytes).filter { it.type == "IDAT" }.forEach { out.write(it.data) }
        return out.toByteArray()
    }

    private fun inflate(zlib: ByteArray): ByteArray {
        val inflater = java.util.zip.Inflater()
        inflater.setInput(zlib)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buf, 0, n)
        }
        assertTrue("zlib stream must end cleanly", inflater.finished())
        inflater.end()
        return out.toByteArray()
    }

    @Test
    fun writeScaled_segmentCountDoesNotChangeDecodedPixelsOrRawStream() {
        // 37 rows does not split evenly into 2, 3 or 4 segments.
        for ((opaque, factor) in listOf(true to 2, false to 3)) {
            val w = 23
            val h = 37
            val pixels = randomPixels(w, h, seed = 42L + factor, opaque = opaque)
            val source = IntArrayPixelSource(w, h, hasAlpha = !opaque, pixels = pixels)

            var referenceRaw: ByteArray? = null
            for (segments in 1..4) {
                val out = ByteArrayOutputStream()
                ScaledPngWriter.writeScaled(out, source, factor = factor, segments = segments)
                val pngBytes = out.toByteArray()
                assertTrue(parseAllChunks(pngBytes).all { it.crcValid })

                val raw = inflate(idatPayload(pngBytes))
                if (referenceRaw == null) referenceRaw = raw else assertArrayEquals(referenceRaw, raw)

                val decoded = ImageIO.read(ByteArrayInputStream(pngBytes))
                assertEquals(w * factor, decoded.width)
                assertEquals(h * factor, decoded.height)
                for (y in 0 until h * factor) {
                    for (x in 0 until w * factor) {
                        assertEquals(
                            "segments=$segments factor=$factor at ($x,$y)",
                            pixels[(y / factor) * w + (x / factor)],
                            decoded.getRGB(x, y),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun writeScaled_zlibTrailerMatchesAdler32OfWholeRawStream() {
        val w = 40
        val h = 50
        val source = IntArrayPixelSource(w, h, hasAlpha = false, pixels = randomPixels(w, h, 7L, true))
        val out = ByteArrayOutputStream()
        ScaledPngWriter.writeScaled(out, source, factor = 2, segments = 4)
        val zlib = idatPayload(out.toByteArray())
        val raw = inflate(zlib)

        val expected = java.util.zip.Adler32().apply { update(raw) }.value
        val trailer = ByteBuffer.wrap(zlib, zlib.size - 4, 4).int.toLong() and 0xFFFFFFFFL
        assertEquals(expected, trailer)
    }

    @Test
    fun adler32Combine_matchesSequentialChecksum() {
        val rnd = java.util.Random(3L)
        val data = ByteArray(200_000).also { rnd.nextBytes(it) }
        fun adler(from: Int, to: Int) =
            java.util.zip.Adler32().apply { update(data, from, to - from) }.value

        for (split in listOf(0, 1, 2, 65_521, 65_522, 100_000, data.size - 1, data.size)) {
            val combined = ScaledPngWriter.adler32Combine(adler(0, split), adler(split, data.size), (data.size - split).toLong())
            assertEquals("split=$split", adler(0, data.size), combined)
        }
        // Combining with an empty range is the identity in both directions.
        val whole = adler(0, data.size)
        assertEquals(whole, ScaledPngWriter.adler32Combine(1L, whole, data.size.toLong()))
        assertEquals(whole, ScaledPngWriter.adler32Combine(whole, 1L, 0L))
    }
}
