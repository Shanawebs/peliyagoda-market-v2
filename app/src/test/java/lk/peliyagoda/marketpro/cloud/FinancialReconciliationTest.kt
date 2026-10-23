package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinancialReconciliationTest {
    private fun req(date: String = "2026-10-20", grams: Long = 5000,
        item: String = "carrot", kitchen: String = "Main Kitchen") =
        FinancialReconciliation.row("requisitions", date, item,
            kitchen = kitchen, grams = grams)

    private fun purchase(price: Long = 20000L, grams: Long = 1000) =
        FinancialReconciliation.row("purchases", "2026-10-20",
            item = "carrot", grams = grams, priceCentsPerKg = price)

    @Test fun sameContentMatchesRegardlessOfDatabaseRowIdsOrOrder() {
        val local = listOf(req(), purchase(),
            FinancialReconciliation.row("daily_cash_received", "2026-10-20",
                cashCents = 12000000))
        val cloud = local.reversed()
        val result = FinancialReconciliation.compare(local, cloud)
        assertTrue(result.fullyMatched)
        assertEquals(3, result.sections.sumOf { it.matchedRows })
    }

    @Test fun sameRecordCountDifferentPurchasePriceFails() {
        val result = FinancialReconciliation.compare(
            listOf(purchase(price = 20000)),
            listOf(purchase(price = 21000))
        )
        assertFalse(result.fullyMatched)
        val prices = result.sections.single { it.type == "purchases" }
        assertEquals(1, prices.phoneOnly)
        assertEquals(1, prices.cloudOnly)
    }

    @Test fun duplicateAdditionalLotsAreComparedAsMultiset() {
        val lot = FinancialReconciliation.row("purchase_additions", "2026-10-20",
            "carrot", grams = 2000, priceCentsPerKg = 30000)
        val result = FinancialReconciliation.compare(listOf(lot, lot), listOf(lot))
        assertFalse(result.fullyMatched)
        assertEquals(1, result.sections.single { it.type == "purchase_additions" }.matchedRows)
    }

    @Test fun kitchenAndDateAreDistinctAndDoNotSilentlyMerge() {
        val result = FinancialReconciliation.compare(
            listOf(req(kitchen = "Indian Kitchen")),
            listOf(req(kitchen = "Main Kitchen"))
        )
        assertFalse(result.fullyMatched)
    }

    @Test fun unmappedItemIdsAlwaysFailClosed() {
        val x = listOf(req())
        assertFalse(FinancialReconciliation.compare(x, x, missingPhoneItems = 1).fullyMatched)
        assertFalse(FinancialReconciliation.compare(x, x, missingCloudItems = 1).fullyMatched)
    }

    @Test fun daySpecificCashMustMatchExactCents() {
        val a = FinancialReconciliation.row("daily_cash_received", "2026-10-20",
            cashCents = 950000)
        val b = FinancialReconciliation.row("daily_cash_received", "2026-10-21",
            cashCents = 950000)
        assertFalse(FinancialReconciliation.compare(listOf(a), listOf(b)).fullyMatched)
    }
}
