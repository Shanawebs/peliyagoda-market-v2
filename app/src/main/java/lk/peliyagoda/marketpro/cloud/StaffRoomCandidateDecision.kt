package lk.peliyagoda.marketpro.cloud

/**
 * A read-only eligibility gate for uploading a CANDIDATE to Owner review,
 * NOT evidence of authorization and NOT approval to bind any UUID.
 * The server and the target phone must revalidate again at final approval.
 */
internal data class StaffRoomEvidence(
    val snapshotTrusted: Boolean,
    val remoteDeleted: Boolean,
    val localExists: Boolean,
    val localNameMatchesCloud: Boolean,
    val localNameCandidateCount: Int,
    val localCategoryMatchesCloud: Boolean,
    val localActiveMatchesCloud: Boolean,
    val verifiedCloudCategoryPresent: Boolean,
    val localPendingEdit: Boolean,
    val identityMappedToOtherUuid: Boolean,
    val remoteUuidMappedToOtherLocalKey: Boolean,
    val localAlreadyLinkedToSameRemote: Boolean
)

internal enum class StaffRoomCandidateStatus {
    READY_FOR_OWNER_REVIEW,
    UNTRUSTED_OR_MISSING_CLOUD_SNAPSHOT,
    REMOTE_CATALOG_DELETED,
    LOCAL_ROW_MISSING,
    AMBIGUOUS_OR_CHANGED_NAME,
    CATEGORY_NOT_VERIFIED_OR_CHANGED,
    ACTIVE_FLAG_CHANGED,
    UNSENT_LOCAL_EDITS,
    CONFLICTING_UUID_IDENTITY,
    ALREADY_LINKED
}

internal object StaffRoomCandidateDecision {
    fun evaluate(e: StaffRoomEvidence): StaffRoomCandidateStatus {
        if (!e.snapshotTrusted)
            return StaffRoomCandidateStatus.UNTRUSTED_OR_MISSING_CLOUD_SNAPSHOT
        if (e.remoteDeleted) return StaffRoomCandidateStatus.REMOTE_CATALOG_DELETED
        if (!e.localExists) return StaffRoomCandidateStatus.LOCAL_ROW_MISSING
        if (!e.localNameMatchesCloud || e.localNameCandidateCount != 1)
            return StaffRoomCandidateStatus.AMBIGUOUS_OR_CHANGED_NAME
        if (!e.verifiedCloudCategoryPresent || !e.localCategoryMatchesCloud)
            return StaffRoomCandidateStatus.CATEGORY_NOT_VERIFIED_OR_CHANGED
        if (!e.localActiveMatchesCloud)
            return StaffRoomCandidateStatus.ACTIVE_FLAG_CHANGED
        if (e.localPendingEdit) return StaffRoomCandidateStatus.UNSENT_LOCAL_EDITS
        if (e.identityMappedToOtherUuid || e.remoteUuidMappedToOtherLocalKey)
            return StaffRoomCandidateStatus.CONFLICTING_UUID_IDENTITY
        if (e.localAlreadyLinkedToSameRemote)
            return StaffRoomCandidateStatus.ALREADY_LINKED
        return StaffRoomCandidateStatus.READY_FOR_OWNER_REVIEW
    }
}
