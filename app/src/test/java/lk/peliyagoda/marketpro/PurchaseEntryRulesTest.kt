package lk.peliyagoda.marketpro

import lk.peliyagoda.marketpro.data.ItemEntity
import lk.peliyagoda.marketpro.data.PurchaseAdditionEntity
import lk.peliyagoda.marketpro.data.PurchaseEntity
import lk.peliyagoda.marketpro.data.RequisitionEntity
import lk.peliyagoda.marketpro.util.MarketMath
import lk.peliyagoda.marketpro.util.PurchaseEntryRules
import lk.peliyagoda.marketpro.util.consolidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseEntryRulesTest {
    @Test fun addExtraRequiresPositiveOriginalQuantityAndPriceAndOutstandingShortage() {
        val f = PurchaseEntryRules::canAddAnotherPurchase
        assertFalse(f(null, null, 45_000, 0))
        assertFalse(f(0, 0, 45_000, 0))
        assertFalse(f(1_000, 0, 45_000, 1_000))
        assertFalse(f(0, 20_000, 45_000, 0))
        assertTrue(f(1_000, 20_000, 45_000, 1_000))
        assertTrue(f(30_000, 20_000, 45_000, 44_000))
        assertFalse(f(30_000, 20_000, 45_000, 45_000))
        assertFalse(f(40_000, 20_000, 45_000, 46_000))
    }

    @Test fun blankOrTypedZeroMapsToSavedZeroAndNotToInvalid() {
        assertEquals(0L, PurchaseEntryRules.blankQuantity(""))
        assertEquals(0L, PurchaseEntryRules.blankQuantity("   "))
        assertEquals(0L, PurchaseEntryRules.blankQuantity("0"))
        assertEquals(0L, PurchaseEntryRules.blankUnitPrice(""))
        assertEquals(0L, PurchaseEntryRules.blankUnitPrice("0"))
        assertEquals("", MarketMath.editKgOrBlank(0))
        assertEquals("", MarketMath.editPriceOrBlank(0))
        assertEquals(0L, MarketMath.billCents(0, 14_000))
        assertEquals(0L, MarketMath.billCents(8_000, 0))
    }

    @Test fun clearingAdditionalLotKeepsLotAndRecalculatesToZeroCost() {
        val d = "2026-10-20"
        val items = listOf(ItemEntity(5, "Lettuce", "Vegetables"))
        val r = listOf(RequisitionEntity(d, "Main Kitchen", 5, 5_000))
        val primary = listOf(PurchaseEntity(d, 5, 3_000, 5_000))
        val extra = listOf(PurchaseAdditionEntity(11, d, 5, 2_000, 4_000))
        val original = consolidate(items, r, primary, extra).single()
        assertEquals(5_000L, original.purchasedGrams)
        assertEquals(23_000L, original.lineCents)
        val cleared = consolidate(
            items, r, primary, listOf(extra.single().copy(quantityGrams = 0, priceCentsPerKg = 0))
        ).single()
        assertEquals(1, cleared.additions.size)
        assertEquals(11L, cleared.additions.single().id)
        assertEquals(3_000L, cleared.purchasedGrams)
        assertEquals(15_000L, cleared.lineCents)
        assertEquals(-2_000L, cleared.varianceGrams)
    }

    @Test fun invalidNegativeOrOutOfRangeValuesAreNotAccepted() {
        assertEquals(null, PurchaseEntryRules.blankQuantity("-1"))
        assertEquals(null, PurchaseEntryRules.blankUnitPrice("-2"))
        assertEquals(null, PurchaseEntryRules.blankQuantity("oops"))
        assertFalse(PurchaseEntryRules.canAddAnotherPurchase(1_000_000_001, 1, 3_000_000_000, 1_000))
    }
}
