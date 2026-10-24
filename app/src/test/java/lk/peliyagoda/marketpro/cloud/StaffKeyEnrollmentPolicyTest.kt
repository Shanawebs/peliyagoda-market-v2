package lk.peliyagoda.marketpro.cloud

import lk.peliyagoda.marketpro.security.StaffDeviceChallenge
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaffKeyEnrollmentPolicyTest {
    private val w="11111111-1111-4111-8111-111111111111"
    private val staff="33333333-3333-4333-8333-333333333333"
    private val owner="22222222-2222-4222-8222-222222222222"
    private val d="f1111111-1111-4111-8111-111111111111"
    private val sid="91111111-1111-4111-8111-111111111111"
    private val fingerprint="a".repeat(64)
    private val now=1800000000000L
    private val session=TeamPullSession("A".repeat(100),staff,d,w)
    private val challenge=StaffDeviceChallenge(
        w,staff,d,sid,"e1111111-1111-4111-8111-111111111111",
        "e".repeat(64),"pair",now-1000,now+59000
    )
    private fun check(c:StaffDeviceChallenge=challenge,
      ses:TeamPullSession=session,subject:String=staff,
      jwtSid:String=sid,physical:String=d,fp:String=fingerprint,
      approved:String=fingerprint,time:Long=now)=
       StaffKeyEnrollmentPolicy.checkChallenge(
         c,ses,subject,jwtSid,physical,fp,approved,time)
    private fun denied(block:()->Unit) {
      var threw=false
      try{block()}catch(_:IllegalArgumentException){threw=true}
      assertTrue("Pairing should be rejected",threw)
    }

    @Test fun staffMaySignOnlyMatchingSessionAndUnexpiredPairChallenge() { check() }
    @Test fun cloneOrOtherSessionAndRotatedKeyCannotRegister() {
      denied{check(c=challenge.copy(sessionId="92222222-2222-4222-8222-222222222222"))}
      denied{check(physical="a1111111-1111-4111-8111-111111111111")}
      denied{check(subject=owner)}
      denied{check(approved="b".repeat(64))}
      denied{check(c=challenge.copy(purpose="write"))}
      denied{check(time=now+59000)}
      denied{check(c=challenge.copy(nonceHex="bad"))}
    }
    @Test fun ownerApprovalNeedsTwoMatchingFingerprintsAndTypedConfirmation() {
      val t=OwnerWriterTarget(w,owner,
        "a1111111-1111-4111-8111-111111111111",staff,d)
      assertTrue(StaffKeyEnrollmentPolicy.ownerMayApprove(
        t,fingerprint,fingerprint,fingerprint,"APPROVE KEY",true))
      assertFalse(StaffKeyEnrollmentPolicy.ownerMayApprove(
        t,fingerprint,fingerprint,"b".repeat(64),"APPROVE KEY",true))
      assertFalse(StaffKeyEnrollmentPolicy.ownerMayApprove(
        t,fingerprint,fingerprint,fingerprint,"APPROVE",true))
      assertFalse(StaffKeyEnrollmentPolicy.ownerMayApprove(
        t,fingerprint,fingerprint,fingerprint,"APPROVE KEY",false))
      assertFalse(StaffKeyEnrollmentPolicy.ownerMayApprove(
        t.copy(staffUserId=owner),fingerprint,fingerprint,fingerprint,"APPROVE KEY",true))
    }
    @Test fun disabledPreviewMustStayOffline() {
      assertFalse(StaffKeyEnrollmentPolicy.allowClient(true,true,true,true))
      assertFalse(StaffKeyEnrollmentPolicy.allowClient(false,false,true,true))
      assertFalse(StaffKeyEnrollmentPolicy.allowClient(false,true,true,false))
      assertTrue(StaffKeyEnrollmentPolicy.allowClient(false,true,true,true))
    }
}
