package lk.peliyagoda.marketpro

import lk.peliyagoda.marketpro.data.MarketCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketCatalogTest {
    @Test fun catalogContainsAll171ItemsInCorrectCategories() {
        val entries = MarketCatalog.entries
        assertEquals(171, entries.size)
        assertEquals(28, entries.count { it.second == MarketCatalog.CHINESE_VEG })
        assertEquals(114, entries.count { it.second == "Vegetables" })
        assertEquals(29, entries.count { it.second == "Fruits" })
        assertEquals(listOf("Chinese Veg", "Vegetables", "Fruits"), MarketCatalog.categories)
    }

    @Test fun noBlankDuplicateOrOversizedItemNames() {
        val entries = MarketCatalog.entries
        assertTrue(entries.all { (name, category) ->
            name.isNotBlank() && name.length <= 80 && category in MarketCatalog.categories
        })
        assertEquals(entries.size, entries.map { it.first.lowercase() }.distinct().size)
    }

    @Test fun sinhalaNamesAndUserSpecifiedCategoryArePreserved() {
        val expected = mapOf(
            "බ්‍රොකොලි" to "Chinese Veg",
            "චයිනීස් ගෝවා" to "Chinese Veg",
            "කැරට්" to "Vegetables",
            "අමු ඉඟුරු" to "Vegetables",
            "ඉඟුරු" to "Fruits",
            "අන්නාසි" to "Fruits",
            "ස්ට්‍රෝබෙරි" to "Fruits"
        )
        val lookup = MarketCatalog.entries.toMap()
        expected.forEach { (name, category) -> assertEquals(category, lookup[name]) }
    }
}
