package lk.peliyagoda.marketpro.cloud

/**
 * Local-only catalog import decision. A matching NAME is never enough to bind
 * an existing Room item to a canonical Cloud UUID. Such cases require explicit
 * owner review in a later server-authorized reconciliation workflow.
 */
data class CatalogLinkEvidence(
    val trustedWorkspace: Boolean,
    val remoteIdValid: Boolean,
    val remoteRevisionValid: Boolean,
    val remoteDeleted: Boolean,
    val referencedCategoryVerified: Boolean,
    val hasPendingLocalEdit: Boolean,
    val normalizedNameCollision: Boolean,
    val localKeyForRemoteUuid: String?,
    val proposedLocalKey: String?,
    val remoteUuidForProposedKey: String?,
    val mappedLocalContentsEqual: Boolean
)

enum class CatalogLinkDecision {
    IMPORT_NEW,
    VERIFIED_ALREADY_LINKED,
    WAIT_FOR_VERIFIED_CATEGORY,
    REVIEW_NAME_COLLISION,
    REVIEW_CHANGED_EXISTING_CATALOG,
    REVIEW_REMOTE_DELETION,
    CONFLICT_PENDING_LOCAL_EDIT,
    CONFLICT_UUID_MAPPING,
    REJECT_UNTRUSTED
}

object CatalogLinkPolicy {
    fun decide(e: CatalogLinkEvidence): CatalogLinkDecision {
        if (!e.trustedWorkspace || !e.remoteIdValid || !e.remoteRevisionValid) {
            return CatalogLinkDecision.REJECT_UNTRUSTED
        }
        if (e.remoteDeleted) return CatalogLinkDecision.REVIEW_REMOTE_DELETION
        if (!e.referencedCategoryVerified)
            return CatalogLinkDecision.WAIT_FOR_VERIFIED_CATEGORY
        if (e.hasPendingLocalEdit)
            return CatalogLinkDecision.CONFLICT_PENDING_LOCAL_EDIT

        val mappedFromRemote = e.localKeyForRemoteUuid
        val mappedFromLocal = e.remoteUuidForProposedKey
        if (mappedFromRemote != null || mappedFromLocal != null) {
            if (mappedFromRemote == null || mappedFromLocal == null ||
                mappedFromRemote != e.proposedLocalKey) {
                return CatalogLinkDecision.CONFLICT_UUID_MAPPING
            }
            // The caller MUST additionally compare reverse UUID == incoming
            // UUID; a supplied, already-linked but DIFFERENT UUID is rejected.
            return if (e.mappedLocalContentsEqual)
                CatalogLinkDecision.VERIFIED_ALREADY_LINKED
            else CatalogLinkDecision.REVIEW_CHANGED_EXISTING_CATALOG
        }
        if (e.normalizedNameCollision) {
            // Includes preseeded catalogs on staff phones. Never guess by name.
            return CatalogLinkDecision.REVIEW_NAME_COLLISION
        }
        return CatalogLinkDecision.IMPORT_NEW
    }
}
