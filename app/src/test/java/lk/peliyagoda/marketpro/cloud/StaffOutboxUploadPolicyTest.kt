package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaffOutboxUploadPolicyTest {
    private val today="2026-10-24"
    private val workspaceStaff="33333333-3333-4333-8333-333333333333"
    private fun candidate(type:String="purchase",base:Long?=null,
        remote:Long?=null,origin:Boolean=true,date:String=today
    ):StaffOutboxUploadPolicy.Candidate = StaffOutboxUploadPolicy.Candidate(
        type=type,localKey=if(type=="purchase")"2026-10-24|5" else today,
        cloudUuid="11111111-1111-4111-8111-111111111111",
        operationUuid="a1111111-1111-4111-8111-111111111111",
        pendingAction="upsert",date=date,userUuid=workspaceStaff,
        ownerUuid="22222222-2222-4222-8222-222222222222",
        physicalDeviceUuid="f1111111-1111-4111-8111-111111111111",
        enrolledDeviceUuid="f1111111-1111-4111-8111-111111111111",
        cloudItemUuid="b1111111-1111-4111-8111-111111111111",
        observedRevision=remote,lastLocallyAppliedRevision=base,
        verifiedFreshCreation=origin,serverBusinessToday=today,
        lastAuthenticatedPullAgeMillis=500L,signedInStaff=true,
        enrolledAndApprovedWriter=true,pendingOperationUnchanged=true
    )
    private fun blocked(c:StaffOutboxUploadPolicy.Candidate) {
        var denied=false
        try { StaffOutboxUploadPolicy.prepare(c) }
        catch(_:IllegalArgumentException){denied=true}
        assertTrue("Unsafe Staff upload was accepted",denied)
    }

    @Test fun authenticatedTodayFreshFinancialRowCanBeQueuedForCreate() {
        val p=StaffOutboxUploadPolicy.prepare(candidate())
        assertEquals("create",p.action)
        assertEquals(null,p.expectedRevision)
        assertEquals(today,p.businessDate)
    }

    @Test fun unlimitedSameDayCorrectionsUseExactServerRevision() {
        val c=candidate(base=2,remote=2,origin=false)
        val p=StaffOutboxUploadPolicy.prepare(c)
        assertEquals("correct",p.action)
        assertEquals(2L,p.expectedRevision)
        assertTrue(StaffOutboxUploadPolicy.serverAccepted(p,p.operationUuid,true,3L,true))
        blocked(c.copy(observedRevision=3L))
        val next=StaffOutboxUploadPolicy.prepare(c.copy(
            lastAckRevision=3L,observedRevision=3L))
        assertEquals(3L,next.expectedRevision)
    }

    @Test fun pendingAfterMidnightStaysQueuedNotSent() {
        blocked(candidate(date="2026-10-23"))
        blocked(candidate(date="2026-10-25"))
        blocked(candidate().copy(lastAuthenticatedPullAgeMillis=60001L))
        blocked(candidate().copy(lastAuthenticatedPullAgeMillis=-1))
    }

    @Test fun retiredOrCopiedStaffPhoneIsDenied() {
        blocked(candidate().copy(enrolledDeviceUuid="f2222222-2222-4222-8222-222222222222"))
        blocked(candidate().copy(enrolledAndApprovedWriter=false))
        blocked(candidate().copy(signedInStaff=false))
        blocked(candidate().copy(userUuid="22222222-2222-4222-8222-222222222222"))
        blocked(candidate().copy(pendingOperationUnchanged=false))
    }

    @Test fun historicOrUnmappedAndCollisionCannotOverwriteCloud() {
        blocked(candidate(origin=false))
        blocked(candidate(base=2,remote=3,origin=false))
        blocked(candidate(base=2,remote=2,origin=true))
        blocked(candidate().copy(hasRemoteBusinessKeyCollision=true))
        blocked(candidate().copy(cloudItemUuid=null))
        blocked(candidate().copy(pendingAction="delete"))
        blocked(candidate().copy(type="item"))
    }

    @Test fun onlyExactRpcOperationRevisionAndCurrentPendingRowGetAck() {
        val p=StaffOutboxUploadPolicy.prepare(candidate(base=2,remote=2,origin=false))
        assertFalse(StaffOutboxUploadPolicy.serverAccepted(p,p.operationUuid,true,3L,false))
        assertFalse(StaffOutboxUploadPolicy.serverAccepted(p,p.operationUuid,false,3L,true))
        assertFalse(StaffOutboxUploadPolicy.serverAccepted(p,p.operationUuid,true,5L,true))
        assertFalse(StaffOutboxUploadPolicy.serverAccepted(
            p,"b1111111-1111-4111-8111-111111111111",true,3L,true))
        assertTrue(StaffOutboxUploadPolicy.serverAccepted(p,p.operationUuid,true,3L,true))
    }

    @Test fun defaultPreviewOrMissingServerCryptoStopsAllUploads() {
        for(i in 0..5) {
            val f=BooleanArray(6){true}
            if(i==0) f[0]=true else f[i]=false
            assertFalse(StaffOutboxUploadPolicy.releaseReady(
                preview=f[0],staffLogin=f[1],staffPull=f[2],
                financeRpcs=f[3],deviceProofEnforced=f[4],staffOutboxEnabled=f[5]))
        }
        assertTrue(StaffOutboxUploadPolicy.releaseReady(
            false,true,true,true,true,true))
    }
}
