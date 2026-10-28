package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class RequisitionTombstonePolicyTest {
    private fun decide(
        mapped: String? = "cloud-id",
        reverse: String? = "date|Main Kitchen|9",
        key: String = "date|Main Kitchen|9",
        pending: Boolean = false, exists: Boolean = true,
        checkpoint: Boolean = true, matches: Boolean = true,
        applied: Long? = 2, ack: Long? = null, revision: Long = 3
    ) = RequisitionTombstonePolicy.evaluate(
        mapped, reverse, key, "cloud-id", pending, exists,
        checkpoint, matches, applied, ack, revision)

    @Test fun syncedRowCanBeDeleted() {
        assertEquals(DomainMergeDecision.REMOVE_DELETED_REQUISITION, decide())
    }
    @Test fun localPendingEditIsNeverOverwritten() {
        assertEquals(DomainMergeDecision.CONFLICT_LOCAL_PENDING, decide(pending = true))
    }
    @Test fun wrongUuidIsProtected() {
        assertEquals(DomainMergeDecision.CONFLICT_UUID_OR_BUSINESS_KEY,
            decide(mapped = "other-id"))
    }
    @Test fun staleCloudRevisionIsProtected() {
        assertEquals(DomainMergeDecision.CONFLICT_REVISION_OR_LOCAL_CONTENT,
            decide(applied = 4))
    }
    @Test fun unbaselinedLocalRowIsProtected() {
        assertEquals(DomainMergeDecision.REVIEW_UNBASELINED_LOCAL_ROW,
            decide(applied = null, checkpoint = false, matches = false))
    }
    @Test fun oldVersionAckWithUnchangedContentsCanDelete() {
        assertEquals(DomainMergeDecision.REMOVE_DELETED_REQUISITION,
            decide(applied = null, checkpoint = false, ack = 2))
    }
    @Test fun freshlyLinkedPhoneLearnsRemoteTombstoneWithoutLocalRow() {
        assertEquals(DomainMergeDecision.REMOVE_DELETED_REQUISITION,
            decide(mapped = null, reverse = null, exists = false, applied = null))
    }
    @Test fun replayOfAppliedTombstoneDoesNotDeleteTwice() {
        assertEquals(DomainMergeDecision.ALREADY_CURRENT,
            decide(exists = false, applied = 3))
    }
    @Test fun newerLocalAckBlocksOldDelete() {
        assertEquals(DomainMergeDecision.CONFLICT_REVISION_OR_LOCAL_CONTENT,
            decide(ack = 4))
    }
}
