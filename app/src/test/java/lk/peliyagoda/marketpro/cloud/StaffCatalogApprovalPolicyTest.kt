package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class StaffCatalogApprovalPolicyTest {
    private val w="e46b90c5-ed6a-4952-82e9-8582fac06d89"
    private val d="b1111111-1111-4111-8111-111111111111"
    private val c="e1111111-1111-4111-8111-111111111111"
    private val o="22222222-2222-4222-8222-222222222222"
    private val proof=StaffCatalogServerApproval(
        approvalId="a1111111-1111-4111-8111-111111111111",
        workspaceId=w,deviceId=d,cloudUuid=c,
        type="item",localKey="29",revision=8,
        localFingerprint="a".repeat(64),reviewedByOwnerId=o,
        approvedAtEpochSeconds=1000,expiresAtEpochSeconds=3000,
        serverNowEpochSeconds=1200,
        approvedForDeviceRecheck=true,needsRoomRecheck=true
    )
    private fun result(
        p:StaffCatalogServerApproval=proof,
        workspace:String=w,device:String=d,
        type:String="item",cloudUuid:String=c,owner:String=o
    )=StaffCatalogApprovalPolicy.decide(p,workspace,device,type,cloudUuid,owner)

    @Test fun exactlyVerifiedServerProofRequiresANewRoomRecheck() {
        assertEquals(StaffCatalogApprovalDecision.READY_TO_RECHECK_ROOM,result())
    }
    @Test fun localPinOrUncheckedPayloadIsNotProof() {
        assertEquals(StaffCatalogApprovalDecision.REJECT_INVALID_SERVER_PROOF,
            result(proof.copy(approvedForDeviceRecheck=false)))
        assertEquals(StaffCatalogApprovalDecision.REJECT_INVALID_SERVER_PROOF,
            result(proof.copy(needsRoomRecheck=false)))
        assertEquals(StaffCatalogApprovalDecision.REJECT_INVALID_SERVER_PROOF,
            result(proof.copy(approvalId="not-uuid")))
    }
    @Test fun anotherPhoneOrAnotherWorkspaceIsDenied() {
        assertEquals(StaffCatalogApprovalDecision.REJECT_DIFFERENT_WORKSPACE_OR_DEVICE,
            result(device="d1111111-1111-4111-8111-111111111111"))
        assertEquals(StaffCatalogApprovalDecision.REJECT_DIFFERENT_WORKSPACE_OR_DEVICE,
            result(workspace="99999999-9999-4999-8999-999999999999"))
    }
    @Test fun wrongCloudItemOrChangedTypeCannotUseApproval() {
        assertEquals(StaffCatalogApprovalDecision.REJECT_DIFFERENT_CATALOG,
            result(cloudUuid="e2222222-2222-4222-8222-222222222222"))
        assertEquals(StaffCatalogApprovalDecision.REJECT_DIFFERENT_CATALOG,
            result(type="category"))
        assertEquals(StaffCatalogApprovalDecision.REJECT_DIFFERENT_CATALOG,
            result(proof.copy(localKey="00029")))
        assertEquals(StaffCatalogApprovalDecision.REJECT_DIFFERENT_CATALOG,
            result(proof.copy(revision=0)))
    }
    @Test fun differentOrRevokedOwnerRequiresServerApprovalAgain() {
        assertEquals(StaffCatalogApprovalDecision.REJECT_WRONG_OWNER,
            result(owner="44444444-4444-4444-8444-444444444444"))
    }
    @Test fun expiryUsesAuthoritativeServerClockNotPhoneClock() {
        assertEquals(StaffCatalogApprovalDecision.REJECT_EXPIRED_OR_STALE,
            result(proof.copy(serverNowEpochSeconds=3000)))
        assertEquals(StaffCatalogApprovalDecision.REJECT_EXPIRED_OR_STALE,
            result(proof.copy(serverNowEpochSeconds=999)))
        assertEquals(StaffCatalogApprovalDecision.REJECT_EXPIRED_OR_STALE,
            result(proof.copy(expiresAtEpochSeconds=100000)))
    }
    @Test fun swappedFingerprintCannotBeAppliedToUnrelatedRecord() {
        assertEquals(StaffCatalogApprovalDecision.REJECT_INVALID_SERVER_PROOF,
            result(proof.copy(localFingerprint="zz".repeat(32))))
    }
    @Test fun categoryProofStillRequiresLocalCategoryRecheck() {
        assertEquals(StaffCatalogApprovalDecision.READY_TO_RECHECK_ROOM,
            result(proof.copy(type="category",localKey="vegetables"),type="category"))
    }
}
