package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Extra finance-specific contract on the existing fail-closed domain merge.
 * These cases must be satisfied before a second phone can publish edits.
 */
class OtherExpensesMergeSafetyTest {
    private fun expected(
        remoteRevision:Long=2,
        localPending:Boolean=false,
        uuidMatches:Boolean=true,
        originalBusinessRow:Boolean=true,
        checkpoint:Long?=1,
        matchesOld:Boolean=true,
        matchesRemote:Boolean=false,
        tombstone:Boolean=false
    ): DomainMergeDecision = DomainMergePolicy.evaluate(
        DomainMergeEvidence(
            workspaceMatches=true,recordIdentityIsValid=true,
            referencedItemUuidIsMapped=true,hasPendingLocalEdit=localPending,
            localBusinessKeyExists=originalBusinessRow,
            localBusinessKeyMapsToIncomingUuid=uuidMatches,
            incomingUuidMapsToAnotherBusinessKey=!uuidMatches,
            remoteTombstone=tombstone,
            remoteRevision=remoteRevision,lastAppliedRevision=checkpoint,
            localContentsMatchRemote=matchesRemote,
            localContentsMatchLastApplied=matchesOld
        )
    )

    @Test fun serverCanUpdateOnlyExactMappedQuietExpenseRow() {
        assertEquals(DomainMergeDecision.UPDATE_EXISTING, expected())
        assertEquals(DomainMergeDecision.ACK_MATCHING_EXISTING,
            expected(remoteRevision=1,checkpoint=null,matchesRemote=true))
    }

    @Test fun unsentPhoneEditsAlwaysWinUntilConflictResolution() {
        assertEquals(DomainMergeDecision.CONFLICT_LOCAL_PENDING,
            expected(localPending=true))
    }

    @Test fun sameBusinessDateWithDifferentCloudUuidIsNeverOverwritten() {
        assertEquals(DomainMergeDecision.CONFLICT_UUID_OR_BUSINESS_KEY,
            expected(uuidMatches=false))
    }

    @Test fun localChangeSinceLastCloudCheckpointBlocksOverwrite() {
        assertEquals(DomainMergeDecision.CONFLICT_REVISION_OR_LOCAL_CONTENT,
            expected(matchesOld=false))
        assertEquals(DomainMergeDecision.REVIEW_UNBASELINED_LOCAL_ROW,
            expected(checkpoint=null))
    }

    @Test fun serverRollbackAndDeletedCloudRecordNeedManualReview() {
        assertEquals(DomainMergeDecision.CONFLICT_REVISION_OR_LOCAL_CONTENT,
            expected(remoteRevision=1,checkpoint=2))
        assertEquals(DomainMergeDecision.REVIEW_REMOTE_TOMBSTONE,
            expected(tombstone=true))
    }

    @Test fun newUnoccupiedLocalDateCanBeImportedButNotRevivedAfterOldCheckpoint() {
        assertEquals(DomainMergeDecision.INSERT_NEW,
            expected(originalBusinessRow=false,checkpoint=null))
        assertEquals(DomainMergeDecision.CONFLICT_REVISION_OR_LOCAL_CONTENT,
            expected(originalBusinessRow=false,checkpoint=1))
    }
}
