package lk.peliyagoda.marketpro.cloud

/**
 * A Staff candidate must represent an actual, unchanged Room catalog record
 * AND a separately authenticated Cloud Inbox record. Name similarity is not
 * enough to authenticate identity or to bind a canonical UUID.
 *
 * This policy cannot approve or persist any mapping.
 */
internal enum class StaffCatalogCaptureDecision {
    READY_FOR_OWNER_REVIEW,
    REJECT_UNTRUSTED_CLOUD_SNAPSHOT,
    REJECT_MISSING_OR_CHANGED_LOCAL_ROW,
    WAIT_FOR_CLOUD_CATEGORY_MAPPING,
    REJECT_PENDING_LOCAL_EDIT,
    REJECT_ALREADY_BOUND_OR_COLLIDING_UUID
}

internal data class StaffCatalogCaptureEvidence(
    val exactWorkspaceAndCloudUuid: Boolean,
    val remoteRevisionPositive: Boolean,
    val remoteNotDeleted: Boolean,
    val localRecordExists: Boolean,
    val currentLocalFieldsMatchCloud: Boolean,
    val referencedCategoryExactlyMapped: Boolean,
    val pendingLocalEdit: Boolean,
    val localKeyHasCloudIdentity: Boolean,
    val cloudUuidHasLocalIdentity: Boolean
)

internal object StaffCatalogCapturePolicy {
    fun decide(
        e: StaffCatalogCaptureEvidence,
        categoryCandidate: Boolean
    ): StaffCatalogCaptureDecision {
        if (!e.exactWorkspaceAndCloudUuid || !e.remoteRevisionPositive ||
            !e.remoteNotDeleted) {
            return StaffCatalogCaptureDecision.REJECT_UNTRUSTED_CLOUD_SNAPSHOT
        }
        if (!e.localRecordExists || !e.currentLocalFieldsMatchCloud) {
            return StaffCatalogCaptureDecision.REJECT_MISSING_OR_CHANGED_LOCAL_ROW
        }
        if (!categoryCandidate && !e.referencedCategoryExactlyMapped) {
            return StaffCatalogCaptureDecision.WAIT_FOR_CLOUD_CATEGORY_MAPPING
        }
        if (e.pendingLocalEdit) {
            return StaffCatalogCaptureDecision.REJECT_PENDING_LOCAL_EDIT
        }
        if (e.localKeyHasCloudIdentity || e.cloudUuidHasLocalIdentity) {
            // Existing mapped items do NOT need an owner mapping request.
            return StaffCatalogCaptureDecision.REJECT_ALREADY_BOUND_OR_COLLIDING_UUID
        }
        return StaffCatalogCaptureDecision.READY_FOR_OWNER_REVIEW
    }
}
