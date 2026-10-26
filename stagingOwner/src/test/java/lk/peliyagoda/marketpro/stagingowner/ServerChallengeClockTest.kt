package lk.peliyagoda.marketpro.stagingowner

import org.junit.Assert.*
import org.junit.Test

class ServerChallengeClockTest {
    private val issued = 1_800_000_000_125L
    private val expires = issued + 60_000L
    private val requestStart = 800_000L

    @Test fun phoneWallClockHoursBehindDoesNotEnterCalculation() {
        // Historical v0.4.2 failure: local wall-clock before the DB issued_at.
        // Only DB timestamp and monotonic duration are required, not UTC
        // device time. Simulate a remote response after 825 milliseconds.
        val phoneClock12HoursBehind = issued - 12L * 60L * 60L * 1000L
        assertTrue(phoneClock12HoursBehind < issued)
        assertEquals(
            issued + 825L,
            ServerChallengeClock.signingTimeMillis(
                issued, expires, requestStart, requestStart + 825L
            )
        )
    }

    @Test fun delayedChallengeStillValidAt59SecondsButNeverAt60() {
        assertEquals(issued+59_999L, ServerChallengeClock.signingTimeMillis(
            issued,expires,requestStart,requestStart+59_999L
        ))
        assertThrows(IllegalArgumentException::class.java) {
            ServerChallengeClock.signingTimeMillis(
                issued,expires,requestStart,requestStart+60_000L
            )
        }
    }

    @Test fun networkDelayIncludedConservatively() {
        val fakeNetworkDelay = 3500L
        assertEquals(issued+fakeNetworkDelay,
            ServerChallengeClock.signingTimeMillis(
                issued,expires,requestStart,requestStart+fakeNetworkDelay
            ))
    }

    @Test fun invalidClockMonotonicRollbackAndInvalidServerTTLRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerChallengeClock.signingTimeMillis(
                issued,expires,requestStart+1,requestStart
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerChallengeClock.signingTimeMillis(
                issued,issued+60_001L,requestStart,requestStart
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerChallengeClock.signingTimeMillis(
                issued,issued,requestStart,requestStart
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerChallengeClock.signingTimeMillis(
                issued,expires,-1L,requestStart
            )
        }
    }

    @Test fun canonicalTranscriptStillChecksStrictServerAnchoredTime() {
        val now=ServerChallengeClock.signingTimeMillis(
            issued,expires,requestStart,requestStart+1200L
        )
        val transcript=OwnerProofTranscript(
            workspaceId="11111111-1111-4111-8111-111111111111",
            ownerUserId="22222222-2222-4222-8222-222222222222",
            deviceId="33333333-3333-4333-8333-333333333333",
            sessionId="44444444-4444-4444-8444-444444444444",
            challengeId="55555555-5555-4555-8555-555555555555",
            nonceHex="a".repeat(64),fingerprintSha256="b".repeat(64),
            issuedAtMillis=issued,expiresAtMillis=expires
        )
        assertEquals(10,String(transcript.bytes(now)).split("\n").size)
        assertThrows(IllegalArgumentException::class.java){
            transcript.bytes(expires)
        }
    }
}
