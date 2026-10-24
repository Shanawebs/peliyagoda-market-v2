package lk.peliyagoda.marketpro.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature

class StaffDeviceProofPolicyTest {
    private val w = "11111111-1111-4111-8111-111111111111"
    private val staff = "33333333-3333-4333-8333-333333333333"
    private val device = "b1111111-1111-4111-8111-111111111111"
    private val session = "91111111-1111-4111-8111-111111111111"
    private val cid = "a1111111-1111-4111-8111-111111111111"
    private val now=1_800_000_000_000L

    private fun challenge() = StaffDeviceChallenge(
        w,staff,device,session,cid,"a".repeat(64),
        "pair",now-20_000,now+20_000
    )

    @Test fun correctSignedChallengeVerifiesAndPublicKeyIsFingerprinted() {
        val pair=KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val c=challenge()
        val sig=Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(StaffDeviceProofPolicy.transcript(c,now))
            sign()
        }
        assertTrue(StaffDeviceProofPolicy.verifySignature(pair.public,c,now,sig))
        assertEquals(64,StaffDeviceProofPolicy.fingerprint(pair.public).length)
        assertFalse(c.toString().contains("a".repeat(64)))
        assertFalse(StaffDeviceProofPolicy.verifySignature(
            pair.public,c.copy(deviceId=w),now,sig))
        assertFalse(StaffDeviceProofPolicy.verifySignature(
            pair.public,c.copy(sessionId=w),now,sig))
        assertFalse(StaffDeviceProofPolicy.verifySignature(
            pair.public,c.copy(challengeId=w),now,sig))
        assertFalse(StaffDeviceProofPolicy.verifySignature(
            pair.public,c.copy(purpose="write"),now,sig))
    }

    @Test fun expiredFutureAndOverlongServerChallengeDenied() {
        val c=challenge()
        assertFalse(StaffDeviceProofPolicy.valid(c,now+20_000))
        assertFalse(StaffDeviceProofPolicy.valid(c,now-20_001))
        assertFalse(StaffDeviceProofPolicy.valid(
            c.copy(expiresAtMillis=c.issuedAtMillis+120_001),now))
        assertFalse(StaffDeviceProofPolicy.valid(
            c.copy(expiresAtMillis=c.issuedAtMillis),now))
        assertFalse(StaffDeviceProofPolicy.valid(
            c.copy(nonceHex="not-a-nonce"),now))
        assertFalse(StaffDeviceProofPolicy.valid(
            c.copy(nonceHex="A".repeat(64)),now))
        assertFalse(StaffDeviceProofPolicy.valid(
            c.copy(sessionId="wrong-session"),now))
        assertFalse(StaffDeviceProofPolicy.valid(
            c.copy(purpose="owner-transfer"),now))
    }

    @Test fun signaturesCannotBeReplayedForDifferentAccountsDevicesOrNonces() {
        val pair=KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val c=challenge()
        val sig=Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(StaffDeviceProofPolicy.transcript(c,now))
            sign()
        }
        assertFalse(StaffDeviceProofPolicy.verifySignature(
            pair.public,c.copy(staffUserId=w),now,sig))
        assertFalse(StaffDeviceProofPolicy.verifySignature(
            pair.public,c.copy(nonceHex="b".repeat(64)),now,sig))
        assertFalse(StaffDeviceProofPolicy.verifySignature(
            pair.public,c.copy(workspaceId=staff),now,sig))
        assertFalse(StaffDeviceProofPolicy.verifySignature(
            pair.public,c,now,ByteArray(0)))
        assertFalse(StaffDeviceProofPolicy.verifySignature(
            pair.public,c,now,ByteArray(600)))
    }
}
