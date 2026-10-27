package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class StagingQaWritePolicyTest {
    @Test
    fun teamFreshTodayCreatesAtRevisionZero() {
        val result = StagingQaWritePolicy.decide(
            role = TeamRole.PURCHASING_STAFF,
            businessDate = "2026-10-27",
            serverToday = "2026-10-27",
            pullAgeMs = 1_000L,
            remoteRevision = null,
            lastAppliedRevision = null,
            lastAckRevision = null,
            freshCreation = true
        )
        assertEquals(0L, result.expectedRevision)
    }

    @Test(expected = IllegalArgumentException::class)
    fun teamCannotUploadPastDay() {
        StagingQaWritePolicy.decide(
            role = TeamRole.PURCHASING_STAFF,
            businessDate = "2026-10-26",
            serverToday = "2026-10-27",
            pullAgeMs = 1_000L,
            remoteRevision = null,
            lastAppliedRevision = null,
            lastAckRevision = null,
            freshCreation = true
        )
    }

    @Test
    fun ownerCanCorrectPastDayFromTrustedBaseline() {
        val result = StagingQaWritePolicy.decide(
            role = TeamRole.OWNER,
            businessDate = "2026-10-20",
            serverToday = "2026-10-27",
            pullAgeMs = 500L,
            remoteRevision = 4L,
            lastAppliedRevision = 4L,
            lastAckRevision = null,
            freshCreation = false
        )
        assertEquals(4L, result.expectedRevision)
    }

    @Test(expected = IllegalArgumentException::class)
    fun newerRemoteRevisionBlocksLocalCorrection() {
        StagingQaWritePolicy.decide(
            role = TeamRole.OWNER,
            businessDate = "2026-10-27",
            serverToday = "2026-10-27",
            pullAgeMs = 500L,
            remoteRevision = 5L,
            lastAppliedRevision = 4L,
            lastAckRevision = null,
            freshCreation = false
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun staleServerClockEvidenceIsRejected() {
        StagingQaWritePolicy.decide(
            role = TeamRole.OWNER,
            businessDate = "2026-10-27",
            serverToday = "2026-10-27",
            pullAgeMs = 60_001L,
            remoteRevision = null,
            lastAppliedRevision = null,
            lastAckRevision = null,
            freshCreation = true
        )
    }
}
