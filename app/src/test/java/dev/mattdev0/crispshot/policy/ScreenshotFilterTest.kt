package dev.mattdev0.crispshot.policy

import dev.mattdev0.crispshot.media.MediaRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotFilterTest {
    private val policy = HdPolicy()
    private val now = 1_760_000_000L

    private fun baseRow(
        id: Long = 101L,
        displayName: String = "Screenshot_20261118-120000.png",
        relativePath: String = "Pictures/Screenshots/",
        width: Int = 1080,
        height: Int = 2400,
        mimeType: String = "image/png",
        dateAddedSec: Long = now - 5L,
        isPending: Int = 0,
    ) = MediaRow(
        id = id,
        displayName = displayName,
        relativePath = relativePath,
        width = width,
        height = height,
        mimeType = mimeType,
        dateAddedSec = dateAddedSec,
        dateModifiedSec = dateAddedSec,
        isPending = isPending,
    )

    @Test
    fun validPngAndJpegScreenshots_areProcessed() {
        val pngDecision = ScreenshotFilter.evaluate(baseRow(mimeType = "image/png"), now, policy, emptySet())
        assertTrue(pngDecision is Decision.Process)
        assertEquals(
            ScalePlan(2160, 4800, filter = false, factor = 2),
            (pngDecision as Decision.Process).plan,
        )

        val jpgDecision = ScreenshotFilter.evaluate(baseRow(mimeType = "IMAGE/JPEG"), now, policy, emptySet())
        assertTrue(jpgDecision is Decision.Process)
    }

    @Test
    fun nonScreenshotsPath_isSkippedWithReason() {
        val decision = ScreenshotFilter.evaluate(
            baseRow(relativePath = "DCIM/Camera/"),
            now,
            policy,
            emptySet(),
        )
        assertTrue(decision is Decision.Skip)
        val skip = decision as Decision.Skip
        assertEquals(SkipReason.NOT_SCREENSHOT, skip.reasonCode)
        assertTrue(skip.reason.contains("DCIM/Camera/"))
    }

    @Test
    fun relativePathCheck_isCaseInsensitive() {
        val decision = ScreenshotFilter.evaluate(
            baseRow(relativePath = "Pictures/screenshots/"),
            now,
            policy,
            emptySet(),
        )
        assertTrue(decision is Decision.Process)
    }

    @Test
    fun pendingRow_isSkippedWithPendingReason() {
        val decision = ScreenshotFilter.evaluate(
            baseRow(isPending = 1),
            now,
            policy,
            emptySet(),
        )
        assertTrue(decision is Decision.Skip)
        val skip = decision as Decision.Skip
        assertEquals(SkipReason.PENDING, skip.reasonCode)
        assertTrue(skip.reason.contains("pending"))
    }

    @Test
    fun oldRow_isSkippedWithTooOldReason() {
        val decision = ScreenshotFilter.evaluate(
            baseRow(dateAddedSec = now - policy.recentWindowSec - 1L),
            now,
            policy,
            emptySet(),
        )
        assertTrue(decision is Decision.Skip)
        val skip = decision as Decision.Skip
        assertEquals(SkipReason.TOO_OLD, skip.reasonCode)
        assertTrue(skip.reason.contains("too old"))
    }

    @Test
    fun unsupportedMime_isSkippedWithUnsupportedMimeReason() {
        val decision = ScreenshotFilter.evaluate(
            baseRow(mimeType = "image/webp"),
            now,
            policy,
            emptySet(),
        )
        assertTrue(decision is Decision.Skip)
        val skip = decision as Decision.Skip
        assertEquals(SkipReason.UNSUPPORTED_MIME, skip.reasonCode)
        assertTrue(skip.reason.contains("image/webp"))
    }

    @Test
    fun alreadyProcessedId_isSkippedWithAlreadyProcessedReason() {
        val decision = ScreenshotFilter.evaluate(
            baseRow(id = 42L),
            now,
            policy,
            processedIds = setOf(42L),
        )
        assertTrue(decision is Decision.Skip)
        val skip = decision as Decision.Skip
        assertEquals(SkipReason.ALREADY_PROCESSED, skip.reasonCode)
        assertTrue(skip.reason.contains("42"))
    }

    @Test
    fun alreadyHdEligibleScreenshot_isSkippedWithPolicyReason() {
        val decision = ScreenshotFilter.evaluate(
            baseRow(width = 1080, height = 4000),
            now,
            policy,
            emptySet(),
        )
        assertTrue(decision is Decision.Skip)
        val skip = decision as Decision.Skip
        assertEquals(SkipReason.POLICY_SKIPPED, skip.reasonCode)
        assertTrue(skip.reason.contains("already HD-eligible"))
    }

    @Test
    fun quickShareLaptopScreenshot1920x1080_plans3840x2160() {
        val decision = ScreenshotFilter.evaluate(
            baseRow(
                displayName = "Screenshot 2026-11-17 200424.png",
                relativePath = "Download/Quick Share/",
                width = 1920,
                height = 1080,
            ),
            now,
            policy,
            emptySet(),
        )
        assertTrue(decision is Decision.Process)
        assertEquals(
            ScalePlan(3840, 2160, filter = false, factor = 2),
            (decision as Decision.Process).plan,
        )
    }

    @Test
    fun quickShareWideCrop1809x250_plans3618x500() {
        val decision = ScreenshotFilter.evaluate(
            baseRow(
                displayName = "Screenshot 2026-11-17 200424.png",
                relativePath = "Download/Quick Share/",
                width = 1809,
                height = 250,
            ),
            now,
            policy,
            emptySet(),
        )
        assertTrue(decision is Decision.Process)
        assertEquals(
            ScalePlan(3618, 500, filter = false, factor = 2),
            (decision as Decision.Process).plan,
        )
    }

    @Test
    fun nearbyShareMacScreenshot_isProcessed() {
        val decision = ScreenshotFilter.evaluate(
            baseRow(
                displayName = "Screenshot 2026-11-17 at 20.04.24.png",
                relativePath = "Download/Nearby Share/",
                width = 1920,
                height = 1080,
            ),
            now,
            policy,
            emptySet(),
        )
        assertTrue(decision is Decision.Process)
    }

    @Test
    fun quickShareNonScreenshotPhoto_isSkipped() {
        val decision = ScreenshotFilter.evaluate(
            baseRow(
                displayName = "photo.jpg",
                relativePath = "Download/Quick Share/",
                width = 1920,
                height = 1080,
                mimeType = "image/jpeg",
            ),
            now,
            policy,
            emptySet(),
        )
        assertTrue(decision is Decision.Skip)
        assertEquals(SkipReason.NOT_SCREENSHOT, (decision as Decision.Skip).reasonCode)
    }

    @Test
    fun screenshotInOtherDownloadSubfolder_isSkipped() {
        val decision = ScreenshotFilter.evaluate(
            baseRow(
                displayName = "Screenshot_x.png",
                relativePath = "Download/X/",
                width = 1920,
                height = 1080,
            ),
            now,
            policy,
            emptySet(),
        )
        assertTrue(decision is Decision.Skip)
        assertEquals(SkipReason.NOT_SCREENSHOT, (decision as Decision.Skip).reasonCode)
    }

    @Test
    fun quickShareDisabledInPolicy_skipsQuickShareScreenshot() {
        val phoneOnlyPolicy = policy.copy(sources = HdPolicy.sourcesFor(includeQuickShare = false))
        val decision = ScreenshotFilter.evaluate(
            baseRow(
                displayName = "Screenshot 2026-11-17 200424.png",
                relativePath = "Download/Quick Share/",
                width = 1920,
                height = 1080,
            ),
            now,
            phoneOnlyPolicy,
            emptySet(),
        )
        assertTrue(decision is Decision.Skip)
        assertEquals(SkipReason.NOT_SCREENSHOT, (decision as Decision.Skip).reasonCode)
    }
}
