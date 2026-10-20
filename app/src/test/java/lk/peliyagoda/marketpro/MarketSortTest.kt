package lk.peliyagoda.marketpro

import lk.peliyagoda.marketpro.data.ItemEntity
import lk.peliyagoda.marketpro.data.PurchaseEntity
import lk.peliyagoda.marketpro.data.PurchaseAdditionEntity
import lk.peliyagoda.marketpro.data.RequisitionEntity
import lk.peliyagoda.marketpro.util.MARKET_PHOTO_SETTLE_DELAY_MS
import lk.peliyagoda.marketpro.util.consolidate
import lk.peliyagoda.marketpro.util.hasPricedCompletedPurchase
import lk.peliyagoda.marketpro.util.isArchivedForMarket
import lk.peliyagoda.marketpro.util.nextMarketSortRefreshMs
import lk.peliyagoda.marketpro.util.sortedMarketPurchases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketSortTest {
    private val date = "2026-10-20"
    private fun rows() = consolidate(
        items = listOf(
            ItemEntity(1, "Zucchini", "Vegetables"),
            ItemEntity(2, "Apples", "Fruits"),
            ItemEntity(3, "Banana", "Fruits"),
            ItemEntity(4, "Carrot", "Vegetables"),
            ItemEntity(5, "Dates", "Fruits"),
            ItemEntity(6, "Eggplant", "Vegetables")
        ),
        requisitions = (1L..6L).map { id ->
            RequisitionEntity(date, "Main Kitchen", id, 1000)
        },
        purchases = listOf(
            PurchaseEntity(date, 1, 1000, 9000), // completed, documented invoice
            PurchaseEntity(date, 2, 200, 9000),  // shortage
            PurchaseEntity(date, 3, 1000, 9000), // completed, NO invoice
            PurchaseEntity(date, 4, 1000, 0),    // completed, NO price
            PurchaseEntity(date, 6, 1000, 5000) // completed, documented invoice
        )
    )

    @Test fun completedMovesToBottomOnlyWithPhotoAfterOneMinuteAndAlphabeticGroups() {
        val r = rows()
        val now = 100_000L
        val photos = mapOf(1L to 39_999L, 6L to 39_999L, 4L to 39_999L)
        // Zucchini and Eggplant have invoice, price and full quantity.
        val expected = listOf("Apples", "Banana", "Carrot", "Dates", "Eggplant", "Zucchini")
        assertEquals(expected, sortedMarketPurchases(r, photos, now).map { it.item.name })
        // Banana is completed but has no invoice and stays with active items.
        assertFalse(isArchivedForMarket(r.first { it.item.id == 3L }, photos[3L], now))
        assertFalse(isArchivedForMarket(r.first { it.item.id == 4L }, photos[4L], now))
    }

    @Test fun oneMinuteWindowIsExactAndNoInvoiceMeansNeverArchive() {
        val row = rows().first { it.item.id == 1L }
        assertFalse(isArchivedForMarket(row, null, 70_000L))
        assertFalse(isArchivedForMarket(row, 1000L, 60_999L))
        assertTrue(isArchivedForMarket(row, 1000L, 61_000L))
        assertEquals(61_000L, nextMarketSortRefreshMs(listOf(row), mapOf(1L to 1000L), 50_000L))
        assertNull(nextMarketSortRefreshMs(listOf(row), mapOf(1L to 1000L), 61_000L))
        assertEquals(60_000L, MARKET_PHOTO_SETTLE_DELAY_MS)
    }

    @Test fun priceAndQtyAreRequiredAlsoForEveryExtraLot() {
        val item = ItemEntity(10, "Guava", "Fruits")
        val pendingPrice = consolidate(
            listOf(item),
            listOf(RequisitionEntity(date, "Indian Kitchen", 10, 5000)),
            listOf(PurchaseEntity(date, 10, 3000, 12_000)),
            listOf(PurchaseAdditionEntity(11, date, 10, 2000, 0))
        ).single()
        assertFalse(hasPricedCompletedPurchase(pendingPrice))
        val priced = pendingPrice.copy(
            additions = listOf(PurchaseAdditionEntity(11, date, 10, 2000, 13_000))
        )
        assertTrue(hasPricedCompletedPurchase(priced))
        assertFalse(isArchivedForMarket(priced, 0L, 120_000L))
    }

    @Test fun photoWithoutCompletedQuantityDoesNotChangeQueueOrder() {
        val all = rows()
        val shortage = all.first { it.item.id == 2L }
        assertFalse(hasPricedCompletedPurchase(shortage))
        assertFalse(isArchivedForMarket(shortage, 1L, 120_000L))
        // Only ready entries are sent to the bottom; list remains A-Z otherwise.
        assertEquals(
            listOf("Apples", "Banana", "Carrot", "Dates", "Eggplant", "Zucchini"),
            sortedMarketPurchases(all, emptyMap(), 120_000L).map { it.item.name }
        )
    }
}
