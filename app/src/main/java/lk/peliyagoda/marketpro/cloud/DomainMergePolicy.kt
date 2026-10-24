package lk.peliyagoda.marketpro.cloud

/**
 * Conservative, independently-testable decision boundary for applying a Cloud
 * shadow record to the user's local Room domain tables.
 *
 * UUID-matched identity is mandatory for any existing local row. A matching
 * item NAME or business date alone is never proof of identity.
 */
data class DomainMergeEvidence(
    val workspaceMatches: Boolean,
    val recordIdentityIsValid: Boolean,
    val referencedItemUuidIsMapped: Boolean,
    val hasPendingLocalEdit: Boolean,
    val localBusinessKeyExists: Boolean,
    val localBusinessKeyMapsToIncomingUuid: Boolean,
    val incomingUuidMapsToAnotherBusinessKey: Boolean,
    val remoteTombstone: Boolean,
    val remoteRevision: Long,
    val lastAppliedRevision: Long?,
    val localContentsMatchRemote: Boolean,
    val localContentsMatchLastApplied: Boolean
)

enum class DomainMergeDecision {
    INSERT_NEW, UPDATE_EXISTING, ACK_MATCHING_EXISTING,
    ALREADY_CURRENT, BLOCK_UNTRUSTED,
    WAIT_FOR_EXACT_ITEM_UUID_MAPPING,
    CONFLICT_LOCAL_PENDING, CONFLICT_UUID_OR_BUSINESS_KEY,
    REVIEW_REMOTE_TOMBSTONE, REVIEW_UNBASELINED_LOCAL_ROW,
    CONFLICT_REVISION_OR_LOCAL_CONTENT
}

object DomainMergePolicy {
    fun evaluate(e: DomainMergeEvidence): DomainMergeDecision {
        if (!e.workspaceMatches || !e.recordIdentityIsValid ||
            e.remoteRevision < 1L ||
            (e.lastAppliedRevision != null && e.lastAppliedRevision < 1L)) {
            return DomainMergeDecision.BLOCK_UNTRUSTED
        }
        if (e.remoteTombstone) return DomainMergeDecision.REVIEW_REMOTE_TOMBSTONE
        if (!e.referencedItemUuidIsMapped) {
            return DomainMergeDecision.WAIT_FOR_EXACT_ITEM_UUID_MAPPING
        }
        if (e.hasPendingLocalEdit) return DomainMergeDecision.CONFLICT_LOCAL_PENDING
        if (e.incomingUuidMapsToAnotherBusinessKey ||
            (e.localBusinessKeyExists && !e.localBusinessKeyMapsToIncomingUuid)) {
            return DomainMergeDecision.CONFLICT_UUID_OR_BUSINESS_KEY
        }
        if (!e.localBusinessKeyExists) {
            return if (e.lastAppliedRevision == null) DomainMergeDecision.INSERT_NEW
            else DomainMergeDecision.CONFLICT_REVISION_OR_LOCAL_CONTENT
        }
        if (e.lastAppliedRevision == null) {
            // Existing Room entries may predate download integration. Never
            // silently replace an owner-entered/backup row. Baseline it only
            // if its CURRENT contents already agree with the server.
            return if (e.localContentsMatchRemote)
                DomainMergeDecision.ACK_MATCHING_EXISTING
            else DomainMergeDecision.REVIEW_UNBASELINED_LOCAL_ROW
        }
        if (e.remoteRevision < e.lastAppliedRevision) {
            return DomainMergeDecision.CONFLICT_REVISION_OR_LOCAL_CONTENT
        }
        if (e.remoteRevision == e.lastAppliedRevision) {
            return if (e.localContentsMatchRemote)
                DomainMergeDecision.ALREADY_CURRENT
            else DomainMergeDecision.CONFLICT_REVISION_OR_LOCAL_CONTENT
        }
        // Even if no outbox row is visible, a changed/restored local record
        // must not be overwritten unless it matches our previous checkpoint.
        return if (e.localContentsMatchLastApplied)
            DomainMergeDecision.UPDATE_EXISTING
        else DomainMergeDecision.CONFLICT_REVISION_OR_LOCAL_CONTENT
    }
}
