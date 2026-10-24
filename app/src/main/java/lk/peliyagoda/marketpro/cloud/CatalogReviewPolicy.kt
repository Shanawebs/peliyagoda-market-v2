package lk.peliyagoda.marketpro.cloud

/**
 * These labels are review hints, NOT authenticated server approval.
 * Matching names/categories must never bind a Cloud UUID to Room IDs.
 */
internal enum class CatalogReviewStatus {
    NEEDS_OWNER_REVIEW,
    CATEGORY_MAPPING_REQUIRED,
    LOCAL_CATEGORY_MISMATCH,
    LOCAL_ITEM_NOT_FOUND,
    AMBIGUOUS_LOCAL_ITEM,
    ALREADY_MAPPED,
    IDENTITY_CONFLICT,
    PENDING_OFFLINE_EDIT,
    CLOUD_DELETED,
    UNTRUSTED_SNAPSHOT
}

internal data class CatalogReviewEvidence(
    val cloudRowTrusted: Boolean,
    val cloudDeleted: Boolean,
    val localNameMatches: Int,
    val localCategoryMatches: Boolean,
    val exactCloudCategoryMapped: Boolean,
    val pendingLocalEdit: Boolean,
    val localKeyAlreadyBoundToOtherUuid: Boolean,
    val cloudUuidAlreadyBoundToOtherLocalKey: Boolean,
    val forwardAndReverseMappingAgree: Boolean
)

internal object CatalogReviewPolicy {
    fun status(e: CatalogReviewEvidence, isItem: Boolean): CatalogReviewStatus {
        if (!e.cloudRowTrusted) return CatalogReviewStatus.UNTRUSTED_SNAPSHOT
        if (e.cloudDeleted) return CatalogReviewStatus.CLOUD_DELETED
        if (e.localKeyAlreadyBoundToOtherUuid ||
            e.cloudUuidAlreadyBoundToOtherLocalKey
        ) return CatalogReviewStatus.IDENTITY_CONFLICT
        if (e.pendingLocalEdit) return CatalogReviewStatus.PENDING_OFFLINE_EDIT
        if (e.localNameMatches == 0) return CatalogReviewStatus.LOCAL_ITEM_NOT_FOUND
        if (e.localNameMatches != 1) return CatalogReviewStatus.AMBIGUOUS_LOCAL_ITEM
        if (isItem && !e.localCategoryMatches)
            return CatalogReviewStatus.LOCAL_CATEGORY_MISMATCH
        if (isItem && !e.exactCloudCategoryMapped)
            return CatalogReviewStatus.CATEGORY_MAPPING_REQUIRED
        if (e.forwardAndReverseMappingAgree) return CatalogReviewStatus.ALREADY_MAPPED
        return CatalogReviewStatus.NEEDS_OWNER_REVIEW
    }
}

internal data class CatalogReviewRow(
    val type: String,
    val cloudName: String,
    val cloudCategoryName: String?,
    val localName: String?,
    val localCategoryName: String?,
    val localKey: String?,
    val cloudUuid: String,
    val cloudRevision: Long,
    val cloudDeleted: Boolean,
    val status: CatalogReviewStatus
)
