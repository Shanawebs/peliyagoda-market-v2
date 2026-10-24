package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogLinkPolicyTest {
    private fun fresh() = CatalogLinkEvidence(
        trustedWorkspace=true,
        remoteIdValid=true,
        remoteRevisionValid=true,
        remoteDeleted=false,
        referencedCategoryVerified=true,
        hasPendingLocalEdit=false,
        normalizedNameCollision=false,
        localKeyForRemoteUuid=null,
        proposedLocalKey=null,
        remoteUuidForProposedKey=null,
        remoteUuidForProposedKeyMatchesIncoming=false,
        mappedLocalContentsEqual=false
    )

    private fun evaluate(e: CatalogLinkEvidence) = CatalogLinkPolicy.decide(e)

    @Test fun brandNewCloudItemWithVerifiedCategoryCanImport() {
        assertEquals(CatalogLinkDecision.IMPORT_NEW,evaluate(fresh()))
    }

    @Test fun matchingPreseededLocalNameRequiresOwnerMappingReview() {
        assertEquals(CatalogLinkDecision.REVIEW_NAME_COLLISION,
            evaluate(fresh().copy(normalizedNameCollision=true)))
    }

    @Test fun categoryMustHaveCanonicalUuidLinkBeforeItemImport() {
        assertEquals(CatalogLinkDecision.WAIT_FOR_VERIFIED_CATEGORY,
            evaluate(fresh().copy(referencedCategoryVerified=false)))
    }

    @Test fun alreadyMappedRecordMustAgreeInBothDirectionsAndContents() {
        val linked=fresh().copy(
            localKeyForRemoteUuid="17",
            proposedLocalKey="17",
            remoteUuidForProposedKey="f1111111-1111-4111-8111-111111111111",
            remoteUuidForProposedKeyMatchesIncoming=true,
            mappedLocalContentsEqual=true,
            normalizedNameCollision=true
        )
        assertEquals(CatalogLinkDecision.VERIFIED_ALREADY_LINKED,
            evaluate(linked))
        assertEquals(CatalogLinkDecision.REVIEW_CHANGED_EXISTING_CATALOG,
            evaluate(linked.copy(mappedLocalContentsEqual=false)))
        assertEquals(CatalogLinkDecision.CONFLICT_UUID_MAPPING,
            evaluate(linked.copy(remoteUuidForProposedKeyMatchesIncoming=false)))
        assertEquals(CatalogLinkDecision.CONFLICT_UUID_MAPPING,
            evaluate(linked.copy(localKeyForRemoteUuid="18")))
        assertEquals(CatalogLinkDecision.CONFLICT_UUID_MAPPING,
            evaluate(linked.copy(remoteUuidForProposedKey=null)))
    }

    @Test fun remoteTombstonesNeverAutomaticallyDeleteLocalCatalog() {
        assertEquals(CatalogLinkDecision.REVIEW_REMOTE_DELETION,
            evaluate(fresh().copy(remoteDeleted=true)))
    }

    @Test fun localOutboxConflictNeverOverwrittenOrCleared() {
        assertEquals(CatalogLinkDecision.CONFLICT_PENDING_LOCAL_EDIT,
            evaluate(fresh().copy(hasPendingLocalEdit=true)))
    }

    @Test fun badWorkspaceUuidOrRevisionAreFailClosed() {
        assertEquals(CatalogLinkDecision.REJECT_UNTRUSTED,
            evaluate(fresh().copy(trustedWorkspace=false)))
        assertEquals(CatalogLinkDecision.REJECT_UNTRUSTED,
            evaluate(fresh().copy(remoteIdValid=false)))
        assertEquals(CatalogLinkDecision.REJECT_UNTRUSTED,
            evaluate(fresh().copy(remoteRevisionValid=false)))
    }

    @Test fun mappingNotCreatedJustBecauseMatchingNameAndCategory() {
        val e=fresh().copy(normalizedNameCollision=true,
            referencedCategoryVerified=true,
            mappedLocalContentsEqual=true)
        assertEquals(CatalogLinkDecision.REVIEW_NAME_COLLISION,evaluate(e))
    }
}
