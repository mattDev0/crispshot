package dev.mattdev0.crispshot.policy

import dev.mattdev0.crispshot.media.MediaRow

/**
 * Size a screenshot had when Crispshot last finished with it (after enlarging or restoring it).
 * A different size later means the file was edited, for example cropped, and is handled again.
 * [UNKNOWN] matches any size.
 */
data class ProcessedSize(val width: Int, val height: Int) {
    fun matches(w: Int, h: Int): Boolean = this == UNKNOWN || (width == w && height == h)

    companion object {
        val UNKNOWN = ProcessedSize(-1, -1)
    }
}

/** Structured reason for skipping a MediaStore row. */
enum class SkipReason {
    NOT_SCREENSHOT,
    PENDING,
    TOO_OLD,
    UNSUPPORTED_MIME,
    ALREADY_PROCESSED,
    POLICY_SKIPPED,
}

/** Filtering result for a single [MediaRow]. */
sealed interface Decision {
    data class Process(val plan: ScalePlan, val reason: String) : Decision
    data class Skip(val reasonCode: SkipReason, val reason: String) : Decision
}

/** Pure filter deciding whether a [MediaRow] should be upscaled. */
object ScreenshotFilter {
    private val SUPPORTED_MIME_TYPES = setOf("image/png", "image/jpeg")

    /** Evaluates all screenshot eligibility conditions in fixed order. */
    fun evaluate(
        row: MediaRow,
        nowEpochSec: Long,
        policy: HdPolicy,
        processed: Map<Long, ProcessedSize>,
    ): Decision {
        if (!matchesAnySource(row, policy.sources)) {
            return Decision.Skip(
                SkipReason.NOT_SCREENSHOT,
                "not in configured screenshot sources (${row.relativePath}${row.displayName})",
            )
        }
        if (row.isPending != 0) {
            return Decision.Skip(
                SkipReason.PENDING,
                "pending (is_pending=${row.isPending})",
            )
        }
        // An edit (e.g. a crop saved over the same file) counts as new, so use the latest change.
        val lastChangeSec = maxOf(row.dateAddedSec, row.dateModifiedSec)
        if (lastChangeSec < nowEpochSec - policy.recentWindowSec) {
            val ageSec = nowEpochSec - lastChangeSec
            return Decision.Skip(
                SkipReason.TOO_OLD,
                "too old (age=${ageSec}s > ${policy.recentWindowSec}s)",
            )
        }
        if (row.mimeType.lowercase() !in SUPPORTED_MIME_TYPES) {
            return Decision.Skip(
                SkipReason.UNSUPPORTED_MIME,
                "unsupported mime (${row.mimeType})",
            )
        }
        val recorded = processed[row.id]
        if (recorded != null && recorded.matches(row.width, row.height)) {
            return Decision.Skip(
                SkipReason.ALREADY_PROCESSED,
                "already processed (id=${row.id})",
            )
        }

        return when (val planResult = ScalePlanner.planDetailed(row.width, row.height, policy)) {
            is PlanResult.Planned -> Decision.Process(
                plan = planResult.plan,
                reason = "scale ${row.width}x${row.height} -> ${planResult.plan.targetWidth}x${planResult.plan.targetHeight}",
            )
            is PlanResult.Skipped -> Decision.Skip(
                SkipReason.POLICY_SKIPPED,
                planResult.reason,
            )
        }
    }

    private fun matchesAnySource(row: MediaRow, sources: List<ScreenshotSource>): Boolean =
        sources.any { source ->
            val pathMatches = if (source.matchAnywhere) {
                row.relativePath.contains(source.pathPrefix, ignoreCase = true)
            } else {
                row.relativePath.startsWith(source.pathPrefix, ignoreCase = true)
            }
            if (!pathMatches) {
                false
            } else if (source.namePattern == null) {
                true
            } else {
                Regex(source.namePattern).containsMatchIn(row.displayName)
            }
        }
}
