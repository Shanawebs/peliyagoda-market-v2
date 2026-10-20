package lk.peliyagoda.marketpro

import lk.peliyagoda.marketpro.data.ItemEntity
import lk.peliyagoda.marketpro.data.PurchaseEntity
import lk.peliyagoda.marketpro.data.PurchaseAdditionEntity
import lk.peliyagoda.marketpro.data.RequisitionEntity
import lk.peliyagoda.marketpro.util.cashBalanceCents
import lk.peliyagoda.marketpro.util.completedCategoryGroups
import lk.peliyagoda.marketpro.util.completedPurchaseTotal
import lk.peliyagoda.marketpro.util.consolidate
import lk.peliyagoda.marketpro.util.totalMarketSpentCents
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryCashReportTest {
    private val day = "2026-10-20"
    private val data = consolidate(
        items = listOf(
            ItemEntity(1, "Banana", "Fruits"),
            ItemEntity(2, "Apple", "Fruits"),
            ItemEntity(3, "Green Chilli", "Vegetables"),
            ItemEntity(4, "Carrot", "Vegetables"),
            ItemEntity(5, "Bok Choy", "Chinese Veg")
        ),
        requisitions = (1L..5L).map { RequisitionEntity(day, "Main Kitchen", it, 1_000L) },
        purchases = listOf(
            PurchaseEntity(day, 1, 1_000, 5_000), // Rs 50
            PurchaseEntity(day, 2, 1_000, 6_000), // Rs 60
            PurchaseEntity(day, 3, 500, 3_000), // Rs 15, shortage: still spent
            PurchaseEntity(day, 4, 1_000, 4_000), // Rs 40
            PurchaseEntity(day, 5, 1_000, 10_000) // Rs 100
        ),
        additions = listOf(
            PurchaseAdditionEntity(9, day, 4, 1_000, 2_000) // extra Rs 20
        )
    )

    @Test fun categorizesCompletedAlphabeticallyAndCalculatesSubtotals() {
        val groups = completedCategoryGroups(data)
        assertEquals(listOf("Chinese Veg", "Fruits", "Vegetables"), groups.map { it.name })
        assertEquals(listOf("Apple", "Banana"), groups[1].rows.map { it.item.name })
        assertEquals(listOf(10_000L, 11_000L, 6_000L), groups.map { it.subtotalCents })
        assertEquals(27_000L, groups.sumOf { it.subtotalCents })
        assertEquals(27_000L, completedPurchaseTotal(data))
    }

    @Test fun itemNumberingRestartsAtOneForEveryCategory() {
        val groups = completedCategoryGroups(data)
        assertEquals(listOf(1), groups[0].numberedRows().map { it.first })
        assertEquals(listOf(1, 2), groups[1].numberedRows().map { it.first })
        assertEquals(listOf(1), groups[2].numberedRows().map { it.first })
        assertEquals(
            listOf("Apple", "Banana"),
            groups[1].numberedRows().map { it.second.item.name }
        )
    }

    @Test fun cashBalanceDeductsAllActualSpendIncludingShortage() {
        assertEquals(28_500L, totalMarketSpentCents(data)) // 270+15
        assertEquals(71_500L, cashBalanceCents(100_000L, data))
        assertEquals(-28_500L, cashBalanceCents(0L, data))
        assertEquals(0L, cashBalanceCents(28_500L, data))
    }

    @Test fun extraLotPricesAreIncludedWithoutDoubleCounting() {
        val carrot = data.single { it.item.name == "Carrot" }
        assertEquals(6_000L, carrot.lineCents)
        assertEquals(2_000L, carrot.purchasedGrams)
    }
}
