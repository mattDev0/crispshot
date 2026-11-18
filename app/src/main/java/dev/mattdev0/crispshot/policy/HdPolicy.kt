package dev.mattdev0.crispshot.policy

/**
 * Folder + optional filename rule matching screenshots that should be auto-upscaled.
 *
 * When [matchAnywhere] is true, [pathPrefix] matches anywhere in `RELATIVE_PATH`; otherwise
 * `RELATIVE_PATH` must start with [pathPrefix].
 */
data class ScreenshotSource(
    val pathPrefix: String,
    val namePattern: String? = null,
    val matchAnywhere: Boolean = false,
)

/**
 * Thresholds and scaling configuration for WhatsApp's HD photo option.
 *
 * WhatsApp offers HD only when max(width, height) >= 2800 (a 2400 px long edge fails even at
 * 5.18 MP) and caps HD output at 2560 px on the long edge, downscaling by powers of two first.
 */
data class HdPolicy(
    val hdMinPixels: Long = 0L,
    val hdMinLongEdge: Int = 2800,
    val hdMaxLongEdge: Int = 2560,
    val strategy: ScaleStrategy = ScaleStrategy.IntegerNearest(2),
    val maxOutputPixels: Long = 12_000_000L,
    val recentWindowSec: Long = 120L,
    val sources: List<ScreenshotSource> = DEFAULT_SOURCES,
) {
    companion object {
        val PHONE_SOURCE = ScreenshotSource(
            pathPrefix = "Screenshots/",
            namePattern = null,
            matchAnywhere = true,
        )
        val QUICK_SHARE_SOURCES = listOf(
            ScreenshotSource(
                pathPrefix = "Download/Quick Share/",
                namePattern = "(?i)^screenshot[ _-]",
            ),
            ScreenshotSource(
                pathPrefix = "Download/Nearby Share/",
                namePattern = "(?i)^screenshot[ _-]",
            ),
        )
        val DEFAULT_SOURCES = listOf(PHONE_SOURCE) + QUICK_SHARE_SOURCES

        fun sourcesFor(includeQuickShare: Boolean): List<ScreenshotSource> =
            if (includeQuickShare) DEFAULT_SOURCES else listOf(PHONE_SOURCE)
    }
}

/** Supported upscaling strategies. */
sealed interface ScaleStrategy {
    data class IntegerNearest(val factor: Int) : ScaleStrategy
    data class FitLongEdge(val target: Int) : ScaleStrategy
}
