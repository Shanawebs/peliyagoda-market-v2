package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class StaffCatalogCapturePolicyTest {
    private fun ok() = StaffCatalogCaptureEvidence(
        exactWorkspaceAndCloudUuid=true,
        remoteRevisionPositive=true,
        remoteNotDeleted=true,
        localRecordExists=true,
        currentLocalFieldsMatchCloud=true,
        referencedCategoryExactlyMapped=true,
        pendingLocalEdit=false,
        localKeyHasCloudIdentity=false,
        cloudUuidHasLocalIdentity=false
    )
    private fun result(e:StaffCatalogCaptureEvidence=ok(), category:Boolean=false) =
        StaffCatalogCapturePolicy.decide(e,categoryCandidate=category)

    @Test fun explicitlySelectedItemWithVerifiedCategoryMayOnlyRequestOwnerReview() {
        assertEquals(StaffCatalogCaptureDecision.READY_FOR_OWNER_REVIEW,result())
    }

    @Test fun categoriesCanBeReviewedBeforeTheirCloudIdentityIsBound() {
        assertEquals(StaffCatalogCaptureDecision.READY_FOR_OWNER_REVIEW,
            result(ok().copy(referencedCategoryExactlyMapped=false),category=true))
    }

    @Test fun itemCannotSkipCategoryUuidBootstrap() {
        assertEquals(StaffCatalogCaptureDecision.WAIT_FOR_CLOUD_CATEGORY_MAPPING,
            result(ok().copy(referencedCategoryExactlyMapped=false)))
    }

    @Test fun missingOrRenamedLocalRoomRowStopsUpload() {
        assertEquals(StaffCatalogCaptureDecision.REJECT_MISSING_OR_CHANGED_LOCAL_ROW,
            result(ok().copy(localRecordExists=false)))
        assertEquals(StaffCatalogCaptureDecision.REJECT_MISSING_OR_CHANGED_LOCAL_ROW,
            result(ok().copy(currentLocalFieldsMatchCloud=false)))
    }

    @Test fun unsentOfflineEditsStopCandidateCaptureWithoutClearingOutbox() {
        assertEquals(StaffCatalogCaptureDecision.REJECT_PENDING_LOCAL_EDIT,
            result(ok().copy(pendingLocalEdit=true)))
    }

    @Test fun existingOrConflictingCloudMappingNeverTriggersDuplicateCandidate() {
        assertEquals(StaffCatalogCaptureDecision.REJECT_ALREADY_BOUND_OR_COLLIDING_UUID,
            result(ok().copy(localKeyHasCloudIdentity=true)))
        assertEquals(StaffCatalogCaptureDecision.REJECT_ALREADY_BOUND_OR_COLLIDING_UUID,
            result(ok().copy(cloudUuidHasLocalIdentity=true)))
    }

    @Test fun untrustedOldOrDeletedCloudSnapshotCannotBeSubmitted() {
        assertEquals(StaffCatalogCaptureDecision.REJECT_UNTRUSTED_CLOUD_SNAPSHOT,
            result(ok().copy(exactWorkspaceAndCloudUuid=false)))
        assertEquals(StaffCatalogCaptureDecision.REJECT_UNTRUSTED_CLOUD_SNAPSHOT,
            result(ok().copy(remoteRevisionPositive=false)))
        assertEquals(StaffCatalogCaptureDecision.REJECT_UNTRUSTED_CLOUD_SNAPSHOT,
            result(ok().copy(remoteNotDeleted=false)))
    }

    @Test fun normalizedCategoryKeyAgreesOnStaffAndOwnerReview() {
        val category=StaffCatalogCandidatePolicy.draft(
            StaffCandidateInputs(
                workspaceId="e46b90c5-ed6a-4952-82e9-8582fac06d89",
                deviceId="b1111111-1111-4111-8111-111111111111",
                cloudUuid="c1111111-1111-4111-8111-111111111111",
                cloudRevision=3,
                cloudWorkspaceMatches=true,
                cloudRecordDeleted=false,
                entityType="category",localKey="vegetables",
                localName="Vegetables",localCategory=null,localActive=null,
                localPendingEdit=false
            )
        )
        assertEquals("vegetables",category.localKey)
        val remote=OwnerRemoteCandidate(
            candidateUuid="a1111111-1111-4111-8111-111111111111",
            staffDeviceId=category.deviceId,
            staffAccountId="33333333-3333-4333-8333-333333333333",
            cloudUuid=category.cloudUuid,cloudRevision=category.cloudRevision,
            entityType=category.entityType,localItemKey=category.localKey,
            localName=category.localName,localCategory=null,localActive=null,
            localFingerprint=category.fingerprint
        )
        org.junit.Assert.assertTrue(
            OwnerRemoteCandidatePolicy.reviewable(remote,category.deviceId)
        )
    }
}
