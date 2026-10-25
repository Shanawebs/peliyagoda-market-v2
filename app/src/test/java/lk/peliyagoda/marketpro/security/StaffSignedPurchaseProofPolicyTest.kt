package lk.peliyagoda.marketpro.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class StaffSignedPurchaseProofPolicyTest {
    private val now=1_800_000_000_000L
    private val base=StaffDeviceChallenge(
      workspaceId="11111111-1111-4111-8111-111111111111",
      staffUserId="33333333-3333-4333-8333-333333333333",
      deviceId="f1111111-1111-4111-8111-111111111111",
      sessionId="91111111-1111-4111-8111-111111111111",
      challengeId="a1111111-1111-4111-8111-111111111111",
      nonceHex="a".repeat(64),purpose="write",
      issuedAtMillis=now-1000,expiresAtMillis=now+59000
    )
    private fun challenge()=StaffSignedPurchaseChallenge(
      base,operationId="d1111111-1111-4111-8111-111111111111",
      entityId="c1111111-1111-4111-8111-111111111111",
      financialAction="create",expectedRevision=null,
      operationSha256="b".repeat(64),
      proofVersion="marketpro-device-proof-purchase-v2"
    )

    @Test fun transcriptEqualsServerV2WireFormatAndAndroidDerSigning() {
      val c=challenge()
      val t=StaffSignedPurchaseProofPolicy.transcript(c,now)
      val lines=String(t,Charsets.US_ASCII).split("\n")
      assertEquals(15,lines.size)
      assertEquals("marketpro-device-proof-purchase-v2",lines[0])
      assertEquals(c.operationId,lines[10])
      assertEquals(c.entityId,lines[11])
      assertEquals("create",lines[12])
      assertEquals("null",lines[13])
      assertEquals(c.operationSha256,lines[14])
      val pair=KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
      }.generateKeyPair()
      val signed=Signature.getInstance("SHA256withECDSA").run {
        initSign(pair.private);update(t);sign()
      }
      assertTrue(Signature.getInstance("SHA256withECDSA").run {
        initVerify(pair.public);update(t);verify(signed)
      })
      val modified=StaffSignedPurchaseProofPolicy.transcript(
        c.copy(operationSha256="c".repeat(64)),now)
      assertFalse(Signature.getInstance("SHA256withECDSA").run {
        initVerify(pair.public);update(modified);verify(signed)
      })
    }

    @Test fun rejectsMismatchedActionRevisionsAndV1Downgrade() {
      fun blocked(c:StaffSignedPurchaseChallenge) {
        var caught=false
        try { StaffSignedPurchaseProofPolicy.transcript(c,now) }
        catch(_:IllegalArgumentException){caught=true}
        assertTrue(caught)
      }
      blocked(challenge().copy(financialAction="correct",expectedRevision=null))
      blocked(challenge().copy(expectedRevision=0L))
      blocked(challenge().copy(operationSha256=""))
      blocked(challenge().copy(proofVersion="marketpro-device-proof-v1"))
      blocked(challenge().copy(deviceChallenge=base.copy(purpose="read")))
      blocked(challenge().copy(deviceChallenge=base.copy(
          expiresAtMillis=now)))
    }
}
