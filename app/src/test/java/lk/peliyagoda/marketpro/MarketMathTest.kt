package lk.peliyagoda.marketpro

import lk.peliyagoda.marketpro.util.MarketMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarketMathTest {
    @Test fun kilogramsToGrams() {
        assertEquals(12_500L, MarketMath.parseGrams("12.5"))
        assertEquals(1L, MarketMath.parseGrams("0.001"))
        assertNull(MarketMath.parseGrams("0.0001"))
        assertNull(MarketMath.parseGrams("-5"))
        assertNull(MarketMath.parseGrams("xyz"))
    }

    @Test fun pricesToCents() {
        assertEquals(15_025L, MarketMath.parsePriceCents("150.25"))
        assertNull(MarketMath.parsePriceCents("150.123"))
        assertNull(MarketMath.parsePriceCents("-2"))
    }

    @Test fun marketLineTotal() {
        assertEquals(195_000L, MarketMath.billCents(13_000, 15_000))
        assertEquals(228_000L, MarketMath.billCents(19_000, 12_000))
        assertEquals(423_000L, 195_000L + 228_000L)
    }

    @Test fun blankInputsAreZeroButFieldsStayVisuallyEmpty() {
        assertEquals("", MarketMath.editKgOrBlank(0))
        assertEquals("", MarketMath.editPriceOrBlank(0))
        assertEquals(0L, MarketMath.parseBlankGrams(""))
        assertEquals(0L, MarketMath.parseBlankPriceCents(" "))
        assertEquals("1.25", MarketMath.editKgOrBlank(1250))
        assertEquals("150.5", MarketMath.editPriceOrBlank(15050))
        assertEquals(1250L, MarketMath.parseBlankGrams("1.250"))
        assertEquals(15050L, MarketMath.parseBlankPriceCents("150.50"))
        assertNull(MarketMath.parseBlankGrams("0.0001"))
        assertNull(MarketMath.parseBlankPriceCents("1.234"))
    }

    @Test fun roundSmallAmountsCorrectly() {
        assertEquals(1L, MarketMath.billCents(5, 100)) // 5g at Rs 1/kg => half cent -> 1 cent
    }
}
