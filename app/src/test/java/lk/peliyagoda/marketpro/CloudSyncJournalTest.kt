package lk.peliyagoda.marketpro

import lk.peliyagoda.marketpro.data.CloudAction
import lk.peliyagoda.marketpro.data.CloudRecordKey
import lk.peliyagoda.marketpro.data.CloudRecordType
import org.junit.Assert.*
import org.junit.Test

class CloudSyncJournalTest {
    @Test fun stableKeysUseExistingPersistedKitchenNames() {
        assertEquals("2026-10-21|Main Kitchen|8", CloudRecordKey.requisition("2026-10-21", "Main Kitchen", 8))
        assertEquals("2026-10-21|Pastry Kitchen|8", CloudRecordKey.requisition("2026-10-21", "Pastry Kitchen", 8))
    }

    @Test fun purchaseAndCashKeysAreStable() {
        assertEquals("2026-10-21|99", CloudRecordKey.purchase("2026-10-21",99))
        assertEquals("2026-10-21", CloudRecordKey.cash("2026-10-21"))
        assertEquals("42", CloudRecordKey.addition(42))
        assertEquals("2026-10-21", CloudRecordKey.otherExpense("2026-10-21"))
        assertEquals("apple", CloudRecordKey.category(" APPLE "))
    }

    @Test fun invalidLocalKeysAreRejectedBeforeQueuing() {
        assertThrows(IllegalArgumentException::class.java) { CloudRecordKey.item(0) }
        assertThrows(IllegalArgumentException::class.java) { CloudRecordKey.requisition("2026-10-21","Bar/Pastry",1) }
        assertThrows(java.time.format.DateTimeParseException::class.java) { CloudRecordKey.cash("not-a-date") }
        assertThrows(IllegalArgumentException::class.java) { CloudRecordKey.category("   ") }
    }

    @Test fun scopeExplicitlyExcludesAllInvoicePhotoAndFileTypes() {
        val allowed = CloudRecordType.values().map { it.wireName }.toSet()
        assertEquals(7,allowed.size)
        assertTrue(allowed.containsAll(setOf(
            "category","item","requisition","purchase","purchase_addition","daily_cash_received","daily_other_expenses"
        )))
        assertFalse(allowed.any { it.contains("photo") || it.contains("invoice") || it.contains("file") || it.contains("storage") })
        assertEquals("DELETE",CloudAction.DELETE.name)
    }
}
