package dev.mattdev0.crispshot.policy

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScalePlannerTest {
    private val defaultPolicy = HdPolicy()

    @Test
    fun standardPixel6Portrait_plans2xNearest() {
        val plan = ScalePlanner.plan(1080, 2400, defaultPolicy)
        assertEquals(
            ScalePlan(targetWidth = 2160, targetHeight = 4800, filter = false, factor = 2),
            plan,
        )
    }

    @Test
    fun landscape2400x1080_plans2xNearest() {
        val plan = ScalePlanner.plan(2400, 1080, defaultPolicy)
        assertEquals(
            ScalePlan(targetWidth = 4800, targetHeight = 2160, filter = false, factor = 2),
            plan,
        )
    }

    @Test
    fun longScreenshot1080x4000_isSkippedAsAlreadyEligible() {
        val result = ScalePlanner.planDetailed(1080, 4000, defaultPolicy)
        assertTrue(result is PlanResult.Skipped)
        assertTrue((result as PlanResult.Skipped).reason.contains("already HD-eligible"))
        assertNull(ScalePlanner.plan(1080, 4000, defaultPolicy))
    }

    @Test
    fun exactThreshold1080x2800_isSkippedAsAlreadyEligible() {
        assertTrue(ScalePlanner.isHdEligible(1080, 2800, defaultPolicy))
        assertNull(ScalePlanner.plan(1080, 2800, defaultPolicy))
    }

    @Test
    fun crop1080x1200_stillUsesIntegerFactor3() {
        // 2x -> 2160x2400 fails the 2800 px long-edge rule; 3x -> 3240x3600 (11.66 MP <= 12 MP).
        val plan = ScalePlanner.plan(1080, 1200, defaultPolicy)
        assertEquals(
            ScalePlan(targetWidth = 3240, targetHeight = 3600, filter = false, factor = 3),
            plan,
        )
    }

    @Test
    fun portraitCrop1080x1300_fallsBackToExactFitLongEdge2800() {
        // 2x -> 2160x2600 fails edge >= 2800; 3x -> 3240x3900 (12.64 MP) exceeds 12 MP.
        val plan = ScalePlanner.plan(1080, 1300, defaultPolicy)
        assertEquals(
            ScalePlan(targetWidth = 2326, targetHeight = 2800, filter = true, factor = null),
            plan,
        )
    }

    @Test
    fun landscapeCrop1300x1080_fallsBackToExactFitLongEdge2800() {
        val plan = ScalePlanner.plan(1300, 1080, defaultPolicy)
        assertEquals(
            ScalePlan(targetWidth = 2800, targetHeight = 2326, filter = true, factor = null),
            plan,
        )
    }

    @Test
    fun smallCrop600x800_usesFactor4ToMeetEdgeRule() {
        // 2x -> 1200x1600, 3x -> 1800x2400 fail; 4x -> 2400x3200 (7.68 MP <= 12 MP).
        val plan = ScalePlanner.plan(600, 800, defaultPolicy)
        assertEquals(
            ScalePlan(targetWidth = 2400, targetHeight = 3200, filter = false, factor = 4),
            plan,
        )
    }

    @Test
    fun tinyCrop400x500_fallsBackToExactFitLongEdge2800() {
        val plan = ScalePlanner.plan(400, 500, defaultPolicy)
        assertEquals(
            ScalePlan(targetWidth = 2240, targetHeight = 2800, filter = true, factor = null),
            plan,
        )
    }

    @Test
    fun outputAboveMaxOutputPixels_isSkipped() {
        val tightCapPolicy = defaultPolicy.copy(maxOutputPixels = 5_000_000L)
        // 2500x2700 has long edge 2700 (< 2800); even fit(2800) = 2593x2800 = 7.26 MP > 5 MP.
        val result = ScalePlanner.planDetailed(2500, 2700, tightCapPolicy)
        assertTrue(result is PlanResult.Skipped)
        assertTrue((result as PlanResult.Skipped).reason.contains("maxOutputPixels"))
        assertNull(ScalePlanner.plan(2500, 2700, tightCapPolicy))
    }

    @Test
    fun invalidDimensions_areSkipped() {
        assertNull(ScalePlanner.plan(0, 2400, defaultPolicy))
        assertNull(ScalePlanner.plan(1080, -1, defaultPolicy))
    }

    @Test
    fun fitLongEdge_roundsAndPreservesAspectRatioWithin1Px() {
        val fit4096 = defaultPolicy.copy(strategy = ScaleStrategy.FitLongEdge(4096))
        val portraitPlan = ScalePlanner.plan(1080, 2400, fit4096)
        assertNotNull(portraitPlan)
        assertEquals(ScalePlan(1843, 4096, filter = true, factor = null), portraitPlan)

        val fit2800 = defaultPolicy.copy(strategy = ScaleStrategy.FitLongEdge(2800))
        val landscapePlan = ScalePlanner.plan(2400, 1080, fit2800)
        assertEquals(ScalePlan(2800, 1260, filter = true, factor = null), landscapePlan)

        val oddAspectPlan = requireNotNull(ScalePlanner.plan(1081, 2337, fit2800))
        assertTrue(oddAspectPlan.filter)
        assertEquals(2800, oddAspectPlan.targetHeight)
        val expectedWidth = 1081.0 * 2800.0 / 2337.0
        assertTrue(abs(oddAspectPlan.targetWidth - expectedWidth) < 1.0)
    }

    @Test
    fun fitLongEdge_aboveMaxOutputPixels_isSkipped() {
        val policy = defaultPolicy.copy(
            strategy = ScaleStrategy.FitLongEdge(5000),
            maxOutputPixels = 10_000_000L,
        )
        // 2000x2400 -> 4167x5000 = 20.8 MP > 10 MP
        val result = ScalePlanner.planDetailed(2000, 2400, policy)
        assertTrue(result is PlanResult.Skipped)
        assertFalse(ScalePlanner.isHdEligible(2000, 2400, policy))
    }
}
