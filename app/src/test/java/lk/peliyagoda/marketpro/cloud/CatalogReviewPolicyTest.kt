package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogReviewPolicyTest {
    private fun evidence()=CatalogReviewEvidence(
        cloudRowTrusted=true,cloudDeleted=false,
        localNameMatches=1,localCategoryMatches=true,
        exactCloudCategoryMapped=true, pendingLocalEdit=false,
        localKeyAlreadyBoundToOtherUuid=false,
        cloudUuidAlreadyBoundToOtherLocalKey=false,
        forwardAndReverseMappingAgree=false
    )
    private fun result(e:CatalogReviewEvidence=evidence(),isItem:Boolean=true) =
        CatalogReviewPolicy.status(e,isItem)

    @Test fun matchingNameIsNotApproval() {
        assertEquals(CatalogReviewStatus.NEEDS_OWNER_REVIEW,result())
    }
    @Test fun importedCloudItemWithNoLocalRowIsNotAutomaticallyAdopted() {
        assertEquals(CatalogReviewStatus.LOCAL_ITEM_NOT_FOUND,
            result(evidence().copy(localNameMatches=0)))
    }
    @Test fun ambiguousItemCannotBeSelectedByName() {
        assertEquals(CatalogReviewStatus.AMBIGUOUS_LOCAL_ITEM,
            result(evidence().copy(localNameMatches=2)))
    }
    @Test fun categoryMustHaveVerifiedCanonicalUuidFirst() {
        assertEquals(CatalogReviewStatus.CATEGORY_MAPPING_REQUIRED,
            result(evidence().copy(exactCloudCategoryMapped=false)))
    }
    @Test fun localCategoryOrActiveFlagMismatchMustBeFlagged() {
        assertEquals(CatalogReviewStatus.LOCAL_CATEGORY_MISMATCH,
            result(evidence().copy(localCategoryMatches=false)))
    }
    @Test fun offlineEditsNeverMarkedReviewReady() {
        assertEquals(CatalogReviewStatus.PENDING_OFFLINE_EDIT,
            result(evidence().copy(pendingLocalEdit=true)))
    }
    @Test fun conflictingOrReusedUuidIsHighestSafetyConcern() {
        assertEquals(CatalogReviewStatus.IDENTITY_CONFLICT,
            result(evidence().copy(localKeyAlreadyBoundToOtherUuid=true)))
        assertEquals(CatalogReviewStatus.IDENTITY_CONFLICT,
            result(evidence().copy(cloudUuidAlreadyBoundToOtherLocalKey=true)))
    }
    @Test fun deletedOrUnverifiedCloudRowsCannotBeReviewedAsMatches() {
        assertEquals(CatalogReviewStatus.CLOUD_DELETED,
            result(evidence().copy(cloudDeleted=true)))
        assertEquals(CatalogReviewStatus.UNTRUSTED_SNAPSHOT,
            result(evidence().copy(cloudRowTrusted=false)))
    }
    @Test fun mappedEntryStillNeedsVisualVerificationWithoutNewApproval() {
        assertEquals(CatalogReviewStatus.ALREADY_MAPPED,
            result(evidence().copy(forwardAndReverseMappingAgree=true)))
    }
    @Test fun existingCategoryRequiresOwnerReviewButNotItemCategoryGate() {
        assertEquals(CatalogReviewStatus.NEEDS_OWNER_REVIEW,
            result(evidence().copy(exactCloudCategoryMapped=false),isItem=false))
    }
}
