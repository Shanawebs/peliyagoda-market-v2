package lk.peliyagoda.marketpro.cloud

import lk.peliyagoda.marketpro.security.StaffDeviceChallenge
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaffSignedFinancePolicyTest {
    private val w="11111111-1111-4111-8111-111111111111"
    private val staff="33333333-3333-4333-8333-333333333333"
    private val d="f1111111-1111-4111-8111-111111111111"
    private val sid="91111111-1111-4111-8111-111111111111"
    private val fp="e".repeat(64)
    private val now=1800000000000L
    private val session=TeamPullSession("A".repeat(120),staff,d,w)
    private val challenge=StaffDeviceChallenge(
        workspaceId=w,staffUserId=staff,deviceId=d,sessionId=sid,
        challengeId="c1111111-1111-4111-8111-111111111111",
        nonceHex="a".repeat(64),purpose="write",
        issuedAtMillis=now-2000,expiresAtMillis=now+58000
    )
    private fun check(c:StaffDeviceChallenge=challenge,
        s:TeamPullSession=session,subject:String=staff,
        jwtSid:String=sid,physical:String=d,approved:String=fp,
        onPhone:String=fp,time:Long=now
    ) = StaffSignedFinancePolicy.acceptServerChallenge(
        c,s,subject,jwtSid,physical,approved,onPhone,time)
    private fun rejected(block:()->Unit) {
        var failed=false
        try { block() } catch(_:IllegalArgumentException) { failed=true }
        assertTrue("Unsigned or mismatched challenge was accepted",failed)
    }

    @Test fun exactOwnerApprovedStaffSessionAndKeyCanSign() {
        check()
    }
    @Test fun clonedPhoneAndOtherJwtCannotSignThisChallenge() {
        rejected { check(physical="b1111111-1111-4111-8111-111111111111") }
        rejected { check(jwtSid="92222222-2222-4222-8222-222222222222") }
        rejected { check(subject="22222222-2222-4222-8222-222222222222") }
        rejected { check(c=challenge.copy(workspaceId="a1111111-1111-4111-8111-111111111111")) }
        rejected { check(c=challenge.copy(deviceId="b1111111-1111-4111-8111-111111111111")) }
        rejected { check(s=session.copy(enrolledDeviceId="b1111111-1111-4111-8111-111111111111")) }
    }
    @Test fun wrongPurposeExpiredChallengeOrKeyChangeFailsClosed() {
        rejected { check(c=challenge.copy(purpose="read")) }
        rejected { check(c=challenge.copy(purpose="pair")) }
        rejected { check(time=challenge.expiresAtMillis) }
        rejected { check(time=challenge.issuedAtMillis-1) }
        rejected { check(c=challenge.copy(nonceHex="bad")) }
        rejected { check(onPhone="a".repeat(64)) }
        rejected { check(approved="b".repeat(64)) }
        rejected { check(approved="missing") }
    }
    @Test fun ackRequiresSameOperationAndExactIncrementedCloudRevision() {
        val plan=StaffOutboxUploadPolicy.Plan(
            entityType="purchase",cloudUuid=w,
            operationUuid="a1111111-1111-4111-8111-111111111111",
            action="correct",expectedRevision=3L,businessDate="2026-10-24"
        )
        assertTrue(StaffSignedFinancePolicy.receiptMatches(
            plan,true,plan.operationUuid,4L,false))
        assertTrue(StaffSignedFinancePolicy.receiptMatches(
            plan,true,plan.operationUuid,4L,true))
        assertFalse(StaffSignedFinancePolicy.receiptMatches(
            plan,true,plan.operationUuid,5L,false))
        assertFalse(StaffSignedFinancePolicy.receiptMatches(
            plan,true,w,4L,false))
        assertFalse(StaffSignedFinancePolicy.receiptMatches(
            plan,false,plan.operationUuid,4L,false))
    }
}
