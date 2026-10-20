package lk.peliyagoda.marketpro

import lk.peliyagoda.marketpro.util.CashAmountFormatter
import lk.peliyagoda.marketpro.util.MarketMath
import org.junit.Assert.assertEquals
import org.junit.Test

class CashAmountFormatterTest {
    @Test fun groupsCashAmountsWhilePreservingDecimalDigits() {
        assertEquals("", CashAmountFormatter.grouped(""))
        assertEquals("0", CashAmountFormatter.grouped("0"))
        assertEquals("999", CashAmountFormatter.grouped("999"))
        assertEquals("1,000", CashAmountFormatter.grouped("1000"))
        assertEquals("600,000", CashAmountFormatter.grouped("600000"))
        assertEquals("1,234,567.50", CashAmountFormatter.grouped("1234567.50"))
        assertEquals("1,234.", CashAmountFormatter.grouped("1234."))
        assertEquals(".50", CashAmountFormatter.grouped(".50"))
    }

    @Test fun commaIsVisualOnlyAndSaveUsesExactCents() {
        val raw = CashAmountFormatter.cleanInput("600,000.50")
        assertEquals("600000.50", raw)
        assertEquals(60_000_050L, MarketMath.parseBlankPriceCents(raw))
        assertEquals(0L, MarketMath.parseBlankPriceCents(
            CashAmountFormatter.cleanInput("")
        ))
        assertEquals("0", CashAmountFormatter.grouped("0"))
    }
}
