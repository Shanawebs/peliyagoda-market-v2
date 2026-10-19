package lk.peliyagoda.marketpro

import lk.peliyagoda.marketpro.data.ItemEntity
import lk.peliyagoda.marketpro.data.PurchaseEntity
import lk.peliyagoda.marketpro.data.PurchaseAdditionEntity
import lk.peliyagoda.marketpro.data.RequisitionEntity
import lk.peliyagoda.marketpro.util.consolidate
import lk.peliyagoda.marketpro.util.marketCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketReportsTest {
    @Test fun separatePricesAddToExactBill() {
        val date = "2026-10-19"
        val rows = consolidate(
            items = listOf(ItemEntity(1, "Green Chilli", "Vegetables")),
            requisitions = listOf(RequisitionEntity(date, "Main Kitchen", 1, 60_000)),
            purchases = listOf(PurchaseEntity(date, 1, 50_000, 15_000)),
            additions = listOf(PurchaseAdditionEntity(7, date, 1, 10_000, 20_000))
        )
        val row = rows.single()
        assertEquals(60_000L, row.requiredGrams)
        assertEquals(60_000L, row.purchasedGrams)
        assertEquals(0L, row.varianceGrams)
        assertEquals(200_000L, row.additionalCents)
        assertEquals(950_000L, row.lineCents) // Rs. 7,500 + Rs. 2,000
        val csv = marketCsv(date, rows)
        assertTrue(csv.contains("Additional Purchase Lots"))
        assertTrue(csv.contains("9500"))
    }

    @Test fun oneShortageRemainsAfterPartialAdditionalPurchase() {
        val date = "2026-10-19"
        val row = consolidate(
            items = listOf(ItemEntity(1, "Beans", "Vegetables")),
            requisitions = listOf(
                RequisitionEntity(date, "Main Kitchen", 1, 20_000),
                RequisitionEntity(date, "Indian Kitchen", 1, 10_000)
            ),
            purchases = listOf(PurchaseEntity(date, 1, 25_000, 12_000)),
            additions = listOf(PurchaseAdditionEntity(1, date, 1, 2_000, 15_000))
        ).single()
        assertEquals(30_000L, row.requiredGrams)
        assertEquals(27_000L, row.purchasedGrams)
        assertEquals(-3_000L, row.varianceGrams)
        assertEquals(330_000L, row.lineCents)
    }
}
