package lk.peliyagoda.marketpro

import lk.peliyagoda.marketpro.data.ItemEntity
import lk.peliyagoda.marketpro.data.PurchaseEntity
import lk.peliyagoda.marketpro.data.PurchaseAdditionEntity
import lk.peliyagoda.marketpro.data.RequisitionEntity
import lk.peliyagoda.marketpro.util.completedPurchases
import lk.peliyagoda.marketpro.util.completedPurchaseTotal
import lk.peliyagoda.marketpro.util.consolidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletedPurchaseReportTest {
    @Test fun includesMatchedAndExtraButNotShortagePendingOrZeroRequired() {
        val day = "2026-10-20"
        val items = listOf(
            ItemEntity(1, "Green Chilli", "Vegetables"),
            ItemEntity(2, "Beans", "Vegetables"),
            ItemEntity(3, "කැරට්", "Vegetables"),
            ItemEntity(4, "No Purchase", "Vegetables"),
            ItemEntity(5, "No Required", "Vegetables")
        )
        val requisitions = listOf(
            RequisitionEntity(day, "Main Kitchen", 1, 10_000),
            RequisitionEntity(day, "Main Kitchen", 2, 10_000),
            RequisitionEntity(day, "Main Kitchen", 3, 10_000),
            RequisitionEntity(day, "Main Kitchen", 4, 10_000)
        )
        val purchases = listOf(
            PurchaseEntity(day, 1, 10_000, 10_000),
            PurchaseEntity(day, 2, 8_000, 10_000),
            PurchaseEntity(day, 3, 11_000, 5_000),
            PurchaseEntity(day, 5, 2_000, 15_000)
        )
        val extra = listOf(PurchaseAdditionEntity(11, day, 1, 2_000, 15_000))
        val rows = consolidate(items, requisitions, purchases, extra)
        val completed = completedPurchases(rows)
        assertEquals(setOf("Green Chilli", "කැරට්"), completed.map { it.item.name }.toSet())
        assertEquals(2, completed.size)
        assertEquals(185_000L, completedPurchaseTotal(rows)) // 1000+300+550 LKR
    }

    @Test fun emptyWhenNoFullyPurchasedItems() {
        val day = "2026-10-20"
        val rows = consolidate(
            listOf(ItemEntity(1, "Radish", "Vegetables")),
            listOf(RequisitionEntity(day, "Main Kitchen", 1, 4000)),
            emptyList()
        )
        assertTrue(completedPurchases(rows).isEmpty())
        assertEquals(0L, completedPurchaseTotal(rows))
    }
}
