package lk.peliyagoda.marketpro.stagingowner

import org.junit.Assert.*
import org.junit.Test

class OwnerProofTranscriptTest {
    private val t=1_800_000_000_000L
    private fun material()=OwnerProofTranscript(
        workspaceId="11111111-1111-4111-8111-111111111111",
        ownerUserId="22222222-2222-4222-8222-222222222222",
        deviceId="33333333-3333-4333-8333-333333333333",
        sessionId="44444444-4444-4444-8444-444444444444",
        challengeId="55555555-5555-4555-8555-555555555555",
        nonceHex="a".repeat(64),fingerprintSha256="b".repeat(64),
        issuedAtMillis=t-1_000,expiresAtMillis=t+59_000
    )
    @Test fun canonicalTranscriptHasTenExactLines() {
        val parts=String(material().bytes(t),Charsets.UTF_8).split("\n")
        assertEquals(10,parts.size)
        assertEquals("marketpro-owner-bootstrap-key-v1",parts[0])
        assertEquals("a".repeat(64),parts[6])
        assertEquals("b".repeat(64),parts[7])
        assertEquals((t-1000).toString(),parts[8])
        assertEquals((t+59000).toString(),parts[9])
    }
    @Test fun expiredNonceCannotBeSigned() {
        assertThrows(IllegalArgumentException::class.java) { material().bytes(t+59_000) }
        assertThrows(IllegalArgumentException::class.java) { material().bytes(t-1_001) }
    }
    @Test fun invalidFingerprintUuidAndLongLifetimeAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            material().copy(fingerprintSha256="bad").bytes(t)
        }
        assertThrows(IllegalArgumentException::class.java) {
            material().copy(ownerUserId="not-an-id").bytes(t)
        }
        assertThrows(IllegalArgumentException::class.java) {
            material().copy(expiresAtMillis=t+60_000).bytes(t)
        }
    }
}
