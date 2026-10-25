package lk.peliyagoda.marketpro.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class StaffSignedFinancialProofPolicyTest {
    private val time=1_800_000_000_000L
    private val b=StaffDeviceChallenge(
        "11111111-1111-4111-8111-111111111111",
        "33333333-3333-4333-8333-333333333333",
        "f1111111-1111-4111-8111-111111111111",
        "91111111-1111-4111-8111-111111111111",
        "a1111111-1111-4111-8111-111111111111",
        "f".repeat(64),"write",time-1000,time+59000
    )
    private fun challenge(type:String="daily_other_expenses")=
        StaffSignedFinancialChallenge(
            b,type,
            "d1111111-1111-4111-8111-111111111111",
            "c1111111-1111-4111-8111-111111111111",
            "create",null,"b".repeat(64),
            StaffSignedFinancialProofPolicy.VERSION
        )

    @Test fun exactlySixteenAsciiLinesWithSignedFinancialType() {
        val pair=KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        for(type in StaffSignedFinancialProofPolicy.types){
            val challenge=challenge(type)
            val transcript=StaffSignedFinancialProofPolicy.transcript(challenge,time)
            val lines=String(transcript,Charsets.US_ASCII).split("\n")
            assertEquals(16,lines.size)
            assertEquals(StaffSignedFinancialProofPolicy.VERSION,lines[0])
            assertEquals(type,lines[10])
            assertEquals("create",lines[13])
            assertEquals("null",lines[14])
            val sign=Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private);update(transcript);sign()
            }
            assertTrue(Signature.getInstance("SHA256withECDSA").run {
                initVerify(pair.public);update(transcript);verify(sign)
            })
            val tampered=StaffSignedFinancialProofPolicy.transcript(
                challenge.copy(operationSha256="a".repeat(64)),time)
            assertFalse(Signature.getInstance("SHA256withECDSA").run {
                initVerify(pair.public);update(tampered);verify(sign)
            })
        }
    }
    @Test fun prohibitCrossEntitySignatureAndV2OrV1Downgrade() {
        fun blocked(c:StaffSignedFinancialChallenge) {
            var rejected=false
            try{StaffSignedFinancialProofPolicy.transcript(c,time)}
            catch(_:IllegalArgumentException){rejected=true}
            assertTrue(rejected)
        }
        blocked(challenge("purchase"))
        blocked(challenge("registered_devices"))
        blocked(challenge().copy(proofVersion="marketpro-device-proof-purchase-v2"))
        blocked(challenge().copy(deviceChallenge=b.copy(purpose="read")))
        blocked(challenge().copy(financialAction="correct",expectedRevision=null))
        blocked(challenge().copy(expectedRevision=0))
        blocked(challenge().copy(deviceChallenge=b.copy(expiresAtMillis=time)))
    }
}
