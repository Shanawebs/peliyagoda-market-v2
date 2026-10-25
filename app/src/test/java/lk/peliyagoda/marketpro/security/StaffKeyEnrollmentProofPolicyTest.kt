package lk.peliyagoda.marketpro.security

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class StaffKeyEnrollmentProofPolicyTest {
    private val now=1_800_000_000_000L
    private val c=StaffKeyEnrollmentChallenge(
        "11111111-1111-4111-8111-111111111111",
        "33333333-3333-4333-8333-333333333333",
        "f1111111-1111-4111-8111-111111111111",
        "91111111-1111-4111-8111-111111111111",
        "a1111111-1111-4111-8111-111111111111",
        "b".repeat(64),"c".repeat(64),
        now-1000,now+59000,
        StaffKeyEnrollmentProofPolicy.VERSION)
    @Test fun tenLineProofSignsFingerprintDeviceAndAuthSession() {
        val k=KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val message=StaffKeyEnrollmentProofPolicy.transcript(c,now)
        assertEquals(10,String(message,Charsets.US_ASCII).split("\n").size)
        val sig=Signature.getInstance("SHA256withECDSA").run {
            initSign(k.private);update(message);sign()
        }
        assertTrue(Signature.getInstance("SHA256withECDSA").run {
            initVerify(k.public);update(message);verify(sig)
        })
        for(edited in listOf(
            c.copy(fingerprintSha256="d".repeat(64)),
            c.copy(nonceHex="e".repeat(64)),
            c.copy(deviceId="f2222222-2222-4222-8222-222222222222"),
            c.copy(sessionId="92222222-2222-4222-8222-222222222222")
        )) assertFalse(Signature.getInstance("SHA256withECDSA").run{
            initVerify(k.public)
            update(StaffKeyEnrollmentProofPolicy.transcript(edited,now))
            verify(sig)
        })
    }
    @Test fun invalidKeysExpiredProofAndDomainDowngradeAreRejected() {
        fun denied(x:StaffKeyEnrollmentChallenge) {
            assertThrows(IllegalArgumentException::class.java) {
                StaffKeyEnrollmentProofPolicy.transcript(x,now)
            }
        }
        denied(c.copy(proofVersion="marketpro-device-proof-financial-v3"))
        denied(c.copy(deviceId="copied"))
        denied(c.copy(fingerprintSha256="ZZ"))
        denied(c.copy(expiresAtMillis=now))
        denied(c.copy(issuedAtMillis=now+1))
    }
}
