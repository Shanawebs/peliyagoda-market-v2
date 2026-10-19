package lk.peliyagoda.marketpro.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Quantities are stored as integer grams; prices as integer cents/LKR per kilogram. */
object MarketMath {
    fun parseGrams(text: String): Long? = try {
        val amount = BigDecimal(text.trim())
        if (amount.signum() < 0) null
        else amount.movePointRight(3).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
    } catch (_: Exception) { null }

    fun parsePriceCents(text: String): Long? = try {
        val amount = BigDecimal(text.trim())
        if (amount.signum() < 0) null
        else amount.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
    } catch (_: Exception) { null }

    fun billCents(purchasedGrams: Long, priceCentsPerKg: Long): Long =
        BigDecimal.valueOf(purchasedGrams)
            .multiply(BigDecimal.valueOf(priceCentsPerKg))
            .divide(BigDecimal.valueOf(1000), 0, RoundingMode.HALF_UP)
            .longValueExact()

    private fun number(pattern: String, value: BigDecimal): String =
        DecimalFormat(pattern, DecimalFormatSymbols(Locale.US)).format(value)

    fun kg(grams: Long): String = number("#,##0.00#", BigDecimal.valueOf(grams, 3))
    fun editKg(grams: Long): String = BigDecimal.valueOf(grams, 3).stripTrailingZeros().toPlainString()
    fun lkr(cents: Long): String = number("#,##0.00", BigDecimal.valueOf(cents, 2))
    fun editPrice(cents: Long): String = BigDecimal.valueOf(cents, 2).stripTrailingZeros().toPlainString()
}
