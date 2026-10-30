package lk.peliyagoda.marketpro.photo

/**
 * Read-only association for an Item Card on phones with different Room IDs.
 * Prefer a canonical server ID. Use a remote display name ONLY if it identifies
 * exactly one server item on that date; never rebind the local catalog or write.
 */
internal enum class PhotoCardMatchStatus { CANONICAL_ID, UNIQUE_NAME, AMBIGUOUS, NONE }

internal data class PhotoCardMatchResult(
    val photos: List<MarketPhotoGatewayClient.CloudPhoto>,
    val status: PhotoCardMatchStatus
)

internal object MarketPhotoCardMatchPolicy {
    fun hasAnyPhoto(localCount: Int, cloudCount: Int): Boolean =
        localCount > 0 || cloudCount > 0

    /** Timestamp of the latest signed Cloud original; never changes purchases. */
    fun latestCapturedAtMs(photos: List<MarketPhotoGatewayClient.CloudPhoto>): Long? =
        photos.mapNotNull { photo ->
            runCatching { java.time.Instant.parse(photo.capturedAt).toEpochMilli() }
                .getOrNull()?.takeIf { it > 0L }
        }.maxOrNull()

    fun match(
        dayPhotos: List<MarketPhotoGatewayClient.CloudPhoto>,
        linkedCloudItemId: String?,
        localItemName: String
    ): PhotoCardMatchResult {
        val canonical = linkedCloudItemId?.let { id ->
            dayPhotos.filter { it.itemId == id }
        }.orEmpty()
        if (canonical.isNotEmpty()) {
            return PhotoCardMatchResult(canonical, PhotoCardMatchStatus.CANONICAL_ID)
        }
        // Normalize presentation-only whitespace and Unicode composition.
        // Never use fuzzy or substring matching: a wrong receipt is worse
        // than an unmatched one, especially across separate owner/team phones.
        fun normalized(value: String): String =
            java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC)
                .trim().replace(Regex("\\s+"), " ")
        val name = normalized(localItemName)
        if (name.isEmpty() || name.equals("Unknown Item", ignoreCase = true)) {
            return PhotoCardMatchResult(emptyList(), PhotoCardMatchStatus.NONE)
        }
        val byName = dayPhotos.filter {
            it.itemId != null && it.itemName?.let(::normalized) == name
        }
        if (byName.isEmpty()) {
            return PhotoCardMatchResult(emptyList(), PhotoCardMatchStatus.NONE)
        }
        if (byName.mapNotNull { it.itemId }.distinct().size != 1) {
            return PhotoCardMatchResult(emptyList(), PhotoCardMatchStatus.AMBIGUOUS)
        }
        return PhotoCardMatchResult(byName, PhotoCardMatchStatus.UNIQUE_NAME)
    }
}
