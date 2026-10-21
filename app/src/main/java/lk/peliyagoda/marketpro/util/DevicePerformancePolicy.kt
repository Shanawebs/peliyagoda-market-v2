package lk.peliyagoda.marketpro.util

/**
 * Keeps UI motion lightweight on entry-level devices without disabling
 * scrolling, editing, or any persistence features.
 *
 * Physical RAM is measured in binary bytes via ActivityManager.MemoryInfo.
 * Treat up to 5 GiB as constrained so common 4 GB phones get the small fade.
 */
object DevicePerformancePolicy {
    private const val LOW_MEMORY_THRESHOLD_BYTES = 5L * 1024L * 1024L * 1024L

    fun preferLightweightAnimations(totalMemoryBytes: Long, isLowRamDevice: Boolean): Boolean =
        isLowRamDevice ||
            (totalMemoryBytes > 0L && totalMemoryBytes <= LOW_MEMORY_THRESHOLD_BYTES)
}
