package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationDryRunTest {
    private val recordTypes = listOf(
        "category" to 3,
        "item" to 184,
        "requisition" to 105,
        "purchase" to 26,
        "purchase_addition" to 10,
        "daily_cash_received" to 2
    )

    private fun identities(
        pending: Int = 330, ambiguousExtra: Int = 0
    ): UuidAuditResult {
        val sections = recordTypes.map { (type, count) ->
            UuidAuditSection(
                type = type,
                phoneRecords = count,
                cloudRecords = count,
                matchingUuid = 0,
                differentUuid = if (type == "purchase_addition") count - ambiguousExtra else count,
                absentLocalUuid = 0,
                phoneOnly = 0,
                cloudOnly = 0,
                ambiguous = if (type == "purchase_addition") ambiguousExtra else 0
            )
        }
        return UuidAuditResult(
            sections = sections,
            pendingUploadOperations = pending,
            archivedRemoteRows = 0
        )
    }

    private fun catalog() = CatalogAudit(
        localActive = 171,
        remoteActive = 171,
        remoteArchived = 13,
        matchedActive = 171,
        onlyOnPhone = emptyList(),
        onlyInCloud = emptyList(),
        differentCategory = emptyList()
    )

    private fun finance(mismatch: Boolean = false): FinancialAudit {
        val pairs = recordTypes.drop(2)
        return FinancialAudit(
            sections = pairs.map { (kind, count) ->
                FinancialAuditSection(
                    type = when (kind) {
                        "requisition" -> "requisitions"
                        "purchase" -> "purchases"
                        "purchase_addition" -> "purchase_additions"
                        else -> "daily_cash_received"
                    },
                    phoneCount = count,
                    cloudCount = count,
                    matchedRows = if (mismatch && kind == "purchase") count - 1 else count,
                    phoneOnly = if (mismatch && kind == "purchase") 1 else 0,
                    cloudOnly = if (mismatch && kind == "purchase") 1 else 0
                )
            },
            unmappedPhoneItems = 0,
            unmappedCloudItems = 0
        )
    }

    @Test fun restored330DifferentUuidsAreCandidatesNotWriterAuthorization() {
        val result = MigrationDryRun.evaluate(identities(), catalog(), finance())
        assertEquals(330, result.phoneRecords)
        assertEquals(330, result.cloudRecords)
        assertEquals(330, result.uniqueCandidates)
        assertEquals(13, result.protectedCloudArchivedItems)
        assertEquals(330, result.pendingLocalOperations)
        assertTrue(result.contentMatches)
        assertTrue(result.oneToOneCandidateCoverage)
        assertTrue(result.cutoverBlocked)
        assertTrue(result.blockers.any { it.contains("outbox") })
        assertTrue(result.blockers.any { it.contains("Original writer") })
    }

    @Test fun ambiguousIdenticalAdditionalLotsFailClosed() {
        val result = MigrationDryRun.evaluate(
            identities(ambiguousExtra = 2), catalog(), finance()
        )
        assertEquals(2, result.duplicateSignatures)
        assertEquals(328, result.uniqueCandidates)
        assertFalse(result.oneToOneCandidateCoverage)
        assertTrue(result.cutoverBlocked)
    }

    @Test fun sameCountsWithWrongPricesStillBlock() {
        val result = MigrationDryRun.evaluate(
            identities(), catalog(), finance(mismatch = true)
        )
        assertFalse(result.contentMatches)
        assertTrue(result.cutoverBlocked)
    }

    @Test fun emptyPendingQueueStillDoesNotMeanWriterApproval() {
        val result = MigrationDryRun.evaluate(
            identities(pending = 0), catalog(), finance()
        )
        assertEquals(0, result.pendingLocalOperations)
        assertTrue(result.cutoverBlocked)
        assertTrue(result.blockers.any { it.contains("backup") })
    }
}
