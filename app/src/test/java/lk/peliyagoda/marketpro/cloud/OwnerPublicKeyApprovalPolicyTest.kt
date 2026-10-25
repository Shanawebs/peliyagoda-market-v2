package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerPublicKeyApprovalPolicyTest {
 private val c=OwnerPublicKeyCandidate(
   "11111111-1111-4111-8111-111111111111",
   "33333333-3333-4333-8333-333333333333",
   "f1111111-1111-4111-8111-111111111111",
   "a1111111-1111-4111-8111-111111111111",
   "c".repeat(64))
 @Test fun ownerMustCompareExactlyWhatStaffPhysicalPhoneShows() {
  val check=OwnerPublicKeyApprovalPolicy::confirm
  assertTrue(check(c,c,"c".repeat(64),"APPROVE KEY",true))
  assertFalse(check(c,c,"d".repeat(64),"APPROVE KEY",true))
  assertFalse(check(c,c,"C".repeat(64),"APPROVE KEY",true))
  assertFalse(check(c,c,"c".repeat(64),"APPROVE",true))
  assertFalse(check(c,c,"c".repeat(64),"APPROVE KEY",false))
  assertFalse(check(c,c.copy(challengeId="a2222222-2222-4222-8222-222222222222"),
   "c".repeat(64),"APPROVE KEY",true))
  assertFalse(check(c,c.copy(staffDeviceId="f2222222-2222-4222-8222-222222222222"),
   "c".repeat(64),"APPROVE KEY",true))
  assertFalse(check(c.copy(fingerprintSha256="!"),c.copy(fingerprintSha256="!"),
   "!","APPROVE KEY",true))
 }
}
