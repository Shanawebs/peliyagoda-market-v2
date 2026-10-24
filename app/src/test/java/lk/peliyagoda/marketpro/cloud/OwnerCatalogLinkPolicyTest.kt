package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OwnerCatalogLinkPolicyTest {
    private val workspace="e46b90c5-ed6a-4952-82e9-8582fac06d89"
    private val staffDevice="b1111111-1111-4111-8111-111111111111"
    private val owner="22222222-2222-4222-8222-222222222222"
    private val staff="33333333-3333-4333-8333-333333333333"
    private val cloudItem="e1111111-1111-4111-8111-111111111111"
    private val hash=OwnerCatalogLinkPolicy.fingerprint("Carrot","Vegetables",true)
    private fun approval()=OwnerCatalogLinkApproval(
        approvalId="a1111111-1111-4111-8111-111111111111",
        workspaceId=workspace,targetDeviceId=staffDevice,
        type="item",localKey="29",cloudUuid=cloudItem,
        cloudRevision=8,localFingerprint=hash,ownerUserId=owner,
        approvedAtEpochSec=1000,expiresAtEpochSec=4000
    )
    private fun candidate()=CatalogLinkCandidate(
        workspaceId=workspace,physicalDeviceId=staffDevice,
        type="item",localKey="29",cloudUuid=cloudItem,
        cloudRevision=8,localFingerprint=hash,authenticatedUserId=staff,
        ownerUserId=owner,serverAuthenticatedApproval=true,
        nowEpochSec=2000,localPresent=true,remoteNotDeleted=true,
        exactCategoryUuidLinked=true,cloudAndLocalDetailsAgree=true,
        hasPendingLocalEdit=false,
        existingUuidForLocalKey=null,existingLocalKeyForUuid=null
    )

    private fun decision(c:CatalogLinkCandidate=candidate(),a:OwnerCatalogLinkApproval?=approval()) =
        OwnerCatalogLinkPolicy.decide(c,a)

    @Test fun authorizedOwnerReviewedExistingItemCanBeMappedWithoutRewriting() {
        assertEquals(OwnerCatalogLinkResult.CAN_LINK,decision())
    }
    @Test fun noApprovalOrLocallyForgedOwnerApprovalFlagIsDenied() {
        assertEquals(OwnerCatalogLinkResult.BLOCK_NO_TRUSTED_OWNER_APPROVAL,
            decision(a=null))
        assertEquals(OwnerCatalogLinkResult.BLOCK_NO_TRUSTED_OWNER_APPROVAL,
            decision(c=candidate().copy(serverAuthenticatedApproval=false)))
    }
    @Test fun wrongStaffPhoneCannotUseOtherDevicesApproval() {
        assertEquals(OwnerCatalogLinkResult.BLOCK_WRONG_DEVICE_OR_WORKSPACE,
            decision(c=candidate().copy(
                physicalDeviceId="d1111111-1111-4111-8111-111111111111")))
        assertEquals(OwnerCatalogLinkResult.BLOCK_WRONG_DEVICE_OR_WORKSPACE,
            decision(c=candidate().copy(workspaceId=
                "99999999-9999-4999-8999-999999999999")))
    }
    @Test fun replacedLocalRowOrCloudCatalogRevisionMustBeReviewedAgain() {
        assertEquals(OwnerCatalogLinkResult.BLOCK_CHANGED_OR_EXPIRED_APPROVAL,
            decision(c=candidate().copy(cloudRevision=9)))
        assertEquals(OwnerCatalogLinkResult.BLOCK_CHANGED_OR_EXPIRED_APPROVAL,
            decision(c=candidate().copy(localFingerprint=
                OwnerCatalogLinkPolicy.fingerprint("Potato","Vegetables",true))))
        assertEquals(OwnerCatalogLinkResult.BLOCK_CHANGED_OR_EXPIRED_APPROVAL,
            decision(c=candidate().copy(nowEpochSec=4000)))
        assertEquals(OwnerCatalogLinkResult.BLOCK_CHANGED_OR_EXPIRED_APPROVAL,
            decision(c=candidate().copy(nowEpochSec=999)))
    }
    @Test fun renamedOrDeletedCloudRowMustNotAdoptLocalHistory() {
        assertEquals(OwnerCatalogLinkResult.BLOCK_REVIEW_CHANGED_ITEM,
            decision(c=candidate().copy(cloudAndLocalDetailsAgree=false)))
        assertEquals(OwnerCatalogLinkResult.BLOCK_REVIEW_CHANGED_ITEM,
            decision(c=candidate().copy(remoteNotDeleted=false)))
    }
    @Test fun categoryMustHaveIndependentCloudUuidMapping() {
        assertEquals(OwnerCatalogLinkResult.BLOCK_UNVERIFIED_CATEGORY,
            decision(c=candidate().copy(exactCategoryUuidLinked=false)))
    }
    @Test fun pendingUnsentLocalItemCannotBeRelinked() {
        assertEquals(OwnerCatalogLinkResult.BLOCK_PENDING_LOCAL_EDIT,
            decision(c=candidate().copy(hasPendingLocalEdit=true)))
    }
    @Test fun oneRoomIdCannotBeBoundToDifferentCloudUuid() {
        assertEquals(OwnerCatalogLinkResult.BLOCK_IDENTITY_COLLISION,
            decision(c=candidate().copy(existingUuidForLocalKey=
                "e2222222-2222-4222-8222-222222222222")))
        assertEquals(OwnerCatalogLinkResult.BLOCK_IDENTITY_COLLISION,
            decision(c=candidate().copy(existingLocalKeyForUuid="30")))
    }
    @Test fun alreadyCorrectlyBoundItemCanBeAcknowledgedWithoutOverwriting() {
        assertEquals(OwnerCatalogLinkResult.ALREADY_LINKED,
            decision(c=candidate().copy(
                existingLocalKeyForUuid="29",existingUuidForLocalKey=cloudItem)))
    }
    @Test fun fingerprintProtectsChangesToCategoryAndActiveFlag() {
        assertFalse(hash==OwnerCatalogLinkPolicy.fingerprint("Carrot","Fruits",true))
        assertFalse(hash==OwnerCatalogLinkPolicy.fingerprint("Carrot","Vegetables",false))
        assertFalse(hash==OwnerCatalogLinkPolicy.fingerprint("Carrot2","Vegetables",true))
        assertEquals(hash,OwnerCatalogLinkPolicy.fingerprint("Carrot","Vegetables",true))
    }
}
