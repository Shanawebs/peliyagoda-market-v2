package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogReconciliationTest {
    private fun row(name: String, category: String, active: Boolean = true) =
        CatalogRecord(name, category, active)

    @Test fun archivedItemsRemainInCloudWithoutCountingAsActiveMismatch() {
        val phone = listOf(row("අයිස් බේජ්", "Chinese Veg"), row("අන්නාසි", "Fruits"))
        val cloud = phone + row("Old fruit name", "Fruits", active = false)
        val audit = CatalogReconciliation.compare(phone, cloud)
        assertTrue(audit.fullyMatched)
        assertEquals(2, audit.matchedActive)
        assertEquals(2, audit.remoteActive)
        assertEquals(1, audit.remoteArchived)
        assertTrue(audit.onlyInCloud.isEmpty())
    }

    @Test fun categoryDifferencesAreIdentifiedAndNeverSilentlyMerged() {
        val phone = listOf(row("Carrot", "Vegetables"))
        val cloud = listOf(row("Carrot", "Chinese Veg"))
        val audit = CatalogReconciliation.compare(phone, cloud)
        assertFalse(audit.fullyMatched)
        assertEquals(listOf("Carrot"), audit.differentCategory)
        assertEquals(0, audit.matchedActive)
    }

    @Test fun whitespaceAndCaseNormalizationAvoidsFalseMismatches() {
        val phone = listOf(row("  Baby   Carrot ", "VEGETABLES"))
        val cloud = listOf(row("baby carrot", "Vegetables"))
        assertTrue(CatalogReconciliation.compare(phone, cloud).fullyMatched)
    }

    @Test fun differentItemNamesAreFlaggedWithoutAnyMutation() {
        val phone = listOf(row("A", "Fruits"))
        val cloud = listOf(row("B", "Fruits"))
        val audit = CatalogReconciliation.compare(phone, cloud)
        assertFalse(audit.fullyMatched)
        assertEquals(listOf("A"), audit.onlyOnPhone)
        assertEquals(listOf("B"), audit.onlyInCloud)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateActiveRecordsFailClosed() {
        CatalogReconciliation.compare(
            listOf(row("Carrot", "Vegetables"), row(" carrot ", "vegetables")),
            emptyList()
        )
    }
}
