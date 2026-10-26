package lk.peliyagoda.marketpro.stagingowner

import org.junit.Assert.*
import org.junit.Test

class PendingOwnerTranscriptTest {
    private val issued=1_800_000_000_050L
    private fun data()=PendingOwnerTranscript(
        workspaceId="11111111-1111-4111-8111-111111111111",
        ownerUserId="22222222-2222-4222-8222-222222222222",
        deviceId="33333333-3333-4333-8333-333333333333",
        sessionId="44444444-4444-4444-8444-444444444444",
        challengeId="55555555-5555-4555-8555-555555555555",
        nonceHex="a".repeat(64),fingerprintSha256="b".repeat(64),
        issuedAtMillis=issued,expiresAtMillis=issued+60_000L
    )
    @Test fun distinctP256DomainAndCanonicalTenLines(){
        val a=String(data().bytes(issued+100L)).split("\n")
        assertEquals(10,a.size)
        assertEquals("marketpro-owner-pending-registration-v1",a[0])
        assertNotEquals(OwnerProofTranscript.VERSION,a[0])
        assertEquals(issued.toString(),a[8])
        assertEquals((issued+60_000L).toString(),a[9])
    }
    @Test fun missingOrExpiredNonceRejected(){
        assertThrows(IllegalArgumentException::class.java){ data().bytes(issued-1) }
        assertThrows(IllegalArgumentException::class.java){ data().bytes(issued+60_000L) }
        assertThrows(IllegalArgumentException::class.java){
            data().copy(nonceHex="invalid").bytes(issued)
        }
    }
    @Test fun oversizedTtlWrongOwnerOrDeviceRejected(){
        assertThrows(IllegalArgumentException::class.java){
            data().copy(expiresAtMillis=issued+60_001L).bytes(issued)
        }
        assertThrows(IllegalArgumentException::class.java){
            data().copy(deviceId="not-a-uuid").bytes(issued)
        }
        assertThrows(IllegalArgumentException::class.java){
            data().copy(ownerUserId="not-a-uuid").bytes(issued)
        }
    }
}
