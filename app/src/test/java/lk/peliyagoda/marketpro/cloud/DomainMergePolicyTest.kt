package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class DomainMergePolicyTest {
    private fun input() = DomainMergeEvidence(
        workspaceMatches=true, recordIdentityIsValid=true,
        referencedItemUuidIsMapped=true,
        hasPendingLocalEdit=false, localBusinessKeyExists=false,
        localBusinessKeyMapsToIncomingUuid=false,
        incomingUuidMapsToAnotherBusinessKey=false,
        remoteTombstone=false, remoteRevision=1,
        lastAppliedRevision=null, localContentsMatchRemote=false,
        localContentsMatchLastApplied=false
    )
    private fun result(e: DomainMergeEvidence) = DomainMergePolicy.evaluate(e)

    @Test fun newRemoteRecordCanInsertWithVerifiedItemAndNoLocalCollision() {
        assertEquals(DomainMergeDecision.INSERT_NEW,result(input()))
    }

    @Test fun cloudItemsNeverBindByMatchingNameOnly() {
        assertEquals(DomainMergeDecision.WAIT_FOR_EXACT_ITEM_UUID_MAPPING,
            result(input().copy(referencedItemUuidIsMapped=false)))
    }

    @Test fun unboundHistoricalLocalRowCannotBeSilentlyOverwritten() {
        assertEquals(DomainMergeDecision.CONFLICT_UUID_OR_BUSINESS_KEY,
            result(input().copy(localBusinessKeyExists=true)))
        assertEquals(DomainMergeDecision.REVIEW_UNBASELINED_LOCAL_ROW,
            result(input().copy(
                localBusinessKeyExists=true,
                localBusinessKeyMapsToIncomingUuid=true
            )))
    }

    @Test fun identicalUnbaselinedRowCanBeCheckpointedWithoutOverwrite() {
        assertEquals(DomainMergeDecision.ACK_MATCHING_EXISTING,
            result(input().copy(
                localBusinessKeyExists=true,
                localBusinessKeyMapsToIncomingUuid=true,
                localContentsMatchRemote=true
            )))
    }

    @Test fun ownerCorrectionBecomesValidOnlyAfterOldLocalBaselineMatched() {
        assertEquals(DomainMergeDecision.UPDATE_EXISTING,
            result(input().copy(
                remoteRevision=6,lastAppliedRevision=5,
                localBusinessKeyExists=true,
                localBusinessKeyMapsToIncomingUuid=true,
                localContentsMatchLastApplied=true
            )))
        assertEquals(DomainMergeDecision.CONFLICT_REVISION_OR_LOCAL_CONTENT,
            result(input().copy(
                remoteRevision=6,lastAppliedRevision=5,
                localBusinessKeyExists=true,
                localBusinessKeyMapsToIncomingUuid=true,
                localContentsMatchLastApplied=false
            )))
    }

    @Test fun pendingOfflineTeamEditCannotBeErasedByCloudImport() {
        assertEquals(DomainMergeDecision.CONFLICT_LOCAL_PENDING,
            result(input().copy(hasPendingLocalEdit=true)))
        assertEquals(DomainMergeDecision.CONFLICT_LOCAL_PENDING,
            result(input().copy(
                localBusinessKeyExists=true,
                localBusinessKeyMapsToIncomingUuid=true,
                lastAppliedRevision=4,remoteRevision=5,
                hasPendingLocalEdit=true
            )))
    }

    @Test fun reversedOrSpoofedIdentityCannotChangeLocalRecord() {
        assertEquals(DomainMergeDecision.CONFLICT_UUID_OR_BUSINESS_KEY,
            result(input().copy(incomingUuidMapsToAnotherBusinessKey=true)))
        assertEquals(DomainMergeDecision.BLOCK_UNTRUSTED,
            result(input().copy(workspaceMatches=false)))
        assertEquals(DomainMergeDecision.BLOCK_UNTRUSTED,
            result(input().copy(remoteRevision=0)))
    }

    @Test fun remoteDeletionRequiresSeparateOwnerReviewNeverHardDeletes() {
        assertEquals(DomainMergeDecision.REVIEW_REMOTE_TOMBSTONE,
            result(input().copy(remoteTombstone=true)))
    }

    @Test fun revisionCannotGoBackwardsOrClaimSameRevisionButDifferentContents() {
        val existing=input().copy(
            localBusinessKeyExists=true,localBusinessKeyMapsToIncomingUuid=true,
            localContentsMatchLastApplied=true,lastAppliedRevision=5
        )
        assertEquals(DomainMergeDecision.CONFLICT_REVISION_OR_LOCAL_CONTENT,
            result(existing.copy(remoteRevision=4)))
        assertEquals(DomainMergeDecision.CONFLICT_REVISION_OR_LOCAL_CONTENT,
            result(existing.copy(remoteRevision=5,localContentsMatchRemote=false)))
        assertEquals(DomainMergeDecision.ALREADY_CURRENT,
            result(existing.copy(remoteRevision=5,localContentsMatchRemote=true)))
    }

    @Test fun missingRecordWithEarlierAppliedCheckpointIsSuspicious() {
        assertEquals(DomainMergeDecision.CONFLICT_REVISION_OR_LOCAL_CONTENT,
            result(input().copy(lastAppliedRevision=5,remoteRevision=6)))
    }
}
