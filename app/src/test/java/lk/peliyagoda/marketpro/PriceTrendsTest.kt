package lk.peliyagoda.marketpro

import lk.peliyagoda.marketpro.data.PurchaseHistoryLot
import lk.peliyagoda.marketpro.util.dailyPriceTrends
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceTrendsTest {
    @Test fun weightedPriceUsesQuantityNotAverageOfRates() {
        val result = dailyPriceTrends(
            listOf(
                PurchaseHistoryLot("2026-10-20", 10_000, 15_000, 0),
                PurchaseHistoryLot("2026-10-20", 20_000, 30_000, 1),
                PurchaseHistoryLot("2026-10-19", 3_500, 11_500, 0)
            )
        )
        assertEquals(listOf("2026-10-19", "2026-10-20"), result.map { it.date })
        assertEquals(2, result.size)
        assertEquals(30_000L, result[1].quantityGrams)
        assertEquals(25_000L, result[1].weightedPriceCentsPerKg) // Rs 250/kg
        assertEquals(2, result[1].purchaseLots)
        assertEquals(11_500L, result[0].weightedPriceCentsPerKg)
    }

    @Test fun ignoresZeroQuantityAndRetainsZeroPriceWithPositiveQuantity() {
        val rows = dailyPriceTrends(
            listOf(
                PurchaseHistoryLot("2026-10-01", 0, 30_000, 0),
                PurchaseHistoryLot("2026-10-02", 1_000, 0, 0)
            )
        )
        assertEquals(1, rows.size)
        assertEquals("2026-10-02", rows.single().date)
        assertEquals(0L, rows.single().weightedPriceCentsPerKg)
    }

    @Test fun noLotsMeansNoChartPoints() {
        assertTrue(dailyPriceTrends(emptyList()).isEmpty())
    }
}
