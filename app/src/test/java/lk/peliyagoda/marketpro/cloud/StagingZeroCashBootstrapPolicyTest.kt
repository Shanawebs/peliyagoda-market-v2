package lk.peliyagoda.marketpro.cloud

import lk.peliyagoda.marketpro.data.CloudOutboxEntity
import lk.peliyagoda.marketpro.data.DailyCashReceivedEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StagingZeroCashBootstrapPolicyTest {
    private fun cash(amount: Long, date: String = "2026-10-27") =
        DailyCashReceivedEntity(date, amount)

    private fun outbox(
        type: String, key: String,
        action: String = "upsert"
    ) = CloudOutboxEntity(type, key, "00000000-0000-4000-8000-000000000001", action, 1L)

    @Test fun freshEmptyRoomAndCatalogJournalAllowed() {
        assertTrue(StagingZeroCashBootstrapPolicy.permits(emptyList(),emptyList()))
        assertTrue(StagingZeroCashBootstrapPolicy.permits(
            emptyList(),listOf(outbox("category","vegetables"),outbox("item","1"))
        ))
    }

    @Test fun oneZeroCashPlaceholderAndItsOwnJournalAllowed() {
        assertTrue(StagingZeroCashBootstrapPolicy.permits(
            listOf(cash(0L)),
            listOf(outbox("category","vegetables"),
                outbox("daily_cash_received","2026-10-27"))
        ))
        assertTrue(StagingZeroCashBootstrapPolicy.permits(listOf(cash(0L)),emptyList()))
    }

    @Test fun nonzeroCashNeverAllowedEvenWithNoJournal() {
        assertFalse(StagingZeroCashBootstrapPolicy.permits(listOf(cash(1L)),emptyList()))
        assertFalse(StagingZeroCashBootstrapPolicy.permits(
            listOf(cash(10L)),listOf(outbox("daily_cash_received","2026-10-27"))
        ))
    }

    @Test fun multipleZeroCashRowsStillBlockedForManualReview() {
        assertFalse(StagingZeroCashBootstrapPolicy.permits(
            listOf(cash(0L),cash(0L,"2026-10-26")),emptyList()
        ))
    }

    @Test fun orphanAndUnmatchedCashJournalBlocked() {
        assertFalse(StagingZeroCashBootstrapPolicy.permits(
            emptyList(),listOf(outbox("daily_cash_received","2026-10-27"))
        ))
        assertFalse(StagingZeroCashBootstrapPolicy.permits(
            listOf(cash(0L)),listOf(outbox("daily_cash_received","2026-10-26"))
        ))
        assertFalse(StagingZeroCashBootstrapPolicy.permits(
            listOf(cash(0L)),listOf(outbox("daily_cash_received","2026-10-27","delete"))
        ))
    }

    @Test fun allRealFinancialJournalTypesAlwaysBlocked() {
        val types=listOf("purchase","purchase_addition","requisition","daily_other_expenses")
        types.forEach { type ->
            assertFalse("Should block $type",StagingZeroCashBootstrapPolicy.permits(
                listOf(cash(0L)),listOf(outbox(type,"one"))
            ))
        }
    }
}
