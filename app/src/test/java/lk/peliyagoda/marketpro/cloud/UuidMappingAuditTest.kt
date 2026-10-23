package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UuidMappingAuditTest {
    private val a = "11111111-1111-4111-8111-111111111111"
    private val b = "22222222-2222-4222-8222-222222222222"
    private val c = "33333333-3333-4333-8333-333333333333"
    private fun p(type: String, key: String, id: String?) =
        UuidAuditRecord(type, key, id)

    @Test fun restoredJsonSameContentButNewUuidMustNotPass() {
        val local = listOf(p("item", "carrot|vegetables", b))
        val remote = listOf(p("item", "carrot|vegetables", a))
        val result = UuidMappingAudit.compare(local, remote, pendingOperations = 47)
        val item = result.sections.single { it.type == "item" }
        assertEquals(1, item.differentUuid)
        assertEquals(0, item.matchingUuid)
        assertEquals(47, result.pendingUploadOperations)
        assertTrue(result.isReadOnly)
    }

    @Test fun identicalUuidOnlyWhenSemanticKeyAndUuidBothEqual() {
        val result = UuidMappingAudit.compare(
            listOf(p("item", "a", a), p("item", "b", b)),
            listOf(p("item", "b", b), p("item", "a", a)), 0
        )
        assertEquals(2, result.equalIds)
        assertEquals(0, result.differentIds)
    }

    @Test fun missingLocalUuidAndUnpairedRowsAreReportedSeparately() {
        val result = UuidMappingAudit.compare(
            listOf(p("purchase", "a", null), p("purchase", "onlyPhone", c)),
            listOf(p("purchase", "a", a), p("purchase", "onlyCloud", b)), 8
        )
        val value = result.sections.single { it.type == "purchase" }
        assertEquals(1, value.absentLocalUuid)
        assertEquals(1, value.phoneOnly)
        assertEquals(1, value.cloudOnly)
    }

    @Test fun identicalPurchaseLotsCannotBeMatchedByListOrder() {
        val key = "2026-10-20|carrot|2000|500"
        val result = UuidMappingAudit.compare(
            listOf(p("purchase_addition", key, a), p("purchase_addition", key, b)),
            listOf(p("purchase_addition", key, b), p("purchase_addition", key, a)), 0
        )
        val lots = result.sections.single { it.type == "purchase_addition" }
        assertEquals(2, lots.ambiguous)
        assertEquals(0, lots.matchingUuid)
    }

    @Test fun archiveRecordsAreTrackedAndCannotEnableCloudWriter() {
        val result = UuidMappingAudit.compare(emptyList(), emptyList(), 0, archivedRemoteRows = 13)
        assertEquals(13, result.archivedRemoteRows)
        assertTrue(result.isReadOnly)
    }

    @Test fun invalidUuidCannotBeReportedAsMatching() {
        val result = UuidMappingAudit.compare(
            listOf(p("category", "fruits", "not-a-uuid")),
            listOf(p("category", "fruits", a)), 0
        )
        assertEquals(1, result.sections.single { it.type == "category" }.absentLocalUuid)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unexpectedEntityTypeFailsClosed() {
        UuidMappingAudit.compare(
            listOf(p("photo", "invoice", a)), emptyList(), 0
        )
    }
}
