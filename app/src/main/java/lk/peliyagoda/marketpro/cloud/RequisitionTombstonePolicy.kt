package lk.peliyagoda.marketpro.cloud

/** A conservative, independently tested rule for ONE deletable entity. */
internal object RequisitionTombstonePolicy {
    fun evaluate(
        mappedCloud: String?,
        reverseLocalKey: String?,
        localKey: String,
        remoteUuid: String,
        hasPending: Boolean,
        localRowExists: Boolean,
        localMatchesCheckpoint: Boolean,
        localMatchesRemote: Boolean,
        lastAppliedRevision: Long?,
        lastAckRevision: Long?,
        remoteRevision: Long
    ): DomainMergeDecision {
        if (remoteRevision <= 0L ||
            lastAppliedRevision != null && lastAppliedRevision <= 0L ||
            lastAckRevision != null && lastAckRevision <= 0L)
            return DomainMergeDecision.BLOCK_UNTRUSTED

        if (hasPending) return DomainMergeDecision.CONFLICT_LOCAL_PENDING
        if (reverseLocalKey != null && reverseLocalKey != localKey ||
            mappedCloud != null && mappedCloud != remoteUuid ||
            localRowExists && mappedCloud != remoteUuid)
            return DomainMergeDecision.CONFLICT_UUID_OR_BUSINESS_KEY

        if (lastAppliedRevision != null && remoteRevision < lastAppliedRevision ||
            lastAckRevision != null && remoteRevision < lastAckRevision)
            return DomainMergeDecision.CONFLICT_REVISION_OR_LOCAL_CONTENT

        if (!localRowExists && lastAppliedRevision == remoteRevision)
            return DomainMergeDecision.ALREADY_CURRENT

        if (localRowExists) {
            // No UUID-matched prior checkpoint? A local legacy row must not
            // be silently deleted. An ACK + exact pre-delete payload is safe.
            if (!(lastAppliedRevision != null && localMatchesCheckpoint ||
                lastAckRevision != null && localMatchesRemote))
                return DomainMergeDecision.REVIEW_UNBASELINED_LOCAL_ROW
            if (lastAppliedRevision == remoteRevision)
                return DomainMergeDecision.CONFLICT_REVISION_OR_LOCAL_CONTENT
        }
        return DomainMergeDecision.REMOVE_DELETED_REQUISITION
    }
}
