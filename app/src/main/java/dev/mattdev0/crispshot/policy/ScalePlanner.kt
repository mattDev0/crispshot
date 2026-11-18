package dev.mattdev0.crispshot.policy

import kotlin.math.max
import kotlin.math.roundToInt

/** Target dimensions and resampling mode produced by [ScalePlanner]. */
data class ScalePlan(
    val targetWidth: Int,
    val targetHeight: Int,
    val filter: Boolean,
    val factor: Int? = null,
)

/** Detailed output of [ScalePlanner.planDetailed] for logging and filtering. */
sealed interface PlanResult {
    data class Planned(val plan: ScalePlan) : PlanResult
    data class Skipped(val reason: String) : PlanResult
}

/** Pure planner deciding whether and how to upscale an image under an [HdPolicy]. */
object ScalePlanner {
    private const val MIN_INTEGER_FACTOR = 2
    private const val MAX_INTEGER_FACTOR = 4

    /** Returns true if an image of size [width]x[height] already satisfies [policy]'s HD threshold. */
    fun isHdEligible(width: Int, height: Int, policy: HdPolicy): Boolean {
        if (width <= 0 || height <= 0) return false
        if (policy.hdMinPixels <= 0L && policy.hdMinLongEdge <= 0) return false

        val pixels = width.toLong() * height.toLong()
        val longEdge = max(width, height)
        val pixelsOk = policy.hdMinPixels <= 0L || pixels >= policy.hdMinPixels
        val edgeOk = policy.hdMinLongEdge <= 0 || longEdge >= policy.hdMinLongEdge
        return pixelsOk && edgeOk
    }

    /** Returns the [ScalePlan] for [width]x[height], or null if it should be skipped. */
    fun plan(width: Int, height: Int, policy: HdPolicy): ScalePlan? =
        (planDetailed(width, height, policy) as? PlanResult.Planned)?.plan

    /** Returns either a [PlanResult.Planned] or a [PlanResult.Skipped] with a diagnostic reason. */
    fun planDetailed(width: Int, height: Int, policy: HdPolicy): PlanResult {
        if (width <= 0 || height <= 0) {
            return PlanResult.Skipped("invalid dimensions (${width}x$height)")
        }
        if (isHdEligible(width, height, policy)) {
            return PlanResult.Skipped("already HD-eligible (${width}x$height)")
        }

        return when (val strategy = policy.strategy) {
            is ScaleStrategy.IntegerNearest -> {
                val integerResult = planIntegerNearest(width, height, strategy.factor, policy)
                if (integerResult is PlanResult.Planned || policy.hdMinLongEdge <= 0) {
                    integerResult
                } else {
                    // Fall back to smallest exact fit at hdMinLongEdge when no integer factor fits within maxOutputPixels.
                    planFitLongEdge(width, height, policy.hdMinLongEdge, policy)
                }
            }
            is ScaleStrategy.FitLongEdge -> planFitLongEdge(width, height, strategy.target, policy)
        }
    }

    private fun planIntegerNearest(
        width: Int,
        height: Int,
        minFactor: Int,
        policy: HdPolicy,
    ): PlanResult {
        val startFactor = max(MIN_INTEGER_FACTOR, minFactor)
        var hitPixelCap = false

        for (factor in startFactor..MAX_INTEGER_FACTOR) {
            val outW = width.toLong() * factor
            val outH = height.toLong() * factor
            if (outW > Int.MAX_VALUE || outH > Int.MAX_VALUE) {
                hitPixelCap = true
                break
            }
            val outPixels = outW * outH
            if (outPixels > policy.maxOutputPixels) {
                hitPixelCap = true
                break
            }
            val targetW = outW.toInt()
            val targetH = outH.toInt()
            if (isHdEligible(targetW, targetH, policy)) {
                return PlanResult.Planned(
                    ScalePlan(
                        targetWidth = targetW,
                        targetHeight = targetH,
                        filter = false,
                        factor = factor,
                    ),
                )
            }
        }

        val reason = if (hitPixelCap) {
            "exceeds maxOutputPixels (${policy.maxOutputPixels})"
        } else {
            "cannot reach HD eligibility within integer factors $startFactor..$MAX_INTEGER_FACTOR"
        }
        return PlanResult.Skipped(reason)
    }

    private fun planFitLongEdge(
        width: Int,
        height: Int,
        target: Int,
        policy: HdPolicy,
    ): PlanResult {
        val longEdge = max(width, height)
        if (target <= longEdge) {
            return PlanResult.Skipped("target long edge $target does not upscale ${width}x$height")
        }

        val (outW, outH) = if (height >= width) {
            val scaledW = ((width.toDouble() * target) / height).roundToInt().coerceAtLeast(1)
            scaledW to target
        } else {
            val scaledH = ((height.toDouble() * target) / width).roundToInt().coerceAtLeast(1)
            target to scaledH
        }

        if (outW.toLong() * outH.toLong() > policy.maxOutputPixels) {
            return PlanResult.Skipped("exceeds maxOutputPixels (${policy.maxOutputPixels})")
        }
        if (!isHdEligible(outW, outH, policy)) {
            return PlanResult.Skipped("planned ${outW}x$outH does not reach HD eligibility")
        }

        return PlanResult.Planned(
            ScalePlan(
                targetWidth = outW,
                targetHeight = outH,
                filter = true,
                factor = null,
            ),
        )
    }
}
