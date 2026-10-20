package lk.peliyagoda.marketpro.util

/**
 * Display-only grouping for editable cash amounts. The raw value deliberately
 * remains comma-free, so MarketMath still parses exact BigDecimal cents.
 */
object CashAmountFormatter {
    fun grouped(raw: String): String {
        if (raw.isEmpty()) return ""
        val decimalIndex = raw.indexOf('.')
        val wholeEnd = if (decimalIndex >= 0) decimalIndex else raw.length
        return buildString(raw.length + raw.length / 3) {
            raw.forEachIndexed { i, char ->
                if (i > 0 && i < wholeEnd && (wholeEnd - i) % 3 == 0) append(',')
                append(char)
            }
        }
    }

    fun cleanInput(value: String): String = value.replace(",", "")
}
