package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaffCatalogCandidatePolicyTest {
    private val workspace="e46b90c5-ed6a-4952-82e9-8582fac06d89"
    private val device="b1111111-1111-4111-8111-111111111111"
    private val cloud="e1111111-1111-4111-8111-111111111111"

    private fun input()=StaffCandidateInputs(
        workspaceId=workspace,deviceId=device,cloudUuid=cloud,
        cloudRevision=8,cloudWorkspaceMatches=true,cloudRecordDeleted=false,
        entityType="item",localKey="29",localName="Carrot",
        localCategory="Vegetables",localActive=true,localPendingEdit=false
    )

    @Test fun staffProvidesFullLocalDetailsForOwnerReviewNotAnApproval() {
        val output=StaffCatalogCandidatePolicy.draft(input())
        assertEquals("29",output.localKey)
        assertEquals(cloud,output.cloudUuid)
        assertEquals("Carrot",output.localName)
        assertEquals("Vegetables",output.localCategory)
        assertEquals(8L,output.cloudRevision)
        assertEquals(OwnerCatalogLinkPolicy.fingerprint(
            "Carrot","Vegetables",true),output.fingerprint)
    }

    @Test fun categoryUsesItsNaturalPrimaryKeyInsteadOfAutoId() {
        val output=StaffCatalogCandidatePolicy.draft(input().copy(
            entityType="category",localKey="Vegetables",
            localName="Vegetables",localCategory=null,localActive=null))
        assertEquals("Vegetables",output.localKey)
    }

    @Test(expected=IllegalArgumentException::class)
    fun cannotSubmitFromWrongCloudWorkspace() {
        StaffCatalogCandidatePolicy.draft(input().copy(cloudWorkspaceMatches=false))
    }

    @Test(expected=IllegalArgumentException::class)
    fun cannotSubmitDeletedCloudRecord() {
        StaffCatalogCandidatePolicy.draft(input().copy(cloudRecordDeleted=true))
    }

    @Test(expected=IllegalArgumentException::class)
    fun pendingLocalOutboxBlocksCandidate() {
        StaffCatalogCandidatePolicy.draft(input().copy(localPendingEdit=true))
    }

    @Test(expected=IllegalArgumentException::class)
    fun negativeOrAmbiguousItemKeyRejected() {
        StaffCatalogCandidatePolicy.draft(input().copy(localKey="00029"))
    }

    @Test(expected=IllegalArgumentException::class)
    fun missingCategoryOnItemRejected() {
        StaffCatalogCandidatePolicy.draft(input().copy(localCategory=null))
    }

    @Test(expected=IllegalArgumentException::class)
    fun fabricatedCloudRevisionRejected() {
        StaffCatalogCandidatePolicy.draft(input().copy(cloudRevision=0))
    }

    @Test fun ownerReviewMustBeForSpecificVerifiedStaffDevice() {
        val data=StaffCatalogCandidatePolicy.draft(input())
        val remote=OwnerRemoteCandidate(
            candidateUuid="a1111111-1111-4111-8111-111111111111",
            staffDeviceId=data.deviceId,
            staffAccountId="33333333-3333-4333-8333-333333333333",
            cloudUuid=data.cloudUuid,
            cloudRevision=data.cloudRevision,
            entityType=data.entityType,
            localItemKey=data.localKey,
            localName=data.localName,
            localCategory=data.localCategory,
            localActive=data.localActive,
            localFingerprint=data.fingerprint
        )
        assertTrue(OwnerRemoteCandidatePolicy.reviewable(remote,device))
        assertFalse(OwnerRemoteCandidatePolicy.reviewable(remote,
            "c1111111-1111-4111-8111-111111111111"))
        assertFalse(OwnerRemoteCandidatePolicy.reviewable(remote.copy(
            localFingerprint="not-a-real-fingerprint"),device))
        assertFalse(OwnerRemoteCandidatePolicy.reviewable(remote.copy(
            cloudRevision=0),device))
    }
}
