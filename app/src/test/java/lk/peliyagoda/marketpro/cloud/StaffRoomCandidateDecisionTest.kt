package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class StaffRoomCandidateDecisionTest {
    private fun valid() = StaffRoomEvidence(
        snapshotTrusted = true,
        remoteDeleted = false,
        localExists = true,
        localNameMatchesCloud = true,
        localNameCandidateCount = 1,
        localCategoryMatchesCloud = true,
        localActiveMatchesCloud = true,
        verifiedCloudCategoryPresent = true,
        localPendingEdit = false,
        identityMappedToOtherUuid = false,
        remoteUuidMappedToOtherLocalKey = false,
        localAlreadyLinkedToSameRemote = false
    )

    @Test fun matchingRealRoomRowOnlyBecomesOwnerReviewCandidate() {
        assertEquals(StaffRoomCandidateStatus.READY_FOR_OWNER_REVIEW,
            StaffRoomCandidateDecision.evaluate(valid()))
    }
    @Test fun mismatchedWorkspaceOrMissingCloudSnapshotIsDenied() {
        assertEquals(StaffRoomCandidateStatus.UNTRUSTED_OR_MISSING_CLOUD_SNAPSHOT,
            StaffRoomCandidateDecision.evaluate(valid().copy(snapshotTrusted=false)))
    }
    @Test fun cloudTombstoneNeverUploadsForAutomaticBinding() {
        assertEquals(StaffRoomCandidateStatus.REMOTE_CATALOG_DELETED,
            StaffRoomCandidateDecision.evaluate(valid().copy(remoteDeleted=true)))
    }
    @Test fun anItemFromBackupCannotBeAdoptedWhenRoomRecordIsMissing() {
        assertEquals(StaffRoomCandidateStatus.LOCAL_ROW_MISSING,
            StaffRoomCandidateDecision.evaluate(valid().copy(localExists=false)))
    }
    @Test fun duplicateNamesAndNamesChangedLocallyBlockEvidence() {
        assertEquals(StaffRoomCandidateStatus.AMBIGUOUS_OR_CHANGED_NAME,
            StaffRoomCandidateDecision.evaluate(valid().copy(localNameCandidateCount=2)))
        assertEquals(StaffRoomCandidateStatus.AMBIGUOUS_OR_CHANGED_NAME,
            StaffRoomCandidateDecision.evaluate(valid().copy(localNameMatchesCloud=false)))
    }
    @Test fun remoteCategoryMustBeVerifiedAndMatchLocalItem() {
        assertEquals(StaffRoomCandidateStatus.CATEGORY_NOT_VERIFIED_OR_CHANGED,
            StaffRoomCandidateDecision.evaluate(valid().copy(verifiedCloudCategoryPresent=false)))
        assertEquals(StaffRoomCandidateStatus.CATEGORY_NOT_VERIFIED_OR_CHANGED,
            StaffRoomCandidateDecision.evaluate(valid().copy(localCategoryMatchesCloud=false)))
    }
    @Test fun changedActiveStatusBlocksOldCatalogCandidate() {
        assertEquals(StaffRoomCandidateStatus.ACTIVE_FLAG_CHANGED,
            StaffRoomCandidateDecision.evaluate(valid().copy(localActiveMatchesCloud=false)))
    }
    @Test fun queuedOfflineCatalogEditsAreNeverSilentlyIgnored() {
        assertEquals(StaffRoomCandidateStatus.UNSENT_LOCAL_EDITS,
            StaffRoomCandidateDecision.evaluate(valid().copy(localPendingEdit=true)))
    }
    @Test fun anotherCloudUuidOrAnotherLocalKeyConflictRequiresReview() {
        assertEquals(StaffRoomCandidateStatus.CONFLICTING_UUID_IDENTITY,
            StaffRoomCandidateDecision.evaluate(valid().copy(identityMappedToOtherUuid=true)))
        assertEquals(StaffRoomCandidateStatus.CONFLICTING_UUID_IDENTITY,
            StaffRoomCandidateDecision.evaluate(valid().copy(remoteUuidMappedToOtherLocalKey=true)))
    }
    @Test fun alreadyLinkedItemDoesNotGenerateRedundantOwnerApproval() {
        assertEquals(StaffRoomCandidateStatus.ALREADY_LINKED,
            StaffRoomCandidateDecision.evaluate(valid().copy(localAlreadyLinkedToSameRemote=true)))
    }
}
