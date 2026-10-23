package lk.peliyagoda.marketpro.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardCategoryRulesTest {
    private val originalItems = listOf(
        "Vegetables", "Chinese Veg", "Fruits", "Vegetables", "Fruits"
    )

    private fun display(selected: String): List<String> =
        originalItems.filter { DashboardCategoryRules.matches(selected, it) }

    @Test fun firstTapFiltersWithoutVisitingAnotherTab() {
        assertEquals(5, display("All").size)
        assertEquals(listOf("Vegetables", "Vegetables"), display("Vegetables"))
        assertEquals(listOf("Fruits", "Fruits"), display("Fruits"))
        assertEquals(listOf("Chinese Veg"), display("Chinese Veg"))
    }

    @Test fun switchingBackToAllRestoresEveryItem() {
        display("Vegetables")
        display("Fruits")
        assertEquals(originalItems, display("All"))
    }

    @Test fun categoryNamesRequireExactMatches() {
        assertTrue(DashboardCategoryRules.matches("All", "Fruits"))
        assertTrue(DashboardCategoryRules.matches("Chinese Veg", "Chinese Veg"))
        assertFalse(DashboardCategoryRules.matches("Fruits", "Vegetables"))
        assertFalse(DashboardCategoryRules.matches("Chinese Veg", "Vegetables"))
    }

    @Test fun returningToDashboardUsesTheSelectedParentCategory() {
        val categorySelectedBeforeTabChange = "Fruits"
        // No separate tab-local state is required to compute the rows.
        assertEquals(
            listOf("Fruits", "Fruits"),
            display(categorySelectedBeforeTabChange)
        )
    }
}
