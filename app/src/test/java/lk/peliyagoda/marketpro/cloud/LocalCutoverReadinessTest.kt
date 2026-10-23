package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCutoverReadinessTest {
    private fun verified() = LocalCutoverSignals(
        staged = true,
        phoneCounts = mapOf(
            "categories" to 3, "items" to 184,
            "requisitions" to 105, "purchases" to 26,
            "purchase_additions" to 10, "daily_cash_received" to 2
        ),
        cloudCounts = mapOf(
            "categories" to 3, "items" to 184,
            "requisitions" to 105, "purchases" to 26,
            "purchase_additions" to 10, "daily_cash_received" to 2
        ),
        matchingUuids = 330,
        differentUuids = 0,
        ambiguousUuids = 0,
        phoneUuidCount = 330,
        cloudUuidCount = 330,
        unsentOperations = 0,
        catalogMatches = true,
        financialMatches = true,
        previewAlreadyWriter = false
    )

    @Test fun fullyStagedSnapshotIsLocallyReadyButNeverAuthorizesCloudWrite() {
        val result = LocalCutoverReadinessPolicy.evaluate(verified())
        assertTrue(result.localDataReady)
        assertTrue(result.blockers.isEmpty())
        assertTrue(result.cloudUploadLocked)
        assertTrue(result.serverHandoverRequired)
    }

    @Test fun oldUuidMappingBlocksReadinessEvenWithMatchingRowCounts() {
        val result = LocalCutoverReadinessPolicy.evaluate(
            verified().copy(matchingUuids = 0, differentUuids = 330)
        )
        assertFalse(result.localDataReady)
        assertTrue(result.cloudUploadLocked)
    }

    @Test fun newLocalEditOrMissingStageBlocksReadiness() {
        assertFalse(LocalCutoverReadinessPolicy.evaluate(
            verified().copy(unsentOperations = 1)
        ).localDataReady)
        assertFalse(LocalCutoverReadinessPolicy.evaluate(
            verified().copy(staged = false)
        ).localDataReady)
    }

    @Test fun financeDifferenceOrUnrecognizedWriterCannotPass() {
        assertFalse(LocalCutoverReadinessPolicy.evaluate(
            verified().copy(financialMatches = false)
        ).localDataReady)
        assertFalse(LocalCutoverReadinessPolicy.evaluate(
            verified().copy(previewAlreadyWriter = true)
        ).localDataReady)
    }

    @Test fun mismatchCountsOrAmbiguousUuidsCannotPass() {
        assertFalse(LocalCutoverReadinessPolicy.evaluate(
            verified().copy(cloudCounts = verified().cloudCounts + ("purchases" to 27))
        ).localDataReady)
        assertFalse(LocalCutoverReadinessPolicy.evaluate(
            verified().copy(ambiguousUuids = 1)
        ).localDataReady)
    }

    @Test fun emptyOrBogusCountsNeverPass() {
        assertFalse(LocalCutoverReadinessPolicy.evaluate(
            verified().copy(phoneCounts = emptyMap(), cloudCounts = emptyMap())
        ).localDataReady)
        assertFalse(LocalCutoverReadinessPolicy.evaluate(
            verified().copy(phoneUuidCount = 0, cloudUuidCount = 0, matchingUuids = 0)
        ).localDataReady)
    }
}
