package lk.peliyagoda.marketpro.util

import java.math.BigDecimal
import java.math.RoundingMode
import lk.peliyagoda.marketpro.data.PurchaseHistoryLot

/** Historic daily market values: weighted-average unit price when bought in separate lots. */
data class DailyPriceTrend(
    val date: String,
    val quantityGrams: Long,
    val weightedPriceCentsPerKg: Long,
    val purchaseLots: Int
)

/**
 * No price interpolation on missing days. Groups all original and additional lots
 * for the same item + date. Averages each day's actual LKR/kg weighted by its
 * purchased quantity, not a misleading simple average of different lot rates.
 */
fun dailyPriceTrends(lots: List<PurchaseHistoryLot>): List<DailyPriceTrend> =
    lots.filter { it.quantityGrams > 0 && it.priceCentsPerKg >= 0 }
        .groupBy { it.businessDate }
        .toSortedMap()
        .map { (day, entries) ->
            val grams = entries.sumOf { it.quantityGrams }
            val weighted = entries.fold(BigDecimal.ZERO) { acc, lot ->
                acc.add(
                    BigDecimal.valueOf(lot.quantityGrams)
                        .multiply(BigDecimal.valueOf(lot.priceCentsPerKg))
                )
            }
            val average = weighted.divide(
                BigDecimal.valueOf(grams), 0, RoundingMode.HALF_UP
            ).longValueExact()
            DailyPriceTrend(day, grams, average, entries.size)
        }
