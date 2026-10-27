package lk.peliyagoda.marketpro.cloud

/**
 * Daily Cash Received and Daily Other Expenses are singleton rows in Cloud:
 * one row per workspace + business date.
 *
 * A phone can legitimately create a provisional local UUID before it learns
 * that another approved phone already created the Cloud row for that date.
 * In that narrow case we may adopt the canonical remote UUID without touching
 * the local value that is still pending upload.
 *
 * This policy is intentionally strict. It never rebinds purchases/lots,
 * acknowledged UUIDs, tombstones, remote IDs already mapped elsewhere, or
 * rows without a durable local pending edit.
 */
internal data class DailySingletonIdentityEvidence(
    val type: String,
    val localRowExists: Boolean,
    val hasPendingLocalEdit: Boolean,
    val remoteTombstone: Boolean,
    val localKey: String,
    val mappedCloudId: String?,
    val remoteCloudId: String,
    val reverseLocalKey: String?,
    val provisionalOriginMatches: Boolean,
    val oldMappedPullRevision: Long?,
    val oldMappedAckRevision: Long?
)

internal object DailySingletonIdentityPolicy {
    private val singletonTypes = setOf(
        "daily_cash_received",
        "daily_other_expenses"
    )

    fun canAdoptCanonicalRemote(e: DailySingletonIdentityEvidence): Boolean {
        if (e.type !in singletonTypes) return false
        if (!e.localRowExists || !e.hasPendingLocalEdit || e.remoteTombstone) return false

        val old = e.mappedCloudId ?: return false
        if (old == e.remoteCloudId) return false
        if (!CloudInboxPagePolicy.uuid(old) || !CloudInboxPagePolicy.uuid(e.remoteCloudId)) {
            return false
        }

        if (e.reverseLocalKey != null && e.reverseLocalKey != e.localKey) return false
        if (!e.provisionalOriginMatches) return false

        // Once the provisional UUID has any trusted pull/ACK history, identity
        // is established and automatic rebinding is no longer safe.
        if (e.oldMappedPullRevision != null || e.oldMappedAckRevision != null) return false

        return true
    }
}
