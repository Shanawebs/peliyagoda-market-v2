package lk.peliyagoda.marketpro

import lk.peliyagoda.marketpro.data.ItemEntity
import lk.peliyagoda.marketpro.data.MarketConstants
import lk.peliyagoda.marketpro.data.RequisitionEntity
import lk.peliyagoda.marketpro.util.groupKitchenRequirements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KitchenCategoryGroupsTest {
    private val date = "2026-10-21"
    private val items = listOf(
        ItemEntity(1, "Carrot", "Vegetables"),
        ItemEntity(2, "Banana", "Fruits"),
        ItemEntity(3, "Apple", "Fruits"),
        ItemEntity(4, "Beans", "Vegetables"),
        ItemEntity(5, "Bok Choy", "Chinese Veg"),
        ItemEntity(6, "අන්නාසි", "Fruits")
    )
    private val requirements = listOf(
        RequisitionEntity(date, MarketConstants.MAIN, 1, 12_000),
        RequisitionEntity(date, MarketConstants.MAIN, 2, 5_000),
        RequisitionEntity(date, MarketConstants.MAIN, 3, 0),
        RequisitionEntity(date, MarketConstants.MAIN, 4, 3_500),
        RequisitionEntity(date, MarketConstants.MAIN, 5, 9_000),
        RequisitionEntity(date, MarketConstants.INDIAN, 1, 50_000),
        RequisitionEntity(date, MarketConstants.INDIAN, 6, 4_000),
        RequisitionEntity(date, MarketConstants.PASTRY, 3, 8_000)
    )

    @Test fun mainKitchenIsGroupedAtoZWithCategorySubtotals() {
        val groups = groupKitchenRequirements(items, requirements, MarketConstants.MAIN)
        assertEquals(listOf("Chinese Veg", "Fruits", "Vegetables"), groups.map { it.category })
        assertEquals(listOf("Apple", "Banana"),
            groups[1].rows.map { it.item.name })
        assertEquals(listOf("Beans", "Carrot"),
            groups[2].rows.map { it.item.name })
        assertEquals(listOf(9_000L, 5_000L, 15_500L),
            groups.map { it.subtotalGrams })
        assertEquals(29_500L, groups.sumOf { it.subtotalGrams })
        assertEquals(listOf(1, 2, 2), groups.map { it.itemCount })
    }

    @Test fun eachKitchenUsesOnlyItsOwnRequiredQuantities() {
        val indian = groupKitchenRequirements(items, requirements, MarketConstants.INDIAN)
        assertEquals(listOf("Fruits", "Vegetables"), indian.map { it.category })
        assertEquals(listOf(4_000L, 50_000L), indian.map { it.subtotalGrams })
        assertEquals(54_000L, indian.sumOf { it.subtotalGrams })

        val pastry = groupKitchenRequirements(items, requirements, MarketConstants.PASTRY)
        assertEquals(listOf("Fruits"), pastry.map { it.category })
        assertEquals(8_000L, pastry.single().subtotalGrams)
    }

    @Test fun zeroQuantitiesRemainVisibleAndAreEditable() {
        val fruits = groupKitchenRequirements(items, requirements, MarketConstants.MAIN)
            .first { it.category == "Fruits" }
        assertEquals(2, fruits.rows.size)
        assertEquals(0L, fruits.rows.first().requirement.quantityGrams)
    }

    @Test fun newCategoriesAppearAutomaticallyAndUnknownItemsDoNotCrash() {
        val withAnotherCategory = items + ItemEntity(99, "Parsley", "Herbs")
        val reqs = requirements +
            RequisitionEntity(date, MarketConstants.MAIN, 99, 2_500) +
            RequisitionEntity(date, MarketConstants.MAIN, 123456, 1_500)
        val groups = groupKitchenRequirements(withAnotherCategory, reqs, MarketConstants.MAIN)
        assertEquals(listOf("Chinese Veg", "Fruits", "Herbs", "Vegetables"),
            groups.map { it.category })
        assertEquals(2_500L, groups[2].subtotalGrams)
        assertEquals(6, groups.sumOf { it.itemCount })
    }

    @Test fun emptyRequisitionsProduceNoHeaders() {
        assertTrue(groupKitchenRequirements(items, emptyList(), MarketConstants.MAIN).isEmpty())
    }
}
